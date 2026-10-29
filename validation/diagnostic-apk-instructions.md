# APK EXPERIMENTAL Lite ARMv7 — firma debug de Android

(Antes llamado «diagnóstico»; ahora incluye AniList con el cliente de NuvioMobileKai.)

Esta entrega sirve para diagnóstico en TCL. No es una release estable ni usa una firma de producción.

- Único ABI: armeabi-v7a. Package: com.nuvio.tv.lite. VersionCode: 10024.
- VersionName termina en `-lite-diagnostic-debug-signed`.
- Conserva las optimizaciones de la variante Lite release y usa la opción original `CI_USE_DEBUG_SIGNING=true` para firmar con Android Debug.
- El certificado debug se genera en el runner. No puede actualizar una instalación oficial con otro certificado; tampoco se garantiza continuidad de firma entre ejecuciones. No se realiza ninguna desinstalación automática.
- Supabase se configura mediante `local.properties`, como upstream, recuperando exclusivamente el anon público y las URLs del APK oficial ARMv7 v2.3.1-lite, cuyo SHA-256 y certificado se verifican antes de usarlo. Se consulta el endpoint público del primario y se comprueba que las constantes quedan dentro del APK generado.
- No se cambian AuthManager, SupabaseModule, ServerConfigurationStore ni el flujo QR. La respuesta del endpoint público no equivale a aprobar y probar físicamente una sesión QR.
- Los reproductores y cada biblioteca nativa ARMv7 se comparan con el APK oficial. No se modifican MPV, ExoPlayer, decodificadores ni libass.

## AniList en este APK

Se reutiliza el cliente OAuth público de NuvioMobileKai: Client ID `53329`, redirect registrada `nuvio://auth/anilist`, flujo implícito (`response_type=token`), sin Client Secret. Como esa redirección es un esquema de app y no una URL HTTPS, el QR con página HTTPS para teléfono no es compatible con ese cliente; en su lugar el TV abre la página oficial de AniList en un WebView integrado e intercepta la redirección `nuvio://auth/anilist#access_token=…` dentro del propio proceso (nada sale a la red local).

1. Ajustes → Seguimiento → Cuentas → AniList → Conectar cuenta.
2. Iniciar sesión en AniList con el mando y pulsar Authorize.
3. El TV valida la cuenta con `Viewer`, guarda el token cifrado (Android Keystore) y sincroniza.

La conexión no se ha comprobado físicamente en un TV. Si en el futuro se registra un cliente propio con redirect HTTPS, `ANILIST_CLIENT_ID`/`ANILIST_REDIRECT_URI` en `local.properties` activan automáticamente el flujo QR para teléfono de `docs/anilist-oauth/callback.html`.

## Prueba en TCL

Extraer el ZIP del artefacto y comprobar `SHA256SUMS.txt`. El informe `apk-verification.json` incluye firma, ABI, versiones y hashes nativos. Instalar únicamente el archivo `.apk`. Si Android rechaza la actualización por certificados distintos, no es compatible como actualización de esa instalación.

Probar reproducción, audio, subtítulos y el login QR original. No se afirma que ya se hayan probado físicamente en el TCL. Una firma debug no convierte el APK en un build debuggable: se mantiene la variante release original para conservar su comportamiento y optimizaciones.

Este workflow solo publica un artefacto de Actions; no crea releases ni tags.
