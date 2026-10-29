#!/usr/bin/env python3
"""Check that a standalone APK can be installed beside the original Lite APK.

Android refuses a second install when it reuses the package name, a content-provider
authority or a custom permission already declared by another app, or when it requests a
shared user ID. This reads both binary manifests through aapt; it does not install anything.
"""
import re
import subprocess

STANDALONE_PACKAGE = 'com.eibyx.nuviotv.anilist'
STANDALONE_LABEL = 'NuvioTV AniList'


def parse_manifest(xmltree):
    """Package, declared permissions, provider authorities and sharedUserId from `aapt dump xmltree`."""
    result = {'package': None, 'permissions': set(), 'authorities': set(), 'shared_user_id': None,
              'services': set(), 'receivers': set(), 'uses_permissions': set()}
    element = None
    for line in xmltree.splitlines():
        tag = re.match(r'\s*E: ([\w-]+)', line)
        if tag:
            element = tag.group(1)
            continue
        attr = re.match(r'\s*A: (?:android:)?([\w]+)(?:\(0x[0-9a-f]+\))?="([^"]*)"', line)
        if not attr:
            continue
        name, value = attr.groups()
        if element == 'manifest' and name == 'package':
            result['package'] = value
        elif element == 'manifest' and name == 'sharedUserId':
            result['shared_user_id'] = value
        elif element == 'permission' and name == 'name':
            result['permissions'].add(value)
        elif element == 'uses-permission' and name == 'name':
            result['uses_permissions'].add(value)
        elif element == 'provider' and name == 'authorities':
            result['authorities'].update(a for a in value.split(';') if a)
        elif element == 'service' and name == 'name':
            result['services'].add(value)
        elif element == 'receiver' and name == 'name':
            result['receivers'].add(value)
    return result


def compare(standalone, original):
    assert standalone['package'] == STANDALONE_PACKAGE, 'Unexpected standalone package: ' + str(standalone['package'])
    assert original['package'] != standalone['package'], 'Same package as the original app'
    assert not standalone['shared_user_id'], 'Standalone APK must not request a sharedUserId'
    clashes = standalone['authorities'] & original['authorities']
    assert not clashes, 'Provider authority already used by the original: ' + ', '.join(sorted(clashes))
    clashes = standalone['permissions'] & original['permissions']
    assert not clashes, 'Custom permission already declared by the original: ' + ', '.join(sorted(clashes))
    assert all(a.startswith(STANDALONE_PACKAGE + '.') for a in standalone['authorities']), \
        'Authority not derived from the standalone package'
    updater = {n for n in standalone['services'] | standalone['receivers'] if '.updater.' in n}
    assert not updater, 'Standalone APK must not ship the in-app updater: ' + ', '.join(sorted(updater))
    assert 'android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION' not in standalone['uses_permissions'], \
        'Standalone APK must not request silent package updates'
    return {'standalone_package': standalone['package'], 'original_package': original['package'],
            'standalone_authorities': sorted(standalone['authorities']),
            'original_authorities': sorted(original['authorities']),
            'standalone_permissions': sorted(standalone['permissions']),
            'original_permissions': sorted(original['permissions']),
            'shared_user_id': None, 'updater_components': [], 'install_conflicts': []}


def check(aapt, standalone_apk, original_apk):
    read = lambda apk: parse_manifest(subprocess.check_output([aapt, 'dump', 'xmltree', str(apk), 'AndroidManifest.xml'], text=True))
    badging = subprocess.check_output([aapt, 'dump', 'badging', str(standalone_apk)], text=True)
    labels = set(re.findall(r"label='([^']*)'", badging))
    assert labels == {STANDALONE_LABEL}, 'Unexpected launcher labels: ' + ', '.join(sorted(labels))
    report = compare(read(standalone_apk), read(original_apk))
    report['launcher_label'] = STANDALONE_LABEL
    return report
