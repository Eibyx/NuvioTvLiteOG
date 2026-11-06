# Almacenamiento en la nube (Google Drive y TeraBox) — TV

Port de `features/cloudstorage` de NuvioMobileKai (rama `kai`, 3.1.1), adaptado a Android TV.

## Dónde está

Ajustes → Descubrimiento de contenido → **Almacenamiento en la nube**. Todo se maneja con el mando:
lista de cuentas, y por cuenta: usar al buscar fuentes, mostrar primero, sincronizar ahora,
carpetas (añadir explorando la cuenta, quitar), asociaciones manuales y desconectar.

Las credenciales se introducen **desde el móvil**: la TV muestra un QR con una página de la red
local y un código de 6 cifras. La página solo existe mientras el QR está en pantalla, se cierra al
añadir una cuenta, tras 5 códigos erróneos o a los 10 minutos, y nunca devuelve credenciales.

## Google Drive

Requiere **tu propio cliente OAuth** de tipo «App de escritorio» (Google Cloud Console), con Google
Drive API activada y la app publicada (en modo prueba el acceso caduca a los 7 días).

Inicio de sesión desde el móvil: client ID + secreto → «Iniciar sesión con Google» → aceptar →
Google lleva el navegador a `http://127.0.0.1:53682/?state=…&code=…` (no carga en el móvil, es lo
esperado) → pegar esa dirección. La TV comprueba `state` y canjea el código con PKCE; el verificador
no sale de la TV y la página nunca recibe el refresh token. Alternativas: pegar un refresh token o
un `rclone.conf` sin cifrar.

No hay inicio de sesión con código en la TV: el flujo OAuth para dispositivos de entrada limitada
de Google solo admite `drive.file` y `drive.appdata` (y perfil/YouTube), no `drive.readonly`, que
además es un alcance restringido. Con `drive.file` la app no vería los vídeos ya subidos.

## TeraBox (experimental)

Cookie de sesión (o solo el valor `ndus`) y dominio opcional; solo se aceptan dominios de TeraBox.
Las peticiones son las de rclone-extra (`backend/terabox`). Usa siempre una sesión nueva.

## Índice y memoria

- Índice en disco: `noBackupFilesDir/cloud_index/<perfil>/<cuenta>.json`. Se lee al buscar fuentes
  y se suelta después; solo se guardan en memoria los últimos archivos coincidentes (48).
- Límites (Kai: 30 000 archivos / 5 000 carpetas): TV estándar 30 000 / 5 000; TV de 2–2,5 GB
  15 000 / 2 500; TV de poca RAM (< 1,6 GB) 8 000 / 1 500. Profundidad máxima 12.
- Drive usa el feed de cambios (incremental); TeraBox recorre las carpetas. Se resincroniza en
  segundo plano si el índice tiene más de 6 h (al abrir el perfil o buscar fuentes).
- Cada cuenta cuenta como una consulta dentro del límite de consultas simultáneas (3 / 6 / 8).
- Sin cuentas no se ejecuta nada en segundo plano y el servidor local no arranca.

## Reproducción

Servidor local en `127.0.0.1` con token de 256 bits en la ruta, `Host` de loopback, solo GET/HEAD,
máximo 8 conexiones; reenvía `Range`, renueva el enlace en 401/403/404/410 o respuesta HTML y
cancela la petición al proveedor cuando el reproductor se desconecta. `Cookie`/`Authorization`
solo al mismo host o subdominios, y el resolvedor DNS rechaza direcciones privadas o locales.

Subtítulos externos (`.srt/.ass/.ssa/.vtt` < 20 MB) del mismo nombre base, o mismo título y
episodio, en la misma carpeta. Si ningún subtítulo coincide con el idioma preferido, se usa el del
archivo (`findFileDefaultSubtitleTrackIndex`): mismo idioma base (`spa` para `es-419`), pista
marcada por defecto, la elegida por el motor o la única completa; nunca si el audio ya está en el
idioma preferido o los subtítulos están desactivados.

## Coincidencias

Parser de nombres: `S02E12`, ` - 12` (fansub), `2x12`, `Temporada 2 Episodio 12`, número suelto con
contexto de carpeta. Títulos de Nuvio, título original de TMDB y AniList (romaji, inglés, nativo,
sinónimos, con la temporada TVDB de ARM). Números absolutos solo con la lista de episodios.

**Nuevo respecto a Kai (portar a Kai):**

- `OVA`, `OAD`, `SP`, `Special(s)`, `Extra(s)`, `Recap` o `Movie` en el archivo o en las dos
  carpetas más cercanas → el archivo solo coincide con la temporada 0 de TMDB. Un título que
  empieza por la palabra (`Special A`) sigue siendo título.
- Extras de BD (`NCOP`, `NCED`, `PV`, `CM`, `Menu`, `Preview`, `Trailer`, `Teaser`, `Creditless`)
  nunca coinciden.
- Para películas se compara también el título con la palabra (`Gintama The Movie`).
- Prefiltro barato por palabras del alias en la ruta antes del parser (índices grandes, TV lentas).
