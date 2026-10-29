# APK EXPERIMENTAL Lite ARMv7 — firma debug de Android

Esta entrega sirve para diagnóstico en TCL. No es una release estable ni usa una firma de producción.

- Único ABI: armeabi-v7a. Package: com.nuvio.tv.lite. VersionCode: 10024.
- VersionName termina en `-lite-diagnostic-debug-signed`.
- Conserva las optimizaciones de la variante Lite release y usa la opción original `CI_USE_DEBUG_SIGNING=true` para firmar con Android Debug.
- El certificado debug se genera en el runner. No puede actualizar una instalación oficial con otro certificado; tampoco se garantiza continuidad de firma entre ejecuciones. No se realiza ninguna desinstalación automática.
- Supabase se configura mediante `local.properties`, como upstream, recuperando exclusivamente el anon público y las URLs del APK oficial ARMv7 v2.3.1-lite, cuyo SHA-256 y certificado se verifican antes de usarlo. Se consulta el endpoint público del primario y se comprueba que las constantes quedan dentro del APK generado.
- No se cambian AuthManager, SupabaseModule, ServerConfigurationStore ni el flujo QR. La respuesta del endpoint público no equivale a aprobar y probar físicamente una sesión QR.
- Los reproductores y cada biblioteca nativa ARMv7 se comparan con el APK oficial. No se modifican MPV, ExoPlayer, decodificadores ni libass.

## AniList no puede conectarse en este APK

Este APK de diagnóstico compila `ANILIST_CLIENT_ID` y `ANILIST_REDIRECT_URI` vacíos. No se ha creado ni inventado un Client ID, no se reutiliza el cliente móvil y no se declara validada la autenticación AniList. El diálogo de AniList indica que falta configuración.

Para preparar una compilación futura con conexión:

1. Iniciar sesión en AniList y abrir https://anilist.co/settings/developer; crear un cliente para esta aplicación Android TV.
2. Hospedar `docs/anilist-oauth/callback.html` sobre una URL HTTPS propia y accesible desde el teléfono. Es una página estática; no necesita un backend ni guarda tokens.
3. Registrar esa URL exacta como Redirect URL del cliente AniList. Debe coincidir con `ANILIST_REDIRECT_URI`, incluyendo ruta y cualquier barra final.
4. Poner el Client ID público asignado por AniList en `ANILIST_CLIENT_ID` y la URL registrada en `ANILIST_REDIRECT_URI`, usando el `local.properties` original. No se necesita un Client Secret para el flujo implícito implementado, ni se pide la contraseña AniList dentro de Nuvio.
5. Incorporar esas propiedades a `LOCAL_PROPERTIES_BASE64` del repositorio para el workflow de entrega con OAuth (este workflow de diagnóstico las deja vacías deliberadamente).
6. En esa futura compilación: Ajustes → Seguimiento → Cuentas → AniList → Conectar cuenta. Escanear el QR con el teléfono en la misma LAN IPv4, aprobar en AniList y entregar la autorización cifrada al TV. Solo tras comprobar ese recorrido real se puede declarar verificada la conexión.

## Prueba en TCL

Extraer el ZIP del artefacto y comprobar `SHA256SUMS.txt`. El informe `apk-verification.json` incluye firma, ABI, versiones y hashes nativos. Instalar únicamente el archivo `.apk`. Si Android rechaza la actualización por certificados distintos, no es compatible como actualización de esa instalación.

Probar reproducción, audio, subtítulos y el login QR original. No se afirma que ya se hayan probado físicamente en el TCL. Una firma debug no convierte el APK en un build debuggable: se mantiene la variante release original para conservar su comportamiento y optimizaciones.

Este workflow solo publica un artefacto de Actions; no crea releases ni tags.
