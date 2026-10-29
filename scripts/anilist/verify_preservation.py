#!/usr/bin/env python3
"""Enforce the reviewed allowlist against the exact Lite original commit."""
import argparse, hashlib, json, subprocess
from pathlib import Path

UPSTREAM = '9bd27940ac8669b836807e6143feb0950fe0049d'
ALLOWED = {
    'app/src/androidTest/java/com/nuvio/tv/ui/screens/settings/TrackingSettingsOverviewTest.kt',
    'app/build.gradle.kts',
    'app/src/main/AndroidManifest.xml',
    'app/src/main/java/com/nuvio/tv/data/repository/WatchProgressRepositoryImpl.kt',
    'app/src/main/java/com/nuvio/tv/ui/screens/detail/MetaDetailsViewModel.kt',
    'app/src/main/java/com/nuvio/tv/ui/screens/settings/TrackingSettingsScreen.kt',
}
PROTECTED = [
    'app/src/main/java/com/nuvio/tv/core/auth/AuthManager.kt',
    'app/src/main/java/com/nuvio/tv/core/di/SupabaseModule.kt',
    'app/src/main/java/com/nuvio/tv/data/local/ServerConfigurationStore.kt',
]

def verify(ref=None):
    def tree(commit):
        result={}
        for record in subprocess.check_output(['git','ls-tree','-rz',commit]).split(b'\0'):
            if not record: continue
            header,name=record.split(b'\t',1)
            mode,kind,oid=header.decode().split()
            result[name.decode()]=(mode,oid)
        return result
    original=tree(UPSTREAM)
    current=tree(ref or 'HEAD')
    changed,identical=[],[]
    for name,(mode,expected) in original.items():
        if ref or mode=='160000':
            actual=current.get(name,('',None))[1]
        else:
            payload=Path(name).read_bytes()
            actual=hashlib.sha1(b'blob '+str(len(payload)).encode()+b'\0'+payload).hexdigest()
        (identical if expected==actual else changed).append(name)
    forbidden = sorted(set(changed) - ALLOWED)
    assert not forbidden, f'Unreviewed upstream modifications: {forbidden}'
    assert not set(changed).intersection(PROTECTED), 'Nuvio authentication was changed'
    return {'upstream_commit':UPSTREAM,'original_entries':len(original),'identical':len(identical),
        'modified_original_files':changed,'qr_auth_supabase_original':True,
        'player_native_addons_themes_original':True}

if __name__ == '__main__':
    p=argparse.ArgumentParser(); p.add_argument('--ref'); p.add_argument('--output'); args=p.parse_args()
    result=verify(args.ref); payload=json.dumps(result,indent=2)+'\n'
    if args.output: Path(args.output).write_text(payload)
    print(payload)
