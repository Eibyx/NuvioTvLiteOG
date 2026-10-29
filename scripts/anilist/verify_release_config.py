#!/usr/bin/env python3
"""Fail closed without printing configuration values, keys or credentials."""
import argparse, base64, json, re, urllib.request
from pathlib import Path

def properties(path):
    values={}
    for line in Path(path).read_text().splitlines():
        if not line.strip() or line.lstrip().startswith(('#','!')): continue
        k,sep,v=line.partition('=')
        if sep: values[k.strip()]=v.strip().replace('\\:',':').replace('\\=','=')
    return values

def validate(values, online=False, require_anilist=True):
    from urllib.parse import urlparse
    required=['NUVIO_SUPABASE_URL','NUVIO_SUPABASE_ANON_KEY']
    if require_anilist: required += ['ANILIST_CLIENT_ID','ANILIST_REDIRECT_URI']
    missing=[k for k in required if not values.get(k)]
    if missing: raise ValueError('Missing upstream build properties: '+', '.join(missing))
    url=urlparse(values['NUVIO_SUPABASE_URL'])
    assert url.scheme=='https' and url.hostname and not url.username and not url.fragment, 'Invalid Supabase HTTPS URL'
    key=values['NUVIO_SUPABASE_ANON_KEY']
    if not key.startswith('sb_publishable_'):
        pieces=key.split('.')
        assert len(pieces)==3,'Expected a public Supabase anon JWT or publishable key'
        claims=json.loads(base64.urlsafe_b64decode(pieces[1]+'='*(-len(pieces[1])%4)))
        assert claims.get('role')=='anon','Privileged/non-anon Supabase key rejected'
    if require_anilist:
        assert values['ANILIST_CLIENT_ID'].isdigit(),'Invalid AniList public client ID'
        redirect=urlparse(values['ANILIST_REDIRECT_URI'])
        assert redirect.scheme=='https' and redirect.hostname and not redirect.username and not redirect.fragment,'Invalid registered HTTPS OAuth callback'
    result={'public_configuration_valid':True,'supabase_settings_http':None,'oauth_client_registration_verified':False}
    if online:
        req=urllib.request.Request(values['NUVIO_SUPABASE_URL'].rstrip('/')+'/auth/v1/settings',headers={'apikey':key})
        try:
            with urllib.request.urlopen(req,timeout=20) as response:
                assert response.status==200
                body=json.load(response)
                assert isinstance(body,dict) and 'external' in body,'Unexpected Supabase auth settings response'
            result['supabase_settings_http']=200
        except Exception:
            raise ValueError('Original Supabase public auth endpoint could not be verified') from None
        # Verify the static callback is actually hosted. This does not establish OAuth registration.
        if not require_anilist: return result
        try:
            with urllib.request.urlopen(values['ANILIST_REDIRECT_URI'],timeout=20) as response:
                content=response.read(128*1024)
                assert response.status==200 and b'RSA-OAEP' in content and b'AES-GCM' in content
        except Exception:
            raise ValueError('Registered HTTPS callback page is unavailable or incorrect') from None
    return result

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--properties',default='local.properties');parser.add_argument('--online',action='store_true');args=parser.parse_args()
    try: print(json.dumps(validate(properties(args.properties),args.online),indent=2))
    except Exception as e: raise SystemExit(str(e))
