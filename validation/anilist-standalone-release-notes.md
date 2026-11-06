# NuvioTvOG (ARMv7)

NuvioTV-Lite original con la integración experimental de AniList portada desde NuvioMobileKai, como **aplicación propia**: **NuvioTvOG**, package `com.eibyx.nuviotvog`. Se instala junto a NuvioTV-Lite original sin desinstalarla ni modificarla.

**Firma fija:** desde esta versión, NuvioTvOG se firma siempre con la misma clave. Las próximas versiones se instalan **encima**, conservando la sesión y los ajustes. Las pre-releases anteriores se conservan sin cambios.

## Novedades en 0.6.3

- **TeraBox sin pausas largas:** si una conexión con TeraBox deja de enviar datos, se corta a los 8 s y ese bloque se pide de nuevo por otra conexión (hasta 5 intentos, renovando el enlace si caducó). Antes el reproductor podía esperar hasta un minuto con el búfer vacío.
- **Anime de la nube con AIOmetadata:** los títulos de AniList (romaji, inglés, nativo) ahora se encuentran también desde fichas con IDs de TVDB (AIOmetadata), IMDb y TMDB. Antes la consulta a ARM para esos IDs fallaba y solo coincidían los archivos nombrados como el título del catálogo.
- **El índice de la nube no se borra solo:** si una sincronización en segundo plano encuentra de golpe muchos menos vídeos (sesión caducada o fallo del proveedor), se mantiene el índice anterior y se reintenta en una hora; «Sincronizar ahora» acepta la lista nueva.
- **Nombre corto de la fuente:** en los resultados, tus cuentas aparecen como **Drive** o **TeraBox**, sin el correo. Con varias cuentas del mismo tipo: «Drive 1», «Drive 2»…
- **Firma fija:** se instala encima de la 0.6.2 sin desinstalar.

## Novedades en 0.6.2

- **Reproducción de TeraBox y Drive más rápida y sin cortes:** TeraBox limita la velocidad de cada conexión (también con Premium). Ahora el vídeo se descarga en bloques de 2 MB por varias conexiones a la vez (3 en TVs con poca RAM, unos 8 MB de memoria; 4 en el resto) y se entrega al reproductor en orden.
- **Arranque y saltos más cortos:** el destino final del enlace de TeraBox se reutiliza en lugar de seguir la redirección en cada lectura; si caduca, se renueva una sola vez aunque haya varias lecturas a la vez.
- **Probar velocidad:** Ajustes → Descubrimiento de contenido → Almacenamiento en la nube → tu cuenta → **Probar velocidad**. Lee parte de tu vídeo más grande igual que el reproductor y muestra los Mbit/s y el tiempo hasta el primer dato (un anime BD 1080p necesita unos 5–8 Mbit/s).
- **Firma fija:** se instala encima de la 0.6.1 sin desinstalar.

## Novedades en 0.6.1

- **Inicio de sesión de Google Drive mucho más fácil:** en la página del QR basta el client ID y el secreto de un cliente de tipo **App de escritorio**. Pulsa «Iniciar sesión con Google», acepta y pega la dirección `127.0.0.1` a la que te lleva Google: la TV obtiene el refresh token sola. Ya no hace falta OAuth Playground ni rclone (siguen disponibles «Ya tengo un refresh token» y rclone.conf). La página trae los enlaces directos a Google Cloud Console.
- **Errores claros al conectar Drive:** falta el refresh token, Drive API desactivada, client ID o secreto incorrectos, cliente que no es de escritorio, código caducado o app en modo prueba.
- **Más anime detectado en la nube (como en NuvioMobileKai):** siempre se consultan los títulos de AniList (romaji, inglés, nativo y sinónimos), aunque el catálogo solo tenga el título en inglés o en español; se descarga la ficha si no estaba en memoria, y si AniList no responde se reintenta en la siguiente búsqueda en vez de quedarse sin esos títulos. Ejemplo: «Fantasy Bishoujo Juniku Ojisan to».
- **Firma fija:** se instala encima de la 0.6.0 sin desinstalar.

## Novedades en 0.6.0

- **Almacenamiento en la nube (Google Drive y TeraBox)**, portado de NuvioMobileKai: Ajustes → Descubrimiento de contenido → **Almacenamiento en la nube**.
  - Tus propios vídeos aparecen como fuente al buscar enlaces, junto a los addons (cada cuenta cuenta dentro del límite de consultas simultáneas).
  - Las credenciales se introducen **desde el móvil**: la TV muestra un QR y un código de 6 cifras; la página solo existe mientras el QR está en pantalla y nunca devuelve credenciales.
  - **Google Drive:** tu propio cliente OAuth (client ID + secret) y refresh token con alcance `drive.readonly`, o un `rclone.conf` sin cifrar. Google no permite el inicio de sesión con código de TV para ese alcance.
  - **TeraBox (experimental):** cookie de sesión o valor `ndus`; solo dominios de TeraBox.
  - Al conectar indexa toda la unidad; luego puedes elegir carpetas. Drive se actualiza por cambios; se resincroniza en segundo plano si el índice tiene más de 6 h. Nunca descarga, mueve ni cambia archivos.
  - Reconoce `S02E12`, ` - 12` (fansub), `2x12`, `Temporada/Episodio` y números con contexto de carpeta; títulos de TMDB y AniList; OVA/especiales solo en la temporada 0; NCOP/NCED/PV/menús nunca. Asociación manual desde el móvil.
  - Subtítulos `.srt/.ass/.vtt` junto al vídeo y, si ninguno está en tu idioma, el subtítulo propio del archivo.
  - Reproduce con ExoPlayer y MPV a través de un servidor local en la propia TV (saltos, todas las pistas, ASS con fuentes incrustadas, progreso y siguiente episodio).
  - Credenciales cifradas con Android Keystore, por perfil y solo en esta TV. Desconectar revoca el token de Google y borra credenciales, índice y asociaciones; cerrar sesión en Nuvio lo borra todo.
  - En TVs de poca RAM indexa como máximo 8 000 vídeos (15 000 con 2–2,5 GB; 30 000 con más). Sin cuentas no se ejecuta nada.
- **Firma fija:** se instala encima de la 0.5.0 sin desinstalar.

## Novedades en 0.5.0

- Base NuvioTV-Lite 2.4.0 (NuvioTV 1.1.0-beta.9), mejoras de NuvioTV upstream (Marcar como visto, modo nocturno de audio, HDR/HLG, MDBList anime) y teclas numéricas de Nuvio Arc para saltar al 0-90 %.

## Funciones de versiones anteriores

- **Actualizaciones sin desinstalar** (desde 0.4.1): firma fija de NuvioTvOG (certificado SHA-256 `09778027af6ff49b4e3393f1e786d8b65040abe25c148c3ed0724c7abfc76ceb`).
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
- Bibliotecas nativas precompiladas (MPV, FFmpeg, ExoPlayer, libass) idénticas byte a byte a la release oficial v2.4.0-lite ARMv7.
- Sin actualizador integrado: el de Lite instalaría el APK oficial `com.nuvio.tv.lite` sobre la app original. Las nuevas versiones de NuvioTvOG se instalan a mano.

## Instalación

> Si tienes **NuvioTvOG 0.4.1, 0.5.0, 0.6.0, 0.6.1 o 0.6.2**, instala esta encima: conserva la sesión y los ajustes. Si tienes una anterior (v0.3.0 o v0.4.0, con firma debug), desinstálala una vez antes: `pm uninstall com.eibyx.nuviotvog`. NuvioTV-Lite original no se toca.

1. Instala el APK en el TCL. Android lo trata como una aplicación distinta: no pide desinstalar NuvioTV-Lite y no toca sus datos.
2. Abre **NuvioTvOG** e inicia sesión con el QR de Nuvio (sus datos son independientes).
3. Ajustes → Seguimiento → Cuentas → AniList → **Conectar cuenta**.
4. Opcional: Ajustes → Reproducción → Vídeo → Búfer de MPV, por ejemplo 256 MB delante y 32 MB atrás.

## Limitaciones conocidas

- **Almacenamiento en la nube no probado en un televisor físico** (Drive, TeraBox, página del QR). TeraBox puede dejar de funcionar si cambia su web. La página del móvil va por HTTP en tu red local.
- **No probado en un televisor físico:** instalación junto a la original, reproducción, búfer de MPV, QR de Nuvio y login de AniList con mando. La coexistencia se comprobó en el APK (package, authorities, permisos y sharedUserId frente al APK oficial).
- Un búfer grande usa memoria del televisor. Si la app se cierra durante la reproducción, baja la precarga o el búfer hacia atrás.
- Mismo icono que la app original; solo cambia el nombre.
- Ambas apps declaran los enlaces `nuvio://` y `stremio://`; Android puede preguntar cuál usar al abrir uno desde otra app.
- La clave de firma solo existe en los secretos del repositorio (y en la copia del propietario). Si se perdiera, la versión siguiente volvería a requerir desinstalar.
- Experimental: no probado en un televisor físico.
- Las claves de Trakt, Simkl, MDBList, TMDB y otros servicios del `local.properties` privado de upstream no están en el repositorio. Esas integraciones pueden aparecer sin configurar hasta añadir el secreto `LOCAL_PROPERTIES_BASE64`.
- AniList guarda progreso acumulado: solo se importa un prefijo de episodios por entrada.
- Requiere Android System WebView. La verificación Cloudflare de AniList puede necesitar navegar con el mando hasta la casilla.
