#!/usr/bin/env python3
"""Check a single signed Lite ARMv7 APK and its embedded public build configuration."""
import argparse, hashlib, json, os, re, shutil, subprocess, sys, zipfile
from pathlib import Path
from verify_release_config import properties, validate

def tool(name):
    sdk=Path(os.environ.get('ANDROID_HOME',os.environ.get('ANDROID_SDK_ROOT','/nonexistent')))
    candidates=sorted(sdk.glob('build-tools/*/'+name),reverse=True)
    return str(candidates[0]) if candidates else shutil.which(name) or name

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('apk');p.add_argument('--properties',default='local.properties');p.add_argument('--expected-signer');p.add_argument('--output',default='apk-verification.json');a=p.parse_args()
    values=properties(a.properties);validate(values)
    path=Path(a.apk)
    with zipfile.ZipFile(path) as z:
        assert z.testzip() is None,'APK ZIP integrity failure'
        native=[n for n in z.namelist() if n.startswith('lib/') and n.endswith('.so')]
        assert native and {n.split('/')[1] for n in native}=={'armeabi-v7a'},'APK must contain only ARMv7 native libraries'
        for name in native:
            elf=z.read(name)
            assert elf[:4]==b'\x7fELF' and elf[4]==1 and int.from_bytes(elf[18:20],'little')==40,'Invalid ARM32 ELF: '+name
        assert any('mpv' in n.lower() for n in native),'Original MPV native payload missing'
        assert any('ass' in n.lower() for n in native),'Original subtitle native payload missing'
        assert not any(n.endswith(('.jks','.keystore','.pem','.p12')) or Path(n).name in ('.env','local.properties','local.dev.properties') for n in z.namelist()),'Private configuration file packaged'
        dex=b''.join(z.read(n) for n in z.namelist() if re.fullmatch(r'classes\d*\.dex',n))
        for key in ['NUVIO_SUPABASE_URL','NUVIO_SUPABASE_ANON_KEY','ANILIST_CLIENT_ID','ANILIST_REDIRECT_URI']:
            assert values[key].encode() in dex,'Expected public build constant absent from APK: '+key
        assert not re.search(rb'sb_secret_[A-Za-z0-9_-]{15,}|gh[pousr]_[A-Za-z0-9_]{30,}|-----BEGIN (?:RSA |EC )?PRIVATE KEY-----',dex),'Private credential pattern found in APK'
        for jwt in re.findall(rb'eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+',dex):
            import base64
            try: claims=json.loads(base64.urlsafe_b64decode(jwt.split(b'.')[1]+b'='*(-len(jwt.split(b'.')[1])%4)))
            except (ValueError,TypeError): continue
            assert claims.get('role') not in ('service_role','authenticated') and not claims.get('email'),'Privileged or user JWT in APK'
    signature=subprocess.check_output([tool('apksigner'),'verify','--verbose','--print-certs',str(path)],text=True)
    signer=re.search(r'Signer #1 certificate SHA-256 digest: ([a-fA-F0-9]+)',signature).group(1).lower()
    badging=subprocess.check_output([tool('aapt'),'dump','badging',str(path)],text=True)
    package=re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'",badging)
    assert package and package[1]=='com.nuvio.tv.lite','Unexpected Lite package ID'
    assert int(package[2])>10023,'versionCode must exceed the original Lite version'
    assert "native-code: 'armeabi-v7a'" in badging
    expected=a.expected_signer.replace(':','').lower() if a.expected_signer else None
    if expected: assert signer==expected,'Signing certificate does not match expected installed build'
    result={'apk':path.name,'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'package':package[1],
        'version_code':int(package[2]),'version_name':package[3],'abi':'armeabi-v7a','signer_sha256':signer,
        'update_certificate_verified':bool(expected),'native_libraries':native,'zip_integrity':True,'public_config_embedded':True}
    Path(a.output).write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2))
