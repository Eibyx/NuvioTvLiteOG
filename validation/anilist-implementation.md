# AniList para NuvioTV Lite original

Estado: implementación experimental, pendiente de completar las verificaciones documentadas en `test-results.json` y los reportes de Actions. No constituye una validación física en TCL ni una autorización OAuth comprobada.

## Bases inspeccionadas antes de editar

- Destino: `Eibyx/NuvioTvLiteOG`, rama inicial `dev`, commit `9bd27940ac8669b836807e6143feb0950fe0049d`.
- Original: `hackerslash/NuvioTV-Lite`, rama `dev`, mismo commit. Las 1.665 entradas Git eran idénticas, incluidos los gitlinks. No había modificaciones previas que conservar o revertir.
- Mobile: `Eibyx/NuvioMobileKai`, rama `kai`, commit `62b4121af5e09977d87b445faa1cb91b268c6c12`.
- Estructura AIOmetadata: `cedya77/aiometadata`, commit `26bbc7cd25d936dc1935dfa1c73e36a93fd8b796`; `addon/lib/getMeta.js` genera IDs por episodio como `mal:<entrada>:<episodio>` y `kitsu:<entrada>:<episodio>`, que pueden cambiar de entrada dentro de una temporada.
- No se ha consultado ni copiado `Eibyx/NuvioTvKaiLite`.

`upstream-preservation.json` y `scripts/anilist/verify_preservation.py` permiten reproducir la comparación. Las referencias se han utilizado solo para lectura y compilación de la base; todas las modificaciones versionadas pertenecen al repositorio destino.

## Componentes Mobile reutilizados

Ruta fuente común: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/anilist/`.

| Mobile | Port TV | Adaptación |
|---|---|---|
| `AniListModels.kt` | mismo nombre, paquete `com.nuvio.tv.data.anilist` | Sin proyecciones de reproducciones móviles; conserva modelos GraphQL, estados y snapshot de listas. |
| `AniListApiClient.kt` | mismo nombre | Mismas consultas/mutaciones, límites, 429, Retry-After, sondeo de entrada antes de escribir y control de generación. Lecturas públicas sin token; errores GraphQL parciales impiden dar una mutación por confirmada. |
| `AniListAuthStore.kt` | mismo nombre | Bloqueo JVM en lugar de atomicfu KMP; conserva generaciones, perfiles, caducidad y revocación. |
| `AniListAuthPersistence.android.kt` | `AndroidAniListAuthPersistence.kt` | AES-GCM con Android Keystore, AAD por perfil, espacio de nombres independiente. No hay dependencia nueva. |
| `AniListIdResolver.kt` | mismo nombre | Interfaces de IDs/metadatos de Lite y su `SimklIdResolver` original. Cachés limitadas. No cambia Simkl. |
| `AniListSeasonMapping.kt` | mismo nombre | Planificación de temporadas, partes, cours y numeración absoluta; regla de actualización monotónica y protección COMPLETED/REPEATING. |
| `AniListWatchedImport.kt` | mismo nombre | Inversión del plan de partes y prefijo importado; conserva pruebas Mobile. El fallback por fechas no autoriza envíos remotos. |
| `AniListSendQueue.kt` + `AniListQueueStorage.kt` | mismos nombres | Cola por perfil/temporada/parte, máximos confirmados, backoff 30 s a 6 h, Retry-After, serialización y reanudación. Escritura atómica previa a publicar el estado, errores de disco visibles y cierre al cambiar de cuenta e importación de enlaces en una única escritura por lote. |
| `AniListTrackingWrites.kt` | `AniListProgressSender.kt` + adaptador `AniListRepository` | Conserva lectura fresca, `aniListProgressUpdate`, omisión de duplicados y COMPLETED; usa el evento real `markAsCompleted` de Lite. |
| `AniListWatchedImporter.kt` / `AniListTracker.kt` | `AniListTvProjection.kt` + `AniListRepository` | Capa de marcas importadas en la ficha; no inserta reproducciones ni eventos en el historial. No se portan la UI móvil ni sus repositorios globales. Se publica la proyección persistida antes de consultar la red, para conservar marcas tras reiniciar sin conexión. |
| `AniListSyncWorker.kt` | `AniListRetryService.kt` en `AniListRetryScheduler.kt` | JobScheduler Android, trabajo puntual con conexión; sin WorkManager adicional ni servicio permanente. Las lecturas masivas y los lotes de envíos pendientes se aplazan cuando la señal original de reproducción está activa; un único evento de completado puede enviarse de inmediato; la caché de listas de episodios AniList conserva solo cuatro títulos. |

`AniListListLinker`, las fuentes de biblioteca, ratings, posters y pantallas Mobile se investigaron pero no se trasladaron: no son necesarios para el seguimiento solicitado y alterarían más funciones originales. La lista completa se importa en un snapshot; los episodios se proyectan al disponer de los metadatos del título. La aplicación no añade un catálogo AniList ni cambia la fuente de biblioteca o de progreso elegida en Lite.

El núcleo de la cola mantiene el aviso y la licencia Apache-2.0 de los componentes de Aniyomi/Mihon reconocidos por Mobile. El proyecto conserva su licencia original. El icono proviene de `AniList/ApiV2-GraphQL-Docs`, `docs/public/anilist.svg`; se usa como marca identificativa de su integración.

## Modificaciones justificadas de archivos originales

| Archivo | Motivo exclusivo de integración |
|---|---|
| `TrackingSettingsOverviewTest.kt` | Ajusta la prueba de las cuatro cuentas y comprueba foco con mando hasta AniList; mantiene las aserciones existentes. |
| `app/build.gradle.kts` | Dos constantes públicas OAuth; opción `-PanilistArmv7Only=true` que genera solo armeabi-v7a sin universal; versionCode 10024 y versionName 2.3.2-anilist.1-lite para distinguir el experimento y superar el código original 10023. No se cambian dependencias. |
| `app/src/main/AndroidManifest.xml` | Declara exclusivamente el JobService privado de reintentos AniList. |
| `WatchProgressRepositoryImpl.kt` | Notifica a la cola AniList el evento original de episodio completado, incluso cuando `broadcastTrackingHistory=false`. No altera las escrituras existentes. |
| `MetaDetailsViewModel.kt` | Une las marcas importadas a la presentación de episodios y selección siguiente; respeta el punto de rewatch AniList sin guardar reproducciones ficticias. |
| `TrackingSettingsScreen.kt` | Añade una cuarta fila y su diálogo con el mismo componente/navegación de ajustes. No cambia los otros tres proveedores ni sus fuentes. |

Los archivos nuevos quedan aislados en `data/anilist`, dos componentes de ajustes, recursos AniList, pruebas, documentación, herramientas de verificación y un workflow adicional. Los workflows originales permanecen idénticos.

No se modifican MPV, ExoPlayer, Media3, sus bibliotecas nativas, aceleración, buffers, cachés, subtítulos/libass, temas, inicio, fuentes, colores, posters, addons, endpoints de Supabase, `AuthManager`, `SupabaseModule`, `ServerConfigurationStore`, el QR Nuvio, su aprobación ni intercambio de sesiones.

## Identidad, importación y seguridad de progreso

1. Se prioriza el ID de anime del vídeo AIOmetadata, seguido de IDs AniList/MAL/Kitsu/AniDB y correspondencias ARM verificadas. IMDb usa la tabla de temporadas de ARM; TMDB/TVDB solo pueden continuar si los metadatos instalados aportan IMDb o una identidad de anime verificable.
2. El plan reutiliza las reglas de Mobile para partes y temporadas. La capa TV rechaza el fallback de temporadas sin tabla verificada, orden ambiguo de partes, episodios 0 y numeración absoluta de temporadas posteriores cuando falta su lista de episodios. No hay búsquedas automáticas por título.
3. Los especiales no se contabilizan como episodios normales. Un vídeo especial con ID propio debe corresponder a una entrada SPECIAL/OVA; la temporada 0 por IMDb necesita una correspondencia explícita.
4. AniList devuelve progreso acumulado, no un historial por episodio. Se infiere únicamente el prefijo de cada entrada/parte. COMPLETED y REPEATING conservan las reglas Mobile: total conocido visto; REPEATING conserva además el siguiente episodio de la repetición.
5. Las marcas importadas nunca llaman `markAsCompleted`, Trakt, Simkl, MDBList ni Supabase. Se persiste la lista y los enlaces en el almacenamiento privado; las coordenadas se reconstruyen a partir de metadatos al abrir una ficha tras reiniciar.
6. El envío nace del evento original de completado. Se persiste antes de la operación remota, se consulta la entrada fresca y solo se escribe si la regla Mobile devuelve un aumento. No se guarda un COMPLETED de nuevo, ni se reduce progreso, ni se genera REPEATING.
7. Los casos sin correspondencia se muestran como fallos de enlace y no escriben remoto. No se incorpora una UI de enlace manual o un fallback por títulos/fechas para enviar.

## OAuth TV independiente

Configurar un cliente OAuth AniList autorizado para esta aplicación. No se reutiliza el Client ID móvil 53329 ni se incluye un Client Secret.

- Hospedar `docs/anilist-oauth/callback.html` sobre HTTPS, sin scripts de terceros, analíticas ni backend. Registrar su URL exacta como redirect del cliente AniList.
- Proporcionar `ANILIST_CLIENT_ID` y `ANILIST_REDIRECT_URI` mediante `local.properties` (el mecanismo de compilación ya existente).
- Ajustes → Seguimiento → Cuentas → AniList → Conectar cuenta.
- Escanear el QR en un teléfono en la misma red local IPv4, aprobar en la web oficial de AniList y pulsar la entrega de autorización cifrada. Algunos navegadores piden permiso de red local/navegación HTTP.
- La página elimina el fragmento OAuth del historial y cifra el token con AES-256-GCM; envuelve la clave con RSA-OAEP SHA-256 usando una clave efímera del TV. Solo el sobre cifrado llega por la LAN. El nonce/estado está autenticado, caduca en 10 minutos y no admite replay.
- El TV valida la identidad mediante `Viewer` antes de completar la conexión. Guarda únicamente sus credenciales en Android Keystore/AES-GCM; no toca la sesión Nuvio.
- «Sincronizar ahora» lee la lista y reintenta pendientes. «Desconectar cuenta» borra credenciales, enlaces, snapshot y pendientes de AniList.

La página estática no se ha publicado ni registrado automáticamente: no se ha comprobado permiso para configurar GitHub Pages ni una cuenta administrativa de AniList. La validez del cliente/redirect y el recorrido teléfono–TV requieren una prueba real antes de habilitar publicación.

## Supabase y firma

Upstream lee en release `NUVIO_SUPABASE_URL`, `NUVIO_SUPABASE_ANON_KEY` y opcionalmente `NUVIO_SUPABASE_FALLBACK_URL` desde `local.properties`. Debug admite la precedencia original de `local.dev.properties`. Las direcciones de login por defecto siguen siendo `https://nuvio.tv/link` y `https://nuvio.tv/tv-login`.

Se recuperaron únicamente los valores públicos anon y URLs de la release oficial ARMv7 v2.3.1-lite. Se verificó la respuesta 200 de `/auth/v1/settings` en `https://api.nuvio.tv`; el fallback `https://api-two.nuvioapp.space` se conserva tal como aparece en la configuración de servidor del APK original. El endpoint fallback original devolvió HTTP 503 en este entorno; no se reemplazó ni se alteró. Están en `local.properties`, ignorado por Git; no se inventan valores ni se introduce una clave administrativa. La evidencia pública, sin incluir el token anon, está en `public-supabase-verification.json`. La configuración del APK nuevo aún no se puede verificar sin compilarlo y firmarlo. La verificación de publicación exige claves públicas, consulta `/auth/v1/settings` y comprueba que las constantes esperadas están dentro del APK. Rechaza `service_role`, tokens de usuario y patrones de secretos privados.

El almacén de firma upstream `nuviotv.jks` no está versionado ni se encuentra en este entorno. No se crea una firma de sustitución para publicar. La capacidad de actualizar una instalación existente exige comparar su certificado, package ID y versionCode; no está verificada sin evidencia de esa instalación.

## Workflow y entrega

Workflow adicional: `.github/workflows/anilist-armv7.yml`. Verifica conservación, pruebas Python/Kotlin, restaura los secretos admitidos por upstream, verifica configuración, compila Lite ARMv7, comprueba ZIP/ELF/ABI/firma/identidad/versiones/configuración pública y prepara un pre-release experimental con solo el APK. SHA-256 y certificado se incluyen en sus notas. El job de pruebas tiene `contents: read`; solo el job de release tiene `contents: write`.

Secretos de repositorio necesarios (mismos nombres que el workflow upstream):

- `LOCAL_PROPERTIES_BASE64`, con configuración pública upstream y las dos propiedades OAuth.
- `NUVIO_RELEASE_KEYSTORE_BASE64`.
- `NUVIO_RELEASE_KEY_ALIAS`, `NUVIO_RELEASE_KEY_PASSWORD`, `NUVIO_RELEASE_STORE_PASSWORD`.

Variables:

- `ANILIST_TV_OAUTH_VERIFIED=true` únicamente después de registrar y probar el cliente/retorno en un teléfono y TV.
- `NUVIO_EXPECTED_SIGNER_SHA256`, opcional para comparar con el certificado realmente instalado; sin ella las notas declaran que no se verificó la actualización.

No es posible leer ni gestionar secretos/variables con el permiso efectivo actual: la API devuelve 403. La lectura de ramas, PR, runs, artefactos y releases sí fue comprobada. Se comprobaron push de la rama, creación/edición de PR, ejecución de Actions y descarga de sus artefactos mediante el conector GitHub. El check AniList pasa en Actions. Issues está deshabilitado y habilitarlo devolvió 403: el check original de plantilla del PR queda pendiente de una issue vinculada, sin modificarlo para ocultar el bloqueo.

## Límites de validación

- Pruebas unitarias/modelos/transporte no equivalen a reproducción física, navegación con un mando TCL ni validación OAuth/Supabase en producción.
- Ningún token real de usuario ni credencial administrativa se introduce en pruebas, fuentes o APK de distribución.
- La integración no representa progreso parcial en segundos de AniList: AniList permite progreso de episodios completos. Lite conserva sus propios tiempos de reproducción.
- La selección general de biblioteca/progreso y el actualizador siguen exactamente los originales; el actualizador original consulta las releases de hackerslash, no las de este experimento.
- Los títulos ambiguos se omiten sin escribir progreso; necesitan ampliar correspondencias verificadas en el futuro.
- Solo IPv4 privada con teléfono y TV en la misma LAN; redes aisladas de invitados, IPv6-only y restricciones de navegador pendientes de validación física.


## Resultado de esta ejecución

- Código de aplicación verificado: `450b83a83c83f9c2c255df096f0770149c4d5ddf` (misma aplicación que el commit de implementación `9769960564d170462585142bb0a37496e5158c40`; el segundo commit corrige solo la preparación del SDK en el workflow).
- Lite: 2.347 casos JUnit, 2.344 aprobados, 0 fallidos y 3 omitidos heredados. Las 69 pruebas AniList pasan.
- Base original: 2.278 casos, 2.275 aprobados y las mismas tres omisiones. No falta ningún caso original ni cambia ningún resultado.
- Python: 21 aprobados; WebCrypto: 3 aprobados. Las 59 pruebas instrumentadas compilan, pero no se ejecutaron por ausencia de dispositivo/emulador.
- Actions de verificación: https://github.com/Eibyx/NuvioTvLiteOG/actions/runs/40905567712 — aprobado; artefactos descargados mediante el conector y conteos JUnit comprobados.
- Intento de preparación/publicación: https://github.com/Eibyx/NuvioTvLiteOG/actions/runs/40907456462 — pruebas aprobadas y publicación detenida porque el runner no recibió `LOCAL_PROPERTIES_BASE64` ni los cuatro secretos de firma. No se generó un APK nuevo ni una release.
- PR en borrador: https://github.com/Eibyx/NuvioTvLiteOG/pull/1. La plantilla original exige una issue vinculada; Issues está deshabilitado y la API denegó habilitarlo (403). Ese check no se ha desactivado ni alterado.
- `local.properties` contiene únicamente la configuración pública Supabase recuperada del APK oficial; está ignorado por Git y no incluye OAuth AniList ni firma. Completarlo con el cliente/redirect autorizado y la configuración upstream restante, y proporcionarlo mediante `LOCAL_PROPERTIES_BASE64`.
- No hay enlace de release, descarga ni SHA-256 de un APK final que se pueda entregar. El nombre previsto por el workflow es `NuvioTV-Lite-2.3.2-anilist.1-armeabi-v7a.apk` y el tag previsto es `v2.3.2-anilist.1`.
- Compatibilidad de actualización no verificada: se conoce el certificado público del APK de referencia, pero no se dispone de la clave privada de firma ni del certificado realmente instalado en el TCL.

Los commits finales de documentación/evidencia no alteran la aplicación validada. Se evita repetir CI en ellos mediante `[skip ci]`; los enlaces anteriores conservan los resultados del código real.
