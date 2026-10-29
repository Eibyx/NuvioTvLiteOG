import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'anilist'))
from verify_coexistence import compare, launcher_labels, parse_manifest


def tree(package, authorities=(), permissions=(), extra=''):
    lines = ['N: android=http://schemas.android.com/apk/res/android', '  E: manifest (line=2)',
             f'    A: package="{package}" (Raw: "{package}")']
    for p in permissions:
        lines += ['    E: permission (line=4)', f'      A: android:name(0x01010003)="{p}" (Raw: "{p}")']
    lines += ['    E: application (line=6)']
    for a in authorities:
        lines += ['      E: provider (line=8)',
                  '        A: android:name(0x01010003)="androidx.core.content.FileProvider" (Raw: "x")',
                  f'        A: android:authorities(0x01010018)="{a}" (Raw: "{a}")']
    return '\n'.join(lines) + extra


ORIGINAL = parse_manifest(tree('com.nuvio.tv.lite', ['com.nuvio.tv.lite.fileprovider', 'com.nuvio.tv.lite.androidx-startup'],
                               ['com.nuvio.tv.lite.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION']))


class CoexistenceTests(unittest.TestCase):
    def test_parser_reads_package_authorities_and_permissions(self):
        self.assertEqual('com.nuvio.tv.lite', ORIGINAL['package'])
        self.assertEqual({'com.nuvio.tv.lite.fileprovider', 'com.nuvio.tv.lite.androidx-startup'}, ORIGINAL['authorities'])
        self.assertEqual({'com.nuvio.tv.lite.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'}, ORIGINAL['permissions'])

    def test_standalone_with_derived_names_coexists(self):
        standalone = parse_manifest(tree('com.eibyx.nuviotv.anilist', ['com.eibyx.nuviotv.anilist.fileprovider'],
                                         ['com.eibyx.nuviotv.anilist.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION']))
        self.assertEqual([], compare(standalone, ORIGINAL)['install_conflicts'])

    def test_reused_authority_or_permission_is_an_install_conflict(self):
        with self.assertRaisesRegex(AssertionError, 'authority'):
            compare(parse_manifest(tree('com.eibyx.nuviotv.anilist', ['com.nuvio.tv.lite.fileprovider'])), ORIGINAL)
        with self.assertRaisesRegex(AssertionError, 'permission'):
            compare(parse_manifest(tree('com.eibyx.nuviotv.anilist', [],
                                        ['com.nuvio.tv.lite.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'])), ORIGINAL)

    def test_labels_cover_every_locale_and_launcher_but_not_feature_groups(self):
        badging = ("application-label:'NuvioTV AniList'\napplication-label-hr:'Nuvio'\n"
                   "launchable-activity: name='a.B'  label='NuvioTV AniList' icon='x'\n"
                   "leanback-launchable-activity: name='a.B'  label='NuvioTV AniList' icon='x' banner='y'\n"
                   "feature-group: label=''\n")
        self.assertEqual({'NuvioTV AniList', 'Nuvio'}, launcher_labels(badging))

    def test_same_package_or_updater_is_rejected(self):
        with self.assertRaisesRegex(AssertionError, 'package'):
            compare(parse_manifest(tree('com.nuvio.tv.lite')), ORIGINAL)
        updater = tree('com.eibyx.nuviotv.anilist', extra='\n      E: service (line=9)\n        A: android:name(0x01010003)="com.nuvio.tv.updater.UpdateJobService" (Raw: "x")')
        with self.assertRaisesRegex(AssertionError, 'updater'):
            compare(parse_manifest(updater), ORIGINAL)


if __name__ == '__main__':
    unittest.main()
