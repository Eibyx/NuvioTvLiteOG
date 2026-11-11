# Mis carpetas de subtítulos (con sus fuentes) — NuvioTvOG

Port de «Mis carpetas de subtítulos» de **Eibyx/NuvioArcKai**, rama `kai/subtitle-folders` (PR #2, pre-release
`1.1.0-nt.stable-kai.2`), adaptado a NuvioTvOG (NuvioTvLiteOG).

Rama `kai/subtitle-folders`, creada desde `claude/anilist-nuviotv-armv7-cuxyj3` (`dd9e50aa5`, la de la release
estable `v0.6.4-nuviotvog`). LiteOG no tiene rama `main`: esa rama es la línea estable desde la que se
publican todas las releases de NuvioTvOG, y es el destino del PR. El PR no se fusiona hasta probarlo en el TCL.

## Fase 0: lo que hay en LiteOG (evidencia)

| Punto | Resultado |
|---|---|
| Licencia | `LICENSE` de LiteOG: GNU GPL v3, igual que NuvioArcKai. Se copió el código de Arc y se adaptó; cada archivo portado lleva una cabecera `Ported from Eibyx/NuvioArcKai (branch kai/subtitle-folders, GPL-3.0)`. |
| ExoPlayer | Media3 **1.8.0** propio (`app/libs/lib-*-release.aar`; `MediaLibraryInfo.VERSION = "1.8.0"` en `lib-common`). |
| libass en ExoPlayer | Sí: `app/libs/nuvio-libass-release.aar` (`build.gradle.kts`: `implementation(files("libs/nuvio-libass-release.aar"))`), ajuste «Usar libass para subtítulos ASS/SSA», activado por defecto (`PlayerSettingsDataStore`: `useLibass ... ?: true`). API (`javap`): `AssHandler.addFont(String, byte[])`, `createTrack(Format)`, `onMediaItemTransition`. Igual que la de Arc salvo `replaceTrack`, que solo tiene Arc (no se usa). |
| MPV | `io.github.abdallahmehiz:mpv-android-lib:0.1.12` (`build.gradle.kts:629`); `libmpv.so` (armeabi-v7a) contiene `mpv v0.41.0-174-g76a5eba99` y `ass_set_fonts_dir`. Mismo que Arc. |
| Subtítulo externo en ExoPlayer | `selectAddonSubtitle` → para ASS con libass, `attachAddonSubtitleViaMediaReload` → `toSubtitleConfiguration` (id `nuvio-addon-sub:<id>:<hash>`, `PlayerRuntimeControllerTrackSelection.kt:392`) → `PlayerMediaSourceFactory.createMediaSource` → `DefaultMediaSourceFactory`. |
| Subtítulo externo en MPV | `selectAddonSubtitle` (rama mpv) → `NuvioMpvSurfaceView.addAndSelectExternalSubtitle` → `sub-add <ruta> cached`. |
| Nube | Sí: cuentas de TeraBox y Google Drive por perfil (`core/cloudstorage`, credenciales cifradas con Keystore, `resolve` a URL firmada servida por el servidor local). Solo faltaba `listFiles` (archivos sin filtrar, fuentes incluidas): se añadió igual que en Arc. |
| Metadatos del episodio | `PlayerRuntimeController`: `contentId`, `contentName`, `contentType`, `currentVideoId`, `currentSeason`, `currentEpisode`. Alias, temporada y número absoluto: `CloudStreams.request()` (títulos de Nuvio, TMDB y AniList vía ARM, `absoluteEpisode`), el mismo que usa la nube. |
| Ajustes | Ajustes → Reproducción → Subtítulos (`PlaybackSubtitleSettings.kt`, componentes `SettingsActionRow`/`SettingsToggleRow`). Idiomas: en, es y es-419 (`strings_kai_subtitles.xml`). LiteOG **no** tiene buscador de ajustes (`SettingsSearchIndex` no existe), así que no hay filas que indexar. |
| Perfiles | Sí: la configuración se guarda por perfil (`ProfileDataStoreFactory`). Sin tokens ni cookies en DataStore: las cuentas siguen en el almacén cifrado de la nube. |
| Compilación y release | `applicationId` `com.eibyx.nuviotvog` (`-PanilistStandalone=true`), solo `armeabi-v7a` (`-PanilistArmv7Only=true`), workflow `.github/workflows/anilist-diagnostic-armv7.yml` (build + verificación + publicación); firma fija de NuvioTvOG con los secretos `NUVIOTVOG_KEYSTORE_BASE64` / `NUVIOTVOG_KEYSTORE_PASSWORD` (se re-firma fuera de CI y la publicación exige el certificado fijado). Última release: `v0.6.4-nuviotvog`, versionCode 10031. |

Ningún bloqueo: hay forma de compilar y firmar, y los dos motores pueden usar fuentes.

## Orígenes disponibles

Almacenamiento interno / USB, «Enviar desde el celular», TeraBox y Google Drive (estas dos con las cuentas
que ya tenga el perfil en Almacenamiento en la nube).

## Puntos «verifica» (con evidencia)

- **`format.id`** (ExoPlayer): en `lib-exoplayer-release.aar`, `DefaultMediaSourceFactory.createMediaSource` hace
  `Format.Builder.setId(subtitleConfiguration.id)` (offset 506–509 del bytecode) y llama a
  `SubtitleParser$Factory.create(format)` en `lambda$createMediaSource$0`, en el hilo de carga. El id es el de
  `toSubtitleConfiguration` (`nuvio-addon-sub:<id>:<hash>`). El registro (`LibraryFontRegistry`) también empareja
  por sufijo, por si el grupo de pistas lo prefija.
- **Orden de eventos**: `AssHandler.onMediaItemTransition` → `resetMediaState` borra las fuentes cuando
  `setMediaSource` cambia el medio (hilo de la app, antes de `prepare()`); el parser del archivo externo se crea
  después, al cargar. Las fuentes de la carpeta se añaden en `CompatAssSubtitleParserFactory.create`
  (`PlayerLibassCompat.kt`) **antes** de `delegate.create`, así que quedan pendientes en `AssFontStore` y
  `AssHandler.createTrack` las vuelca (`fontStore.flush`) justo al crear la pista. Un `Player.Listener`
  registrado después de `assHandler.init(player)` deja un aviso en el log si una transición llega menos de 2 s
  después de crear un parser con fuentes de carpeta.
- **Fuentes tardías**: con pistas ya creadas, `AssHandler.addFont` (javap: `hasTracks()` → `AssFontStore.add(…,
  hasTracks, …)`) las pasa directamente a libass, que las carga al renderizar. NO VERIFICADO en el televisor.
- **mpv**: LiteOG nunca había fijado `sub-fonts-dir`. Para no cambiar ningún otro subtítulo, justo antes del
  `sub-add` de un subtítulo de carpeta con fuentes se cambia `sub-fonts-dir` a
  `noBackupFilesDir/mpv-sub-fonts-active/` (solo con las fuentes de ese subtítulo, enlaces duros o copias,
  escritas con archivo temporal + renombrado) y se devuelve a su valor original antes de cualquier otro
  `sub-add` (`NuvioMpvSurfaceView.applyLibraryFontsDir`). El ASS va como ruta local sin reescribir;
  `sub-ass-override=no` y `sub-font` no cambian.
- **`file://` con fuentes de datos solo HTTP**: en LiteOG los subtítulos del medio se leen con la fuente del vídeo,
  que en streams de la nube (servidor local) o con conexiones paralelas es solo HTTP. Los `file://` marcados como
  subtítulo se abren con `FileDataSource` (`PlayerSubtitleDataSource.kt`); `subtitleRequestTarget` no cambia (lo
  exige `SubtitleRoutingTest`).

## Adaptaciones respecto a Arc

| Arc | LiteOG |
|---|---|
| Selección automática diferida (`autoSelectAddonSubtitleDeferringReload`) | No existe en LiteOG: se usa `selectAddonSubtitle`, la misma que la selección automática de LiteOG, una sola vez por episodio (`autoSelectedId`) para que la recarga no la repita. Un subtítulo de carpeta elegido así no lo sustituye después uno de addon o embebido. |
| `playerNotice` (aviso de fuentes que faltan) | LiteOG no tiene esa franja: un Toast, como los mensajes de servidor. |
| `PlayerPanelRow`, panel v2 | Fila propia (`LibraryPanelRow`) con el estilo de las tarjetas del menú de LiteOG; las filas de «Mis carpetas» van debajo de la lista de idiomas. |
| Fuentes incluidas de Arc para mpv | LiteOG no trae fuentes para mpv: el directorio activo solo tiene las de la carpeta. |
| Logos de TeraBox/Drive | Icono genérico de nube (no se añaden logos de marca). |
| `SettingsSearchIndex` | No existe en LiteOG. |
| Prueba `real bundled TTF` (Montserrat de Arc) | Usa Inter, que sí trae LiteOG. |

## Permisos añadidos al manifest

- `READ_EXTERNAL_STORAGE` con `maxSdkVersion="32"`: leer archivos en Android 7–10 cuando el televisor no tiene
  selector de documentos; solo se pide al elegir esa opción. `requestLegacyExternalStorage="true"` para Android 10.
- `MANAGE_EXTERNAL_STORAGE`: en Android 11+ los `.ass`/`.ttf` no son multimedia y el permiso anterior no los
  alcanza. Solo se pide si el usuario elige «Acceso a todos los archivos» y la pantalla existe (si no, se muestra
  el comando adb). NuvioTvOG no se distribuye en Google Play.
- `<queries>` para `OPEN_DOCUMENT_TREE` y `MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`, para que `resolveActivity`
  responda bien en Android 11+.

## Pruebas

| | Pruebas | Fallos | Omitidas |
|---|---|---|---|
| Base (`dd9e50aa5`) | 2633 | 1 (`ParallelRangeChunkLeaseTest`, prueba de tiempos inestable conocida; no relacionada) | 3 |
| Con esta función | 2663 | 0 | 3 |

Nuevas (30): `AssFontNamesTest`, `SfntNamesTest`, `FontSelectionTest`, `LibraryMatcherTest`,
`LibraryCacheAndAccessTest` (caché LRU, redacción de logs, método de acceso local), `PhoneUploadPathsTest`
(sin `..`, solo extensiones permitidas) y `LibraryFontRegistryTest` (id correcto y sin fugas entre episodios).

## Lista de comprobación para el TCL de 1 GB (NO VERIFICADO)

1. ExoPlayer + libass: episodio con su `.ass` y dos fuentes en la carpeta; se ven las fuentes del ASS.
2. MPV: lo mismo; cambiar a otro subtítulo (de addon) y volver; las fuentes siguen bien y el otro no cambia.
3. Un vídeo de TeraBox/Drive (servidor local) con subtítulo de carpeta: el subtítulo carga.
4. TeraBox y Drive como origen de carpetas: «Español · TeraBox · N fuentes», «Español · Drive».
5. USB: selector de documentos (o permiso / acceso a todos los archivos según la TV); desconectar el USB y abrir un
   episodio → «USB no conectado» en el menú, sin errores.
6. «Enviar desde el celular»: QR, elegir la carpeta, «Archivos recibidos: N»; el episodio la usa.
7. Fuente que falta: quitar una fuente usada → aviso «Faltan fuentes: …» una sola vez.
8. libass desactivado: el subtítulo carga; el menú ofrece activar libass.
9. Cambio de episodio (siguiente episodio y elegir otro): subtítulo y fuentes del nuevo episodio.
10. Memoria: episodio con 30 MB de fuentes, 20 min sin cierres.
11. «Buscar en mis carpetas…» y «Vincular carpeta…»: elegir a mano, salir y volver; se recuerda.
12. Sin carpetas configuradas: el reproductor arranca como antes (sin espera de 2 s).
