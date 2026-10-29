# Matriz de verificación solicitada

Los conteos y resultados reales se registran en los reportes de pruebas de Actions y el informe final; esta matriz describe el alcance, no afirma que las pruebas físicas se hayan realizado.

| Requisito | Evidencia automatizada |
|---|---|
| 1. Importación AniList | Fixtures portadas `AniListWatchedImportTest`; snapshot y prefijos TV en `AniListTvProjectionTest`. |
| 2. Marcas vistas | Importación por partes/temporadas/IDs de vídeo y filtro de episodios realmente presentes. |
| 3. Siguiente episodio | TV: 12 vistos → 13 pendiente; temporada terminada → siguiente temporada; REPEATING → siguiente de repetición. |
| 4. Actualización remota | `AniListTransportTest`: petición GraphQL de lectura seguida de mutación real interceptada 12 → 13. |
| 5. No disminuir | Reglas Mobile y transporte sin mutación si el remoto ya tiene más progreso. |
| 6. COMPLETED | Fixtures Mobile y lectura de remoto COMPLETED sin segunda escritura. |
| 7. Temporadas independientes | AoT, Re:Zero, Spy x Family, Mushoku; cola por temporada/parte y rechazo de temporada no verificada. |
| 8. Especiales | Fixtures Mobile season 0; importación absoluta excluye especiales/episodio 0; adaptador exige entrada especial verificada. |
| 9. Absoluta | One Piece Mobile; recorrido TV entre temporadas excluyendo especiales; adaptador rechaza offset desconocido. |
| 10. AIOmetadata | Fixture de una temporada cuyos IDs de vídeo cambian de MAL 100 a 200; no mezcla prefijos. Código del addon inspeccionado. |
| 11. Persistencia | Reinicio de cola/enlaces Mobile, serialización de snapshot y reinicio de autorización por perfil. Keystore real pendiente de dispositivo. |
| 12. Reintentos | Cola Mobile: backoff, Retry-After, recuperación, máximo pendiente y solicitud de trabajo Android. |
| 13. Desconexión | AuthStore invalida generaciones, aísla perfiles y borra autorización; proyección vacía no cambia los vídeos locales. |
| 14. Otros servicios | Suite original de Trakt/Simkl/MDBList; comprobación de que las importaciones no llaman sus escritores ni al historial. |
| 15. QR Nuvio | Comparación byte a byte contra el commit original de AuthManager/SupabaseModule/ServerConfigurationStore y demás fuentes originales fuera de la lista revisada. Prueba real de aprobación QR pendiente de configuración y dispositivo. |

Adicionalmente se prueban el sobre cifrado del teléfono, estado incorrecto, manipulación, caducidad y replay; la página JavaScript real se ejecuta en Node con WebCrypto y un DOM de prueba. Esto no verifica restricciones LAN de un navegador móvil real ni el registro OAuth de AniList.

La prueba instrumentada original de Seguimiento se actualiza para cuatro filas y foco Trakt → Simkl → MDBList → AniList. No se omite ninguna prueba existente para conseguir un resultado verde. Las pruebas instrumentadas requieren dispositivo/emulador, y se declaran pendientes si no se ejecutan.
