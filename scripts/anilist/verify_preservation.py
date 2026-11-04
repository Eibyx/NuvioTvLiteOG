#!/usr/bin/env python3
"""Enforce the reviewed allowlist against the exact Lite original commit."""
import argparse, hashlib, json, subprocess
from pathlib import Path

UPSTREAM = 'acf8bfee83ce9512c405f6c0ecd6c412d2ca9178'
ALLOWED = {
    'app/src/androidTest/java/com/nuvio/tv/ui/screens/settings/TrackingSettingsOverviewTest.kt',
    'app/build.gradle.kts',
    'app/src/main/AndroidManifest.xml',
    'app/src/main/java/com/nuvio/tv/data/repository/WatchProgressRepositoryImpl.kt',
    'app/src/main/java/com/nuvio/tv/ui/screens/detail/MetaDetailsViewModel.kt',
    'app/src/main/java/com/nuvio/tv/ui/screens/settings/TrackingSettingsScreen.kt',
}
# Owner-requested MPV demuxer cache setting (Ajustes > Reproducción > Vídeo > Búfer de MPV).
# Kotlin only: the option values passed to libmpv; the native MPV/ExoPlayer libraries are untouched.
MPV_CACHE_SETTING = {
    'app/src/main/java/com/nuvio/tv/core/sync/ProfileSettingsSyncService.kt',
    'app/src/main/java/com/nuvio/tv/data/local/PlayerSettingsDataStore.kt',
    'app/src/main/java/com/nuvio/tv/ui/screens/player/NuvioMpvSurfaceView.kt',
    'app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerRuntimeController.kt',
    'app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt',
    'app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerRuntimeControllerMpv.kt',
    'app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerRuntimeControllerObservers.kt',
    'app/src/main/java/com/nuvio/tv/ui/screens/settings/PlaybackSettingsModel.kt',
    'app/src/main/java/com/nuvio/tv/ui/screens/settings/PlaybackSettingsViewModel.kt',
    'app/src/main/java/com/nuvio/tv/ui/screens/settings/PlaybackVideoSettings.kt',
}
ALLOWED |= MPV_CACHE_SETTING
# Owner-requested interface zoom (Ajustes > Apariencia > Tamaño de la interfaz, 60-120 %).
# ProfileSettingsSyncService (listed above) also keeps this value on the device.
UI_SCALE_SETTING = {
    'app/src/main/java/com/nuvio/tv/MainActivity.kt',
    'app/src/main/java/com/nuvio/tv/data/local/ThemeDataStore.kt',
    'app/src/main/java/com/nuvio/tv/ui/screens/settings/ThemeSettingsScreen.kt',
    'app/src/main/java/com/nuvio/tv/ui/screens/settings/ThemeSettingsViewModel.kt',
    'app/src/test/java/com/nuvio/tv/ui/screens/settings/ThemeSettingsViewModelTest.kt',
}
ALLOWED |= UI_SCALE_SETTING
# Self-contained NuvioTV upstream changes after 1.1.0-beta.9, cherry-picked with -x (night mode,
# mark as watched, HDR/HLG detection, MDBList anime remap, reused-link stream info).
UPSTREAM_NUVIOTV_PICKS = {
    'app/src/main/java/com/nuvio/tv/core/player/dvmkv/HevcAlternativeTransferSei.java',
    'app/src/main/java/com/nuvio/tv/core/player/dvmkv/MatroskaExtractor.java',
    'app/src/main/java/com/nuvio/tv/core/sync/ProfileSettingsSyncService.kt',
    'app/src/main/java/com/nuvio/tv/data/local/PlayerSettingsDataStore.kt',
    'app/src/main/java/com/nuvio/tv/data/local/StreamLinkCacheDataStore.kt',
    'app/src/main/java/com/nuvio/tv/data/mdblist/MdbListEpisodeMappingService.kt',
    'app/src/main/java/com/nuvio/tv/data/mdblist/MdbListProgressProjection.kt',
    'app/src/main/java/com/nuvio/tv/data/mdblist/MdbListTrackingProgressProvider.kt',
    'app/src/main/java/com/nuvio/tv/data/mdblist/MdbListTrackingProvider.kt',
    'app/src/main/java/com/nuvio/tv/data/remote/api/TmdbApi.kt',
    'app/src/main/java/com/nuvio/tv/data/repository/AddonEpisodeListService.kt',
    'app/src/main/java/com/nuvio/tv/data/repository/TraktEpisodeMapping.kt',
    'app/src/main/java/com/nuvio/tv/data/repository/TraktEpisodeMappingService.kt',
    'app/src/main/java/com/nuvio/tv/ui/components/ContinueWatchingSection.kt',
    'app/src/main/java/com/nuvio/tv/ui/components/GridContinueWatchingSection.kt',
    'app/src/main/java/com/nuvio/tv/ui/screens/home/ClassicHomeContent.kt',
    'app/src/main/java/com/nuvio/tv/ui/screens/home/GridHomeContent.kt',
    'app/src/main/java/com/nuvio/tv/ui/screens/home/HomeScreen.kt',
    'app/src/main/java/com/nuvio/tv/ui/screens/home/HomeUiState.kt',
    'app/src/main/java/com/nuvio/tv/ui/screens/home/HomeViewModel.kt',
    'app/src/main/java/com/nuvio/tv/ui/screens/home/ModernHomeContent.kt',
    'app/src/main/java/com/nuvio/tv/ui/screens/player/AudioSelectionOverlay.kt',
    'app/src/main/java/com/nuvio/tv/ui/screens/player/NightModeAudioProcessor.kt',
    'app/src/main/java/com/nuvio/tv/ui/screens/player/PeakLimiter.kt',
    'app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerRuntimeController.kt',
    'app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt',
    'app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerRuntimeControllerObservers.kt',
    'app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerRuntimeControllerPlaybackEvents.kt',
    'app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerRuntimeControllerStreams.kt',
    'app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerRuntimeControllerTracks.kt',
    'app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerScreen.kt',
    'app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerUiState.kt',
    'app/src/main/java/com/nuvio/tv/ui/screens/player/StreamSourcesSidePanel.kt',
    'app/src/main/java/com/nuvio/tv/ui/screens/settings/LayoutSettingsViewModel.kt',
    'app/src/main/java/com/nuvio/tv/ui/screens/settings/PlaybackAudioSettings.kt',
    'app/src/main/java/com/nuvio/tv/ui/screens/settings/PlaybackSettingsModel.kt',
    'app/src/main/java/com/nuvio/tv/ui/screens/settings/PlaybackSettingsViewModel.kt',
    'app/src/main/java/com/nuvio/tv/ui/screens/stream/StreamScreenViewModel.kt',
    'app/src/main/res/values/strings.xml',
    'app/src/test/java/com/nuvio/tv/core/player/dvmkv/HevcAlternativeTransferSeiTest.kt',
    'app/src/test/java/com/nuvio/tv/data/mdblist/MdbListEpisodeMappingServiceTest.kt',
    'app/src/test/java/com/nuvio/tv/data/mdblist/MdbListTrackingProgressProviderTest.kt',
    'app/src/test/java/com/nuvio/tv/ui/screens/home/ContinueWatchingMarkWatchedTest.kt',
    'app/src/test/java/com/nuvio/tv/ui/screens/player/CurrentStreamIndexTest.kt',
    'app/src/test/java/com/nuvio/tv/ui/screens/player/NightModeAudioProcessorTest.kt',
}
ALLOWED |= UPSTREAM_NUVIOTV_PICKS
# Light feature adapted from Nuvio Arc (ysosrs123/NuvioTV-Fork, GPL-3.0): number keys jump to 0-90 %.
ARC_NUMBER_KEY_JUMP = {
    'app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerScreen.kt',
}
ALLOWED |= ARC_NUMBER_KEY_JUMP
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
        'player_native_addons_themes_original':True,
        'mpv_cache_setting_files':sorted(set(changed) & MPV_CACHE_SETTING),
        'ui_scale_setting_files':sorted(set(changed) & UI_SCALE_SETTING)}

if __name__ == '__main__':
    p=argparse.ArgumentParser(); p.add_argument('--ref'); p.add_argument('--output'); args=p.parse_args()
    result=verify(args.ref); payload=json.dumps(result,indent=2)+'\n'
    if args.output: Path(args.output).write_text(payload)
    print(payload)
