#!/usr/bin/env python3
"""Recover only public Nuvio Supabase configuration from a pinned official ARMv7 APK."""
import argparse
import base64
import hashlib
import json
import re
import subprocess
import time
from pathlib import Path

from apk_inspection import dex_strings
from verify_apk import tool
from verify_release_config import properties, validate

REFERENCE_SHA256 = '9ed2b43973714e8d98bb1aa076a306c2ec0faf85480df4124f0ab742271babe1'
REFERENCE_SIGNER = '7ce8717c8a2610ddc6c3729abc42a8a6a5f571d3b44b39b2f78b7f8083da2662'
PRIMARY = 'https://api.nuvio.tv'
FALLBACK = 'https://api-two.nuvioapp.space'


def public_anon_key(strings, now=None):
    candidates = set()
    for value in strings:
        pieces = value.split('.')
        if not value.startswith('eyJ') or len(pieces) != 3:
            continue
        try:
            claims = json.loads(base64.urlsafe_b64decode(pieces[1] + '=' * (-len(pieces[1]) % 4)))
        except (ValueError, TypeError):
            continue
        if claims.get('role') != 'anon':
            continue
        assert not claims.get('sub') and not claims.get('email'), 'User credential rejected'
        assert claims.get('iss') == 'supabase', 'Unexpected public token issuer'
        assert claims.get('exp', 0) > (time.time() if now is None else now), 'Expired public anon key'
        candidates.add(value)
    assert len(candidates) == 1, 'Official public anon key is absent or ambiguous'
    return candidates.pop()


def prepare(apk, output, report_path, online=True):
    apk = Path(apk)
    assert hashlib.sha256(apk.read_bytes()).hexdigest() == REFERENCE_SHA256, 'Official reference APK digest changed'
    signature = subprocess.check_output([tool('apksigner'), 'verify', '--print-certs', str(apk)], text=True)
    signer = re.search(r'Signer #1 certificate SHA-256 digest: ([a-fA-F0-9]+)', signature).group(1).lower()
    assert signer == REFERENCE_SIGNER, 'Official reference signing certificate changed'
    strings = dex_strings(apk)
    assert PRIMARY in strings and FALLBACK in strings, 'Original Supabase server constants missing'
    assert 'https://nuvio.tv/link' in strings and 'https://nuvio.tv/tv-login' in strings, 'Original QR URLs absent'
    anon = public_anon_key(strings)
    output = Path(output)
    # Keep an optionally supplied complete upstream configuration. Never restore signing fields.
    lines = output.read_text().splitlines() if output.exists() else []
    replaced = {'sdk.dir', 'NUVIO_SUPABASE_URL', 'NUVIO_SUPABASE_ANON_KEY', 'NUVIO_SUPABASE_FALLBACK_URL',
                'ANILIST_CLIENT_ID', 'ANILIST_REDIRECT_URI'}
    lines = [line for line in lines if line.partition('=')[0].strip() not in replaced
             and not line.lstrip().startswith('NUVIO_RELEASE_')]
    lines += ['NUVIO_SUPABASE_URL=' + PRIMARY, 'NUVIO_SUPABASE_ANON_KEY=' + anon,
              'NUVIO_SUPABASE_FALLBACK_URL=' + FALLBACK, 'ANILIST_CLIENT_ID=', 'ANILIST_REDIRECT_URI=']
    output.write_text('\n'.join(lines) + '\n')
    output.chmod(0o600)
    check = validate(properties(output), online=online, require_anilist=False)
    report = {'reference_release': 'v2.3.1-lite', 'reference_apk_sha256': REFERENCE_SHA256,
              'reference_signer_sha256': signer, 'supabase_public_role': 'anon',
              'supabase_primary': PRIMARY, 'supabase_fallback_preserved': FALLBACK,
              'supabase_settings_http': check['supabase_settings_http'],
              'configuration_mechanism': 'upstream local.properties',
              'anilist_oauth': 'not_configured',
              'anilist_warning': 'AniList cannot connect: authorized TV Client ID and registered HTTPS callback are missing.'}
    Path(report_path).write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('apk')
    parser.add_argument('--properties', default='local.properties')
    parser.add_argument('--output', default='diagnostic-config.json')
    args = parser.parse_args()
    prepare(args.apk, args.properties, args.output)
