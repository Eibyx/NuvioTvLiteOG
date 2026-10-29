import base64
import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'anilist'))
from prepare_diagnostic_config import public_anon_key
from apk_inspection import certificate_sha256
from verify_release_config import validate


def token(claims):
    head = base64.urlsafe_b64encode(b'{"alg":"HS256"}').decode().rstrip('=')
    body = base64.urlsafe_b64encode(json.dumps(claims).encode()).decode().rstrip('=')
    return head + '.' + body + '.testsignature'


class DiagnosticConfigurationTests(unittest.TestCase):
    def test_missing_oauth_is_permitted_only_in_explicit_diagnostic_validation(self):
        values = {'NUVIO_SUPABASE_URL': 'https://example.supabase.co',
                  'NUVIO_SUPABASE_ANON_KEY': token({'role': 'anon'})}
        self.assertTrue(validate(values, require_anilist=False)['public_configuration_valid'])
        with self.assertRaisesRegex(ValueError, 'ANILIST_CLIENT_ID'):
            validate(values)

    def test_diagnostic_mode_still_rejects_privileged_key(self):
        with self.assertRaisesRegex(AssertionError, 'Privileged'):
            validate({'NUVIO_SUPABASE_URL': 'https://example.supabase.co',
                      'NUVIO_SUPABASE_ANON_KEY': token({'role': 'service_role'})}, require_anilist=False)

    def test_reference_extraction_only_selects_one_valid_public_anon_key(self):
        public = token({'role': 'anon', 'iss': 'supabase', 'exp': 2000})
        admin = token({'role': 'service_role', 'iss': 'supabase', 'exp': 2000})
        self.assertEqual(public, public_anon_key({public, admin, 'unrelated'}, now=1000))
        with self.assertRaisesRegex(AssertionError, 'absent or ambiguous'):
            public_anon_key({admin}, now=1000)

    def test_expired_or_user_bearing_anon_keys_are_rejected(self):
        with self.assertRaisesRegex(AssertionError, 'Expired'):
            public_anon_key({token({'role': 'anon', 'iss': 'supabase', 'exp': 1})}, now=1000)
        with self.assertRaisesRegex(AssertionError, 'User credential'):
            public_anon_key({token({'role': 'anon', 'iss': 'supabase', 'exp': 2000, 'sub': 'user'})}, now=1000)


class SigningOutputTests(unittest.TestCase):
    def test_sdk_range_in_signer_label_does_not_change_certificate_verification(self):
        digest = 'ab' * 32
        for label in ('Signer #1', 'Signer #1 (minSdkVersion=24, maxSdkVersion=35)'):
            self.assertEqual(digest, certificate_sha256(label + ' certificate SHA-256 digest: ' + digest))
        with self.assertRaises(AssertionError):
            certificate_sha256('No certificate')
        with self.assertRaises(AssertionError):
            certificate_sha256('certificate SHA-256 digest: ' + digest + '\ncertificate SHA-256 digest: ' + 'cd' * 32)
