# Índice de evidencias - Semana 08

Inicio de la reproducción: 2026-10-05T16:37:34-03:00

Procedencia: colección regenerada mediante la secuencia manual de Semana 08.
Este índice se crea al inicio; los TXT se generan en los pasos posteriores.
34_verificacion_final_semana_08.txt comprueba el cierre de la colección completa.
El respaldo externo es solo recuperación; no forma parte de esta entrega.

| Evidencia | Descripción |
| --- | --- |
| `01_maven_test.txt` | Suite Maven completa: 92 tests y BUILD SUCCESS |
| `02_docker_compose_build.txt` | Construcción de las ocho imágenes propias |
| `03_docker_compose_ps.txt` | Estado de servicios Compose e inicializador Batch |
| `04_healthchecks.txt` | Healthchecks HTTP 200 y UP |
| `05_oauth2_token_web.txt` | Access Token OAuth2 Web: metadatos seguros |
| `06_oauth2_token_mobile.txt` | Access Token OAuth2 Mobile: metadatos seguros |
| `07_oauth2_token_atm.txt` | Access Token OAuth2 ATM: metadatos seguros |
| `08_oauth2_jwt_claims.txt` | Claims JWT públicos, issuer y vigencia |
| `09_web_sin_token_401.txt` | Web sin token: HTTP 401 |
| `10_web_scope_correcto.txt` | Web con scope correcto: HTTP 200 |
| `11_web_scope_incorrecto_403.txt` | Web con scope ATM: HTTP 403 |
| `12_web_token_invalido_401.txt` | Web con token inválido: HTTP 401 |
| `13_mobile_autorizado.txt` | Mobile autorizado: HTTP 200 |
| `14_atm_saldo_autorizado.txt` | Cuenta real y saldo ATM autorizado |
| `15_core_sin_token_401.txt` | Core sin token: HTTP 401 |
| `16_core_token_valido.txt` | Core con token válido: HTTP 200 |
| `17_atm_retiro_pending.txt` | Retiro ATM aceptado: HTTP 202 y PENDING |
| `18_atm_retiro_resultado_final.txt` | Estado terminal del retiro y comprobación de saldo |
| `19_kafka_topics_particiones.txt` | Topics Kafka y sus tres particiones |
| `20_kafka_consumer_group.txt` | Consumer group, miembros, offsets y lag |
| `21_kafka_flujo_retiro.txt` | Trazabilidad Kafka de la transacción actual |
| `22_kafka_idempotencia_test.txt` | Test de idempotencia del evento ya procesado |
| `23_resilience4j_tests.txt` | Nueve tests Resilience4j: estados, fallback y recuperación |
| `24_batch_regresion.txt` | Tres tests de integración y Jobs Spring Batch |
| `25_docker_images.txt` | Ocho imágenes propias del proyecto |
| `26_eureka_servicios.txt` | Cuatro servicios registrados UP en Eureka |
| `27_config_server.txt` | Configuración centralizada sin secretos |
| `28_resilience4j_runtime.txt` | Runtime Resilience4j seguro: captura compartida con 31 |
| `29_verificacion_documental_alcance.txt` | Inspección documental, migración e inventario |
| `30_docker_reset_arranque.txt` | Reset único, arranque y espera del stack |
| `31_resilience4j_recuperacion_sin_dependencias.txt` | Recuperación de Core sin dependencias ni reejecución Batch |
| `32_resilience4j_configuracion_productiva.txt` | Parámetros productivos Resilience4j desde Config Server |
| `33_resilience4j_runtime_final.txt` | Runtime definitivo: seis 503 por canal y recuperación 200 |
| `34_verificacion_final_semana_08.txt` | Verificación final de las 35 evidencias y criterios funcionales |
