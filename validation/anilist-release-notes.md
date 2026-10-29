# NuvioTV Lite + AniList — experimental ARMv7 (firma debug)

**Pre-release experimental. No es una versión estable.**

Base Lite original: `9bd27940ac8669b836807e6143feb0950fe0049d` (hackerslash/NuvioTV-Lite `dev`, v2.3.1-lite).
Port AniList desde NuvioMobileKai: `62b4121af5e09977d87b445faa1cb91b268c6c12`.

## Funciones

- Cuarta cuenta en Ajustes → Seguimiento → Cuentas → AniList, con icono y descripción.
- Conectar / Sincronizar ahora / Desconectar cuenta.
- Importa el prefijo de episodios vistos de cada entrada AniList y los muestra como vistos en la ficha; selecciona el siguiente pendiente sin añadir reproducciones ficticias al historial Nuvio.
- Al completar un episodio en Nuvio, actualiza AniList con cola persistente y reintentos (30 s → 6 h, Retry-After), correspondencias por IDs AIOmetadata (`mal:`/`kitsu:`), AniList/MAL/Kitsu/AniDB, temporadas, partes, especiales y numeración absoluta (reglas de Mobile).
- Nunca reduce el progreso ni reescribe COMPLETED.
- OAuth con el mismo cliente público de NuvioMobileKai (Client ID `53329`, redirect `nuvio://auth/anilist`): el TV abre la página oficial de AniList en su propio navegador integrado e intercepta la redirección. El token se guarda cifrado con Android Keystore, independiente de la sesión Nuvio.
- Solo APK Lite `armeabi-v7a`; package `com.nuvio.tv.lite`, versionCode 10024.
- MPV, ExoPlayer, bibliotecas nativas (idénticas byte a byte a la release oficial), QR/Supabase Nuvio, addons, temas, Trakt, Simkl y MDBList sin cambios.

## Conectar AniList desde el TV

1. Ajustes → Seguimiento → Cuentas → AniList → **Conectar cuenta**.
2. Se abre la página oficial de AniList en el TV. Inicia sesión con el mando (teclado en pantalla) y completa la verificación de Cloudflare si aparece.
3. Pulsa **Authorize/Autorizar**. El TV recibe la autorización, valida la cuenta y lanza la primera sincronización.
4. **Atrás** cancela en cualquier momento.

## Limitaciones conocidas

- No se ha probado físicamente en un TCL ni en ningún TV: ni reproducción, ni login QR Nuvio, ni el inicio de sesión AniList con mando.
- El inicio de sesión requiere Android System WebView en el TV. La verificación Cloudflare Turnstile de AniList puede necesitar navegar con el mando hasta la casilla.
- Firma Android Debug generada en el runner: no puede actualizar una instalación con firma oficial (hay que desinstalar antes, lo que borra datos locales) y la firma puede cambiar entre compilaciones.
- AniList guarda progreso acumulado: solo se importa un prefijo de episodios por entrada, no episodios sueltos ni segundos parciales.
- Correspondencias ambiguas, episodios 0 y numeraciones absolutas sin lista verificada se omiten sin escribir.
- El actualizador integrado sigue consultando las releases de upstream.
