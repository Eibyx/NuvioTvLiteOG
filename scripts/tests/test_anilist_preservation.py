import base64
import importlib.util
import json
from pathlib import Path
import sys
import unittest

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'scripts/anilist'))
from verify_preservation import verify
from verify_release_config import validate

class AniListPreservationTests(unittest.TestCase):
    def test_original_players_native_auth_and_qr_are_preserved(self):
        result=verify()
        self.assertTrue(result['qr_auth_supabase_original'])
        self.assertTrue(result['player_native_addons_themes_original'])
        self.assertEqual(1665,result['original_entries'])

    def test_missing_public_configuration_stops_publication(self):
        with self.assertRaisesRegex(ValueError,'NUVIO_SUPABASE_URL'):
            validate({})

    def test_privileged_supabase_key_is_rejected(self):
        def token(role):
            body=base64.urlsafe_b64encode(json.dumps({'role':role}).encode()).decode().rstrip('=')
            return 'header.'+body+'.signature'
        values={'NUVIO_SUPABASE_URL':'https://example.supabase.co','NUVIO_SUPABASE_ANON_KEY':token('service_role'),
                'ANILIST_CLIENT_ID':'12345','ANILIST_REDIRECT_URI':'https://example.org/callback.html'}
        with self.assertRaisesRegex(AssertionError,'Privileged'):
            validate(values)
        values['NUVIO_SUPABASE_ANON_KEY']=token('anon')
        self.assertTrue(validate(values)['public_configuration_valid'])

    def test_import_projection_has_no_history_or_other_tracker_writes(self):
        source=(ROOT/'app/src/main/java/com/nuvio/tv/data/anilist/AniListTvProjection.kt').read_text()
        for forbidden in ('markAsCompleted(', 'markAsWatched(', 'TrackingHistoryWriter', 'Supabase', 'TraktRepository', 'SimklMutation'):
            self.assertNotIn(forbidden,source)

if __name__=='__main__': unittest.main()
