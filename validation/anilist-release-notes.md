# NuvioTV Lite + AniList — experimental ARMv7

Base Lite original: `9bd27940ac8669b836807e6143feb0950fe0049d`.
Port AniList desde Mobile: `62b4121af5e09977d87b445faa1cb91b268c6c12`.

- Cuarta cuenta en Ajustes → Seguimiento → Cuentas → AniList.
- Importa el prefijo de episodios vistos y selecciona el siguiente pendiente sin añadir reproducciones ficticias al historial Nuvio.
- Envía episodios completados con cola persistente, reintentos, correspondencias por identificadores/temporadas/partes y protección de progreso y COMPLETED.
- OAuth oficial en el teléfono, con entrega cifrada al TV y credenciales independientes de la sesión Nuvio.
- Solo APK Lite armeabi-v7a; package `com.nuvio.tv.lite`, versionCode 10024.
- MPV, ExoPlayer, bibliotecas nativas, QR/Supabase Nuvio, addons, temas y restantes integraciones conservados.

Conexión: abre AniList en Ajustes → Seguimiento → Cuentas, pulsa Conectar cuenta, escanea el QR con el teléfono en la misma LAN, aprueba en AniList y entrega la autorización cifrada. Después puedes usar Sincronizar ahora o Desconectar cuenta.

Limitaciones: experimental, sin prueba física de reproducción en TCL. AniList proporciona un progreso acumulado; solo se puede importar un prefijo, no episodios vistos arbitrarios ni segundos parciales. Las correspondencias ambiguas, episodios 0 y numeraciones absolutas no verificadas no se envían. Requiere IPv4 privada compartida con el teléfono. El actualizador permanece upstream. No se afirma compatibilidad de actualización sin comparar el certificado realmente instalado.
