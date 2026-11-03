# NuvioTvOG — experimental ARMv7 (firma debug)

NuvioTV-Lite original con la integración experimental de AniList portada desde NuvioMobileKai, como **aplicación propia**: **NuvioTvOG**, package `com.eibyx.nuviotvog`. Se instala junto a NuvioTV-Lite original sin desinstalarla ni modificarla.

**Pre-release experimental. No es una versión estable.** Las pre-releases anteriores se conservan sin cambios.

## Novedades

- **Tamaño de la interfaz (zoom):** Ajustes → Apariencia → **Tamaño de la interfaz**: 60 %, 70 %, 80 %, 90 %, **100 % (original)**, 110 % o 120 %.
  - Escala menús, pósters, textos, iconos y controles del reproductor en la misma proporción; se aplica al momento.
  - Menos de 100 %: cabe más contenido en pantalla. Más de 100 %: se lee mejor de lejos.
  - El vídeo y los subtítulos mantienen su propio tamaño. Se guarda solo en este televisor.
  - Valores bajos muestran más pósters a la vez y usan algo más de memoria en imágenes; en equipos de ~1 GB de RAM conviene no bajar de 80-90 %. A 110-120 % alguna pantalla puede necesitar más desplazamiento.
- **Nombre y package únicos:** NuvioTvOG / `com.eibyx.nuviotvog` en todos los idiomas. Sustituye a «NuvioTV AniList» (`com.eibyx.nuviotv.anilist`). Al ser otro package, se instala como app nueva: si tenías NuvioTV AniList puedes desinstalarla.
- **Búfer de MPV ajustable:** Ajustes → Reproducción → Vídeo → **Búfer de MPV** (visible cuando el motor es MPV).
  - **Precarga hacia delante:** 32, 64, 128, 256, 384, 512, 768 o 1024 MB.
  - **Búfer hacia atrás:** desactivado, 16, 32, 64, 128 o 256 MB.
  - Cada opción indica los minutos aproximados que cubre a 1080p (5 Mbit/s). Marca con un aviso los valores altos para la RAM del televisor (delante + atrás por encima de una cuarta parte de la memoria).
  - Por defecto se mantiene el comportamiento anterior: 64 MB hacia delante y 64 MB hacia atrás.
  - Se guarda solo en este dispositivo (no se sincroniza con otros) y se aplica al momento, también durante la reproducción.
  - Solo cambia los valores `demuxer-max-bytes` y `demuxer-max-back-bytes` que la app pasa a MPV. Las bibliotecas MPV y ExoPlayer son las originales.

## Funciones

- Cuarta cuenta en Ajustes → Seguimiento → Cuentas → AniList: conectar, sincronizar ahora y desconectar.
- Importa los episodios vistos de AniList (prefijo por entrada) y selecciona el siguiente pendiente.
- Al completar un episodio, actualiza AniList con cola persistente y reintentos. Nunca reduce el progreso ni reescribe COMPLETED. Respeta temporadas, partes, especiales y numeración absoluta (algoritmos de NuvioMobileKai).
- OAuth con el cliente público de NuvioMobileKai (Client ID `53329`, redirect `nuvio://auth/anilist`), capturado dentro del navegador integrado de la app.
- Inicio de sesión QR de Nuvio y configuración pública oficial de Supabase sin cambios.
- Bibliotecas nativas precompiladas (MPV, FFmpeg, ExoPlayer, libass) idénticas byte a byte a la release oficial v2.3.1-lite ARMv7.
- Sin actualizador integrado: el de Lite instalaría el APK oficial `com.nuvio.tv.lite` sobre la app original. Las nuevas versiones de NuvioTvOG se instalan a mano.

## Instalación

> Esta versión tiene otra firma debug que la anterior de NuvioTvOG: si ya tenías NuvioTvOG instalada, desinstálala antes (se pierde su sesión y sus ajustes). NuvioTV-Lite original no se toca.

1. Instala el APK en el TCL. Android lo trata como una aplicación distinta: no pide desinstalar NuvioTV-Lite y no toca sus datos.
2. Abre **NuvioTvOG** e inicia sesión con el QR de Nuvio (sus datos son independientes).
3. Ajustes → Seguimiento → Cuentas → AniList → **Conectar cuenta**.
4. Opcional: Ajustes → Reproducción → Vídeo → Búfer de MPV, por ejemplo 256 MB delante y 32 MB atrás.

## Limitaciones conocidas

- **No probado en un televisor físico:** instalación junto a la original, reproducción, búfer de MPV, QR de Nuvio y login de AniList con mando. La coexistencia se comprobó en el APK (package, authorities, permisos y sharedUserId frente al APK oficial).
- Un búfer grande usa memoria del televisor. Si la app se cierra durante la reproducción, baja la precarga o el búfer hacia atrás.
- Mismo icono que la app original; solo cambia el nombre.
- Ambas apps declaran los enlaces `nuvio://` y `stremio://`; Android puede preguntar cuál usar al abrir uno desde otra app.
- Firma Android Debug generada en el runner: para instalar una versión futura puede ser necesario desinstalar antes NuvioTvOG (nunca la original).
- Las claves de Trakt, Simkl, MDBList, TMDB y otros servicios del `local.properties` privado de upstream no están en el repositorio. Esas integraciones pueden aparecer sin configurar hasta añadir el secreto `LOCAL_PROPERTIES_BASE64`.
- AniList guarda progreso acumulado: solo se importa un prefijo de episodios por entrada.
- Requiere Android System WebView. La verificación Cloudflare de AniList puede necesitar navegar con el mando hasta la casilla.
