# NuvioTV AniList — app independiente (experimental ARMv7, firma debug)

NuvioTV-Lite original con la integración experimental de AniList portada desde NuvioMobileKai, empaquetada como **segunda aplicación**: se instala junto a NuvioTV-Lite original sin desinstalarla ni modificarla.

**Pre-release experimental. No es una versión estable.** La pre-release anterior `v0.1.0-anilist` (package `com.nuvio.tv.lite`) se conserva sin cambios.

## Qué cambia respecto a `v0.1.0-anilist`

- Package `com.eibyx.nuviotv.anilist` (antes `com.nuvio.tv.lite`). Los paquetes Kotlin y el namespace interno `com.nuvio.tv` no cambian.
- Nombre visible: **NuvioTV AniList** en todos los idiomas.
- Las authorities de FileProvider y androidx-startup y el permiso interno `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` derivan del nuevo package, así que no chocan con la app original.
- Sin actualizador integrado: el de Lite descarga e instala el APK oficial `com.nuvio.tv.lite`, lo que actualizaría la app original en lugar de esta. Se usa el stub sin efecto que upstream ya emplea en su variante Play Store. Las nuevas versiones se instalan a mano desde las releases de este repositorio.
- Mismo código de aplicación, mismos reproductores, mismas bibliotecas nativas y misma integración AniList que `v0.1.0-anilist`.

## Funciones

- Cuarta cuenta en Ajustes → Seguimiento → Cuentas → AniList: conectar, sincronizar ahora y desconectar.
- Importa los episodios vistos de AniList (prefijo por entrada) y selecciona el siguiente pendiente.
- Al completar un episodio en la app, actualiza AniList con cola persistente y reintentos. Nunca reduce el progreso ni reescribe COMPLETED. Respeta temporadas, partes, especiales y numeración absoluta (algoritmos de NuvioMobileKai).
- OAuth con el cliente público de NuvioMobileKai (Client ID `53329`, redirect `nuvio://auth/anilist`), capturado dentro del navegador integrado de la app.
- Inicio de sesión QR de Nuvio y configuración pública oficial de Supabase sin cambios.
- MPV y ExoPlayer originales; bibliotecas nativas precompiladas idénticas byte a byte a la release oficial v2.3.1-lite ARMv7.

## Instalación junto a la app original

1. Descarga el APK de esta release en el TCL, o envíalo por USB o con una app tipo «Send files to TV».
2. Instálalo. Android lo trata como una aplicación distinta: **no** pide desinstalar NuvioTV-Lite y no toca sus datos.
3. Abre **NuvioTV AniList**. Su configuración, perfiles e inicio de sesión son independientes: inicia sesión con el QR de Nuvio como en una instalación nueva.
4. Ajustes → Seguimiento → Cuentas → AniList → **Conectar cuenta**, inicia sesión en AniList con el mando y pulsa **Authorize**.

## Limitaciones conocidas

- **No probado en un televisor físico.** La coexistencia se comprobó en el APK: package, authorities, permisos personalizados y sharedUserId frente al APK oficial. No se ha instalado ninguna de las dos apps en un TCL ni se ha probado la reproducción, el QR de Nuvio ni el inicio de sesión de AniList con mando.
- Ambas apps tienen el mismo icono. Solo cambia el nombre visible.
- Ambas declaran los enlaces `nuvio://` y `stremio://`. Al abrir uno de esos enlaces desde otra app, Android puede preguntar cuál usar. El OAuth de AniList no depende de esto (se captura dentro de la app) y el QR de Nuvio usa `https://nuvio.tv`.
- Firma Android Debug generada en el runner: cada compilación puede tener otra firma. Para instalar una versión futura de esta app puede ser necesario desinstalar antes **esta** app (no la original).
- Sin actualizador integrado (ver arriba).
- Las claves de Trakt, Simkl, MDBList, TMDB y otros servicios del `local.properties` privado de upstream no están en el repositorio. Esas integraciones pueden aparecer sin configurar hasta añadir el secreto `LOCAL_PROPERTIES_BASE64`.
- AniList guarda progreso acumulado: solo se importa un prefijo de episodios por entrada, no episodios sueltos ni segundos parciales.
- Requiere Android System WebView. La verificación Cloudflare de AniList puede necesitar navegar con el mando hasta la casilla.
