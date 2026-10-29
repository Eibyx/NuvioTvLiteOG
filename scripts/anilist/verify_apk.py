#!/usr/bin/env python3
"""Inspect a signed Lite ARMv7 APK; diagnostic mode never relaxes Supabase or ABI checks."""
import argparse
import base64
import hashlib
import json
import os
import re
import shutil
import subprocess
import zipfile
from pathlib import Path

from apk_inspection import dex_strings, native_payload
from verify_release_config import properties, validate


def tool(name):
    sdk = Path(os.environ.get('ANDROID_HOME', os.environ.get('ANDROID_SDK_ROOT', '/nonexistent')))
    candidates = sorted(sdk.glob('build-tools/*/' + name), reverse=True)
    return str(candidates[0]) if candidates else shutil.which(name) or name


def inspect(apk, values, expected_signer=None, diagnostic=False, build_config=None, reference=None):
    validate(values, require_anilist=not diagnostic)
    path = Path(apk)
    native = native_payload(path)
    assert any('mpv' in n.lower() for n in native), 'Original MPV native payload missing'
    assert any('ass' in n.lower() for n in native), 'Original subtitle native payload missing'
    constants = dex_strings(path)
    with zipfile.ZipFile(path) as archive:
        assert not any(n.endswith(('.jks', '.keystore', '.pem', '.p12')) or Path(n).name in
                       ('.env', 'local.properties', 'local.dev.properties') for n in archive.namelist()), 'Private configuration file packaged'
        dex = b''.join(archive.read(n) for n in archive.namelist() if re.fullmatch(r'classes\d*\.dex', n))
    config_keys = ['NUVIO_SUPABASE_URL', 'NUVIO_SUPABASE_ANON_KEY']
    if not diagnostic:
        config_keys += ['ANILIST_CLIENT_ID', 'ANILIST_REDIRECT_URI']
    for key in config_keys:
        assert values[key] in constants, 'Expected public build constant absent from APK: ' + key
    if values.get('NUVIO_SUPABASE_FALLBACK_URL'):
        assert values['NUVIO_SUPABASE_FALLBACK_URL'] in constants, 'Original fallback absent from APK'
    for key, default in [('TV_LOGIN_WEB_BASE_URL', 'https://nuvio.tv/tv-login'),
                         ('DEVICE_LOGIN_WEB_BASE_URL', 'https://nuvio.tv/link')]:
        assert values.get(key, default) in constants, 'Original QR URL absent: ' + key
    assert not re.search(rb'sb_secret_[A-Za-z0-9_-]{15,}|gh[pousr]_[A-Za-z0-9_]{30,}|-----BEGIN (?:RSA |EC )?PRIVATE KEY-----', dex), 'Private credential pattern found in APK'
    for token in re.findall(rb'eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+', dex):
        try:
            part = token.split(b'.')[1]
            claims = json.loads(base64.urlsafe_b64decode(part + b'=' * (-len(part) % 4)))
        except (ValueError, TypeError):
            continue
        assert claims.get('role') not in ('service_role', 'authenticated') and not claims.get('email'), 'Privileged or user JWT in APK'
    signature = subprocess.check_output([tool('apksigner'), 'verify', '--verbose', '--print-certs', str(path)], text=True)
    signer = re.search(r'Signer #1 certificate SHA-256 digest: ([a-fA-F0-9]+)', signature).group(1).lower()
    dn = re.search(r'Signer #1 certificate DN: (.+)', signature).group(1)
    badging = subprocess.check_output([tool('aapt'), 'dump', 'badging', str(path)], text=True)
    package = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
    assert package and package[1] == 'com.nuvio.tv.lite', 'Unexpected Lite package ID'
    assert int(package[2]) > 10023, 'versionCode must exceed original Lite'
    assert "native-code: 'armeabi-v7a'" in badging
    expected = expected_signer.replace(':', '').lower() if expected_signer else None
    if expected:
        assert signer == expected, 'Signing certificate does not match the expected certificate'
    native_match = None
    reference_signer = None
    if reference:
        original = native_payload(reference)
        assert set(native) == set(original), 'Native library set differs from original Lite ARMv7'
        assert native == original, 'Native library bytes differ from original Lite ARMv7'
        native_match = True
        ref_signature = subprocess.check_output([tool('apksigner'), 'verify', '--print-certs', str(reference)], text=True)
        reference_signer = re.search(r'Signer #1 certificate SHA-256 digest: ([a-fA-F0-9]+)', ref_signature).group(1).lower()
    if diagnostic:
        assert 'CN=Android Debug' in dn and 'O=Android' in dn, 'Diagnostic APK must use an Android debug certificate'
        assert expected, 'Supply the generated debug certificate fingerprint'
        assert native_match, 'A diagnostic APK requires the original native reference'
        assert 'diagnostic-debug-signed' in package[3], 'Diagnostic version label missing'
        assert not values.get('ANILIST_CLIENT_ID') and not values.get('ANILIST_REDIRECT_URI'), 'Diagnostic delivery must not use unverified AniList credentials'
        assert build_config, 'BuildConfig evidence required for missing AniList configuration'
        generated = Path(build_config).read_text()
        for field in ['ANILIST_CLIENT_ID', 'ANILIST_REDIRECT_URI']:
            match = re.search(r'\b' + field + r'\s*=\s*("(?:\\.|[^"\\])*")\s*;', generated)
            assert match and json.loads(match.group(1)) == '', 'AniList client unexpectedly configured'
    return {'apk': path.name, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest(), 'package': package[1],
            'version_code': int(package[2]), 'version_name': package[3], 'abi': 'armeabi-v7a',
            'signer_sha256': signer, 'signer_dn': dn, 'signing': 'android_debug' if diagnostic else 'configured',
            'update_certificate_verified': bool(expected) and not diagnostic,
            'official_reference_signer_sha256': reference_signer,
            'can_update_official_reference_certificate': signer == reference_signer if reference_signer else None,
            'native_libraries': native, 'native_payload_identical_to_original': native_match,
            'zip_integrity': True, 'public_config_embedded': True,
            'anilist_oauth': 'not_configured' if diagnostic else 'configured_unverified',
            'anilist_connection_verified': False, 'physical_tcl_tested': False}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('apk')
    parser.add_argument('--properties', default='local.properties')
    parser.add_argument('--expected-signer')
    parser.add_argument('--output', default='apk-verification.json')
    parser.add_argument('--diagnostic', action='store_true')
    parser.add_argument('--build-config')
    parser.add_argument('--reference-apk')
    args = parser.parse_args()
    result = inspect(args.apk, properties(args.properties), args.expected_signer, args.diagnostic,
                     args.build_config, args.reference_apk)
    Path(args.output).write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))
