# Semana 3

## 1. Objetivo del proyecto

Proyecto individual de Semana 3 de Desarrollo Backend III. Moderniza procesos batch legacy del Banco XYZ mediante Spring Batch y continúa el trabajo de las semanas anteriores. Procesa transacciones diarias, intereses mensuales y movimientos anuales, incorporando escalamiento multi-thread y tolerancia a fallos.

## 2. Tecnologías utilizadas

- Java 17
- Spring Boot
- Spring Batch
- Spring JDBC y `JdbcTemplate`
- PostgreSQL
- Maven
- Docker Compose
- SLF4J

## 3. Arquitectura de la solución

```text
CSV -> ItemReader -> ItemProcessor -> ItemWriter -> PostgreSQL
```

Cada `Job` organiza uno o más `Step`. Los `ItemReader` leen los CSV, los `ItemProcessor` validan y transforman los registros, y los `ItemWriter` persisten los datos mediante JDBC. El `JobRepository` usa PostgreSQL y registra Jobs, Steps y ejecuciones en las tablas `BATCH_*`.

## 4. Estructura del código

```text
proyecto/
├── .mvn/wrapper/
├── output/
│   └── estados_cuenta_anuales.csv
├── src/main/
│   ├── java/cl/duoc/bancoxyz/
│   │   ├── config/
│   │   ├── exception/
│   │   ├── model/
│   │   ├── processor/
│   │   └── BankBatchApplication.java
│   └── resources/
│       ├── input/
│       │   ├── transacciones.csv
│       │   ├── intereses.csv
│       │   └── cuentas_anuales.csv
│       ├── application.properties
│       └── schema.sql
├── .dockerignore
├── Dockerfile
├── docker-compose.yml
├── mvnw
├── mvnw.cmd
└── pom.xml
```

`config` contiene los Jobs, Steps, políticas y listeners; `processor` concentra las validaciones y transformaciones; `model` representa los registros CSV; y `resources` contiene configuración, esquema y datos de entrada.

## 5. Procesos Batch implementados

### dailyTransactionsJob

Lee `input/transacciones.csv` mediante `dailyTransactionsStep`. Valida campos, fecha, monto, tipo y duplicados; persiste las transacciones válidas en `transacciones_procesadas` y registra el resumen en `resumen_transacciones_diarias`.

### monthlyInterestJob

Lee `input/intereses.csv` mediante `monthlyInterestStep`. Valida cuentas, saldo, edad y tipo, calcula el interés de ahorro o préstamo y persiste el resultado en `intereses_mensuales`.

### annualStatementsJob

Lee `input/cuentas_anuales.csv` mediante `annualMovementsStep`, valida los movimientos y los persiste en `movimientos_anuales`. Luego `annualStatementReportStep` genera `output/estados_cuenta_anuales.csv`.

## 6. Procesamiento paralelo y escalamiento

El proyecto usa `ThreadPoolTaskExecutor` para ejecutar chunks en paralelo con hilos `Batch-Thread-`. La configuración utilizada en las ejecuciones principales es:

```properties
bank.batch.chunk-size=5
bank.batch.core-pool-size=3
bank.batch.max-pool-size=3
bank.batch.queue-capacity=25
```

| Configuración | Chunk | Threads | Duración |
| ------------- | ----: | ------: | -------: |
| A             |     2 |       2 |   169 ms |
| B             |     5 |       3 |   185 ms |
| C             |     5 |       4 |   165 ms |

Las tres configuraciones mantuvieron el mismo resultado funcional: 10 registros leídos, 7 escritos, 3 omitidos y estado `COMPLETED`.

La configuración C fue la **mejor configuración observada para el conjunto de datos de prueba**.

## 7. Manejo de errores y tolerancia a fallos

`BankDataSkipPolicy` omite registros con `InvalidDataException` o `FlatFileParseException`, y `BatchSkipListener` registra esos skips. Durante las ejecuciones finales hubo skips reales por registros inválidos.

La `RetryPolicy` reintenta únicamente `TransientDataAccessException`, con límite de 3 intentos. `FixedBackOffPolicy` espera 100 ms entre reintentos.

No se produjo un error transitorio real de PostgreSQL durante la ejecución final, por lo que la política quedó configurada pero no fue activada.

## 8. Requisitos de ejecución

- Java 17
- Docker Desktop con integración habilitada para WSL
- Docker Compose
- WSL Ubuntu

## 9. Instrucciones para ejecutar el proyecto

Todas las instrucciones siguientes se ejecutan desde una terminal Ubuntu WSL.

desde:

```bash
cd "/home/francisco/proyecto/BACKEND III/SEMANA 3/Exp1_S3_Francisco_Henriquez/proyecto"
```

### 9.1 Levantar PostgreSQL

Para comenzar desde una base de datos limpia:

```bash
docker compose down -v --remove-orphans
docker compose up -d postgres
docker compose ps
```

Comprobar que PostgreSQL esté disponible:

```bash
docker compose exec -T postgres pg_isready -U banco_user -d banco_xyz
```

El resultado debe indicar:

```text
accepting connections
```

### 9.2 Compilar el proyecto

```bash
chmod +x mvnw
./mvnw clean package
```

El resultado esperado es:

```text
BUILD SUCCESS
```

El JAR generado es:

```text
target/banco-xyz-batch-1.0.0.jar
```

### 9.3 Crear carpeta para evidencias reproducidas

Para no modificar las evidencias originales:

```bash
mkdir -p ../evidencias/reproduccion
```

### 9.4 Ejecutar dailyTransactionsJob

```bash
java -jar target/banco-xyz-batch-1.0.0.jar \
  --spring.batch.job.name=dailyTransactionsJob \
  ejecucion=301,java.lang.Long,true \
  2>&1 | tee ../evidencias/reproduccion/01_transacciones.txt
```

Resultado esperado:

```text
Read = 10
Write = 7
Skip = 3
Estado = COMPLETED
```

### 9.5 Ejecutar monthlyInterestJob

```bash
java -jar target/banco-xyz-batch-1.0.0.jar \
  --spring.batch.job.name=monthlyInterestJob \
  ejecucion=302,java.lang.Long,true \
  2>&1 | tee ../evidencias/reproduccion/02_intereses.txt
```

Resultado esperado:

```text
Read = 8
Write = 6
Skip = 2
Estado = COMPLETED
```

### 9.6 Ejecutar annualStatementsJob

```bash
java -jar target/banco-xyz-batch-1.0.0.jar \
  --spring.batch.job.name=annualStatementsJob \
  ejecucion=303,java.lang.Long,true \
  2>&1 | tee ../evidencias/reproduccion/03_estados_anuales.txt
```

Resultado esperado de `annualMovementsStep`:

```text
Read = 9
Write = 8
Skip = 1
Estado = COMPLETED
```

También deben finalizar:

```text
annualStatementReportStep = COMPLETED
annualStatementsJob = COMPLETED
```

## 10. Resultados de ejecución

| Job / Step                  | Read | Write | Skip | Estado      |
| --------------------------- | ---: | ----: | ---: | ----------- |
| `dailyTransactionsJob`      |   10 |     7 |    3 | `COMPLETED` |
| `monthlyInterestJob`        |    8 |     6 |    2 | `COMPLETED` |
| `annualMovementsStep`       |    9 |     8 |    1 | `COMPLETED` |
| `annualStatementReportStep` |    0 |     0 |    0 | `COMPLETED` |

`annualStatementReportStep` es un Step basado en Tasklet que genera el informe anual; por eso sus contadores no representan procesamiento por chunks.

## 11. Resultados en base de datos

Después de ejecutar los tres Jobs sobre una base limpia:

```text
transacciones_procesadas = 7
intereses_mensuales = 6
movimientos_anuales = 8
```

PostgreSQL también almacena los metadatos de Spring Batch en las tablas `BATCH_*`.

## 12. Archivo generado

`annualStatementsJob` genera:

```text
output/estados_cuenta_anuales.csv
```

## 13. Evidencias

Las evidencias originales del proyecto son:

```text
evidencias/01_transacciones.txt
evidencias/02_intereses.txt
evidencias/03_estados_anuales.txt
evidencias/04_reejecucion_transacciones.txt
evidencias/escala_A.txt
evidencias/escala_B.txt
evidencias/escala_C.txt
```

Las nuevas ejecuciones realizadas siguiendo este README se almacenan en:

```text
evidencias/reproduccion/
```

# 14. Reproducción de las 9 figuras del informe

Las siguientes instrucciones permiten reproducir las evidencias utilizadas en:

```text
PBY2203_Exp1_S3_Francisco_Henriquez.docx
```

Para las Figuras 1 a 6 se utiliza la ejecución normal realizada anteriormente.

## 14.1 Figura 1 - dailyTransactionsJob

Limpiar la terminal:

```bash
clear
```

Mostrar las líneas relevantes:

```bash
grep -E "TaskExecutor configurado|Registro omitido|Finaliza Step dailyTransactionsStep|RESUMEN dailyTransactionsJob" \
  ../evidencias/reproduccion/01_transacciones.txt
```

La salida debe permitir comprobar:

```text
dailyTransactionsJob
dailyTransactionsStep
leidos=10
escritos=7
omitidos=3
estado=COMPLETED
```

## 14.2 Figura 2 - monthlyInterestJob

Limpiar:

```bash
clear
```

Mostrar las líneas relevantes:

```bash
grep -E "TaskExecutor configurado|Registro omitido|Finaliza Step monthlyInterestStep|RESUMEN monthlyInterestJob" \
  ../evidencias/reproduccion/02_intereses.txt
```

La salida debe permitir comprobar:

```text
monthlyInterestJob
monthlyInterestStep
leidos=8
escritos=6
omitidos=2
estado=COMPLETED
```

## 14.3 Figura 3 - annualStatementsJob

Limpiar:

```bash
clear
```

Ejecutar:

```bash
grep -E "TaskExecutor configurado|Registro omitido|Finaliza Step annualMovementsStep|annualStatementReportStep|RESUMEN annualStatementsJob" \
  ../evidencias/reproduccion/03_estados_anuales.txt
```

La salida debe permitir comprobar:

```text
annualMovementsStep
leidos=9
escritos=8
omitidos=1
COMPLETED

annualStatementReportStep
COMPLETED

annualStatementsJob
COMPLETED
```

## 14.4 Figura 4 - Resultados en PostgreSQL

Después de ejecutar los tres Jobs, limpiar la terminal:

```bash
clear
```

Ejecutar:

```bash
docker compose exec -T postgres psql \
  -U banco_user \
  -d banco_xyz \
  -At \
  -c "
SELECT 'transacciones_procesadas: ' || COUNT(*) || ' filas'
FROM transacciones_procesadas;

SELECT 'intereses_mensuales: ' || COUNT(*) || ' filas'
FROM intereses_mensuales;

SELECT 'movimientos_anuales: ' || COUNT(*) || ' filas'
FROM movimientos_anuales;
"
```

El resultado esperado es:

```text
transacciones_procesadas: 7 filas
intereses_mensuales: 6 filas
movimientos_anuales: 8 filas
```

## 14.5 Figura 5 - Archivo anual generado

Comprobar que el archivo existe:

```bash
ls -l output/estados_cuenta_anuales.csv
```

Limpiar:

```bash
clear
```

Mostrar su contenido:

```bash
cat output/estados_cuenta_anuales.csv
```

El encabezado debe ser:

```text
cuenta_id,total_movimientos,total_depositos,total_cargos,saldo_neto
```

En la ejecución validada se obtuvo:

```text
101,2,1000.00,-500.00,500.00
102,1,1500.00,0,1500.00
103,1,2000.00,0,2000.00
104,1,0,-100.00,-100.00
105,1,2500.00,0,2500.00
106,1,3000.00,0,3000.00
108,1,2000.00,0,2000.00
```

## 14.6 Figura 6 - Procesamiento paralelo

La evidencia se obtiene de los logs de los tres Jobs.

Limpiar:

```bash
clear
```

Ejecutar:

```bash
{
  grep -m1 "TaskExecutor configurado" \
    ../evidencias/reproduccion/01_transacciones.txt

  grep "Batch-Thread-1" \
    ../evidencias/reproduccion/01_transacciones.txt \
    ../evidencias/reproduccion/02_intereses.txt \
    ../evidencias/reproduccion/03_estados_anuales.txt \
    | head -n 1

  grep "Batch-Thread-2" \
    ../evidencias/reproduccion/01_transacciones.txt \
    ../evidencias/reproduccion/02_intereses.txt \
    ../evidencias/reproduccion/03_estados_anuales.txt \
    | head -n 1

  grep "Batch-Thread-3" \
    ../evidencias/reproduccion/01_transacciones.txt \
    ../evidencias/reproduccion/02_intereses.txt \
    ../evidencias/reproduccion/03_estados_anuales.txt \
    | head -n 1
}
```

La salida debe permitir observar:

```text
TaskExecutor configurado
Batch-Thread-1
Batch-Thread-2
Batch-Thread-3
```

La asignación exacta de los registros a cada hilo puede variar entre ejecuciones.

Tomar la captura de pantalla.

## 14.7 Figura 7 - Comparación de escalamiento

La comparación utiliza `dailyTransactionsJob`.

Cada configuración debe ejecutarse desde una base de datos limpia y utilizando el mismo archivo de entrada.

Los valores de tiempo pueden ser diferentes a los registrados originalmente.

Lo que debe mantenerse en las tres pruebas es:

```text
Read = 10
Write = 7
Skip = 3
Estado = COMPLETED
```

### Configuración A

Reiniciar PostgreSQL:

```bash
docker compose down -v --remove-orphans
docker compose up -d postgres
```

Esperar a PostgreSQL:

```bash
until docker compose exec -T postgres pg_isready -U banco_user -d banco_xyz
do
  sleep 1
done
```

Ejecutar:

```bash
java -jar target/banco-xyz-batch-1.0.0.jar \
  --spring.batch.job.name=dailyTransactionsJob \
  --bank.batch.chunk-size=2 \
  --bank.batch.core-pool-size=2 \
  --bank.batch.max-pool-size=2 \
  --bank.batch.queue-capacity=25 \
  ejecucion=101,java.lang.Long,true \
  2>&1 | tee ../evidencias/reproduccion/escala_A.txt
```

### Configuración B

Reiniciar PostgreSQL:

```bash
docker compose down -v --remove-orphans
docker compose up -d postgres
```

Esperar:

```bash
until docker compose exec -T postgres pg_isready -U banco_user -d banco_xyz
do
  sleep 1
done
```

Ejecutar:

```bash
java -jar target/banco-xyz-batch-1.0.0.jar \
  --spring.batch.job.name=dailyTransactionsJob \
  --bank.batch.chunk-size=5 \
  --bank.batch.core-pool-size=3 \
  --bank.batch.max-pool-size=3 \
  --bank.batch.queue-capacity=25 \
  ejecucion=102,java.lang.Long,true \
  2>&1 | tee ../evidencias/reproduccion/escala_B.txt
```

### Configuración C

Reiniciar PostgreSQL:

```bash
docker compose down -v --remove-orphans
docker compose up -d postgres
```

Esperar:

```bash
until docker compose exec -T postgres pg_isready -U banco_user -d banco_xyz
do
  sleep 1
done
```

Ejecutar:

```bash
java -jar target/banco-xyz-batch-1.0.0.jar \
  --spring.batch.job.name=dailyTransactionsJob \
  --bank.batch.chunk-size=5 \
  --bank.batch.core-pool-size=4 \
  --bank.batch.max-pool-size=4 \
  --bank.batch.queue-capacity=25 \
  ejecucion=103,java.lang.Long,true \
  2>&1 | tee ../evidencias/reproduccion/escala_C.txt
```

### Mostrar las tres pruebas juntas

Limpiar:

```bash
clear
```

Ejecutar:

```bash
echo "CONFIGURACION A"
echo "chunk=2 | core=2 | max=2 | cola=25"
grep "Finaliza Step dailyTransactionsStep" \
  ../evidencias/reproduccion/escala_A.txt

echo
echo "CONFIGURACION B"
echo "chunk=5 | core=3 | max=3 | cola=25"
grep "Finaliza Step dailyTransactionsStep" \
  ../evidencias/reproduccion/escala_B.txt

echo
echo "CONFIGURACION C"
echo "chunk=5 | core=4 | max=4 | cola=25"
grep "Finaliza Step dailyTransactionsStep" \
  ../evidencias/reproduccion/escala_C.txt
```

La salida será equivalente a:

```text
CONFIGURACION A
chunk=2 | core=2 | max=2 | cola=25
Finaliza Step dailyTransactionsStep - duracionMs=XXX, leidos=10, escritos=7, omitidos=3, estado=COMPLETED

CONFIGURACION B
chunk=5 | core=3 | max=3 | cola=25
Finaliza Step dailyTransactionsStep - duracionMs=XXX, leidos=10, escritos=7, omitidos=3, estado=COMPLETED

CONFIGURACION C
chunk=5 | core=4 | max=4 | cola=25
Finaliza Step dailyTransactionsStep - duracionMs=XXX, leidos=10, escritos=7, omitidos=3, estado=COMPLETED
```

`XXX` corresponde al tiempo realmente obtenido en cada nueva ejecución.

## 14.8 Figura 8 - Nueva ejecución de dailyTransactionsJob

Esta prueba ejecuta nuevamente `dailyTransactionsJob` utilizando un parámetro identificador diferente. Corresponde a una nueva ejecución completa y no a una reanudación desde una fila específica del CSV.

Ejecutar:

```bash
java -jar target/banco-xyz-batch-1.0.0.jar \
  --spring.batch.job.name=dailyTransactionsJob \
  ejecucion=305,java.lang.Long,true \
  2>&1 | tee ../evidencias/reproduccion/04_reejecucion_transacciones.txt
```

El resultado esperado es:

```text
Read = 10
Write = 7
Skip = 3
Estado = COMPLETED
```

Para mostrar únicamente las líneas relevantes de la ejecución:

```bash
grep -E "Finaliza Step dailyTransactionsStep|RESUMEN dailyTransactionsJob|ejecucion.*305|status.*COMPLETED" \
  ../evidencias/reproduccion/04_reejecucion_transacciones.txt
```

La salida debe permitir comprobar que `dailyTransactionsJob` fue ejecutado nuevamente con el parámetro `ejecucion=305` y finalizó con estado `COMPLETED`.

## 14.9 Figura 9 - Verificación de reejecución y ausencia de duplicados

Después de la nueva ejecución, se verifica el registro de las ejecuciones de `dailyTransactionsJob` almacenadas por Spring Batch, el contenido final de la tabla `transacciones_procesadas` y la ausencia de registros duplicados.

Limpiar la terminal:

```bash
clear
```

Ejecutar:

```bash
docker compose exec -T postgres psql \
  -U banco_user \
  -d banco_xyz \
  -c "
SELECT
    ji.JOB_INSTANCE_ID,
    ji.JOB_NAME,
    je.JOB_EXECUTION_ID,
    je.STATUS,
    jep.PARAMETER_NAME,
    jep.PARAMETER_VALUE,
    jep.IDENTIFYING
FROM BATCH_JOB_INSTANCE ji
JOIN BATCH_JOB_EXECUTION je
    ON ji.JOB_INSTANCE_ID = je.JOB_INSTANCE_ID
LEFT JOIN BATCH_JOB_EXECUTION_PARAMS jep
    ON je.JOB_EXECUTION_ID = jep.JOB_EXECUTION_ID
WHERE ji.JOB_NAME = 'dailyTransactionsJob'
ORDER BY je.JOB_EXECUTION_ID;
"

docker compose exec -T postgres psql \
  -U banco_user \
  -d banco_xyz \
  -c "SELECT * FROM transacciones_procesadas ORDER BY id;"

docker compose exec -T postgres psql \
  -U banco_user \
  -d banco_xyz \
  -c "
SELECT id, COUNT(*) AS cantidad
FROM transacciones_procesadas
GROUP BY id
HAVING COUNT(*) > 1;
"
```

La salida debe permitir comprobar:

```text
dailyTransactionsJob - ejecucion 103 - COMPLETED
dailyTransactionsJob - ejecucion 305 - COMPLETED

transacciones_procesadas = 7 registros

Registros duplicados = 0
```

Las ejecuciones 103, 304 y 305 quedan registradas por Spring Batch con estado `COMPLETED`. Después de la reejecución, la tabla `transacciones_procesadas` mantiene 7 registros y la consulta de duplicados retorna `0 rows`, verificando que no se generaron registros duplicados.

## 15. Resumen de las evidencias reproducibles

| Figura   | Evidencia                                            |
| -------- | ---------------------------------------------------- |
| Figura 1 | Ejecución de `dailyTransactionsJob`                  |
| Figura 2 | Ejecución de `monthlyInterestJob`                    |
| Figura 3 | Ejecución de `annualStatementsJob`                   |
| Figura 4 | Resultados persistidos en PostgreSQL                 |
| Figura 5 | Contenido de `estados_cuenta_anuales.csv`            |
| Figura 6 | Procesamiento paralelo mediante `Batch-Thread-*`     |
| Figura 7 | Comparación A, B y C de escalamiento                 |
| Figura 8 | Nueva ejecución de `dailyTransactionsJob`            |
| Figura 9 | Verificación de reejecución y ausencia de duplicados |

## 16. Consideración sobre los tiempos

Los tiempos originales observados fueron:

```text
A = 169 ms
B = 185 ms
C = 165 ms
```

Estos valores corresponden a una ejecución concreta y pueden variar al repetir la prueba.

En cada nueva ejecución se deben conservar los resultados funcionales:

```text
Read = 10
Write = 7
Skip = 3
Estado = COMPLETED
```

## 17. Ejecución completa mediante Docker Compose

Como alternativa a la ejecución de la aplicación mediante `java -jar`, el proyecto puede ejecutarse completamente mediante Docker Compose.

Esta modalidad utiliza dos componentes:

```text
Docker Compose
├── postgres
│   └── PostgreSQL 16
│
└── app
    └── Spring Boot + Spring Batch + Java 17
```

El servicio `postgres` mantiene la base de datos y el servicio `app` contiene la aplicación Spring Batch.

Esta alternativa facilita el despliegue del proyecto porque el equipo donde se ejecute solamente necesita Docker y Docker Compose. Java 17 y la compilación Maven son administrados dentro de la imagen Docker de la aplicación.

### 17.1 Construir la imagen de la aplicación

Desde la carpeta del proyecto:

```bash
cd "/home/francisco/proyecto/BACKEND III/SEMANA 3/Exp1_S3_Francisco_Henriquez/proyecto"
```

Construir la imagen:

```bash
docker compose build
```

Durante este proceso Docker utiliza el `Dockerfile` para:

```text
1. Utilizar Java 17 JDK para compilar.
2. Ejecutar Maven Wrapper.
3. Generar banco-xyz-batch-1.0.0.jar.
4. Crear una imagen final utilizando Java 17 JRE.
```

El resultado esperado debe finalizar indicando que la imagen de la aplicación fue construida correctamente.

Por ejemplo:

```text
Image proyecto-app Built
```

### 17.2 Levantar PostgreSQL

Ejecutar:

```bash
docker compose up -d postgres
```

Comprobar el estado:

```bash
docker compose ps
```

El contenedor debe aparecer con estado:

```text
healthy
```

Por ejemplo:

```text
banco-xyz-postgres   postgres:16   Up (...) (healthy)
```

El servicio PostgreSQL utiliza internamente:

```text
postgres:5432
```

El puerto publicado hacia WSL es:

```text
localhost:5433
```

Cuando la aplicación también se ejecuta dentro de Docker, Docker Compose utiliza el nombre del servicio `postgres` para establecer la comunicación entre los contenedores.

La conexión utilizada por el contenedor `app` es:

```text
jdbc:postgresql://postgres:5432/banco_xyz
```

### 17.3 Ejecutar dailyTransactionsJob

Ejecutar:

```bash
docker compose run --rm app \
  --spring.batch.job.name=dailyTransactionsJob \
  ejecucion=401,java.lang.Long,true
```

El contenedor espera automáticamente hasta que PostgreSQL se encuentre disponible.

El resultado funcional esperado es:

```text
Read = 10
Write = 7
Skip = 3
Estado = COMPLETED
```

En los logs debe observarse una salida equivalente a:

```text
Finaliza Step dailyTransactionsStep - leidos=10, escritos=7, omitidos=3, estado=COMPLETED
```

y posteriormente:

```text
dailyTransactionsJob ... status: [COMPLETED]
```

Cuando el Job ya indique `COMPLETED`, presionar:

```text
Ctrl+C
```

para cerrar el contenedor temporal de la aplicación.

La opción:

```text
--rm
```

hace que el contenedor temporal sea eliminado al finalizar.

PostgreSQL permanece ejecutándose.

### 17.4 Ejecutar monthlyInterestJob

Ejecutar:

```bash
docker compose run --rm app \
  --spring.batch.job.name=monthlyInterestJob \
  ejecucion=402,java.lang.Long,true
```

El resultado funcional esperado es:

```text
Read = 8
Write = 6
Skip = 2
Estado = COMPLETED
```

En los logs debe observarse una salida equivalente a:

```text
Finaliza Step monthlyInterestStep - leidos=8, escritos=6, omitidos=2, estado=COMPLETED
```

y posteriormente:

```text
monthlyInterestJob ... status: [COMPLETED]
```

Cuando el Job indique `COMPLETED`, presionar:

```text
Ctrl+C
```

para cerrar el contenedor temporal.

### 17.5 Ejecutar annualStatementsJob

Ejecutar:

```bash
docker compose run --rm app \
  --spring.batch.job.name=annualStatementsJob \
  ejecucion=403,java.lang.Long,true
```

El resultado funcional esperado de `annualMovementsStep` es:

```text
Read = 9
Write = 8
Skip = 1
Estado = COMPLETED
```

También deben finalizar correctamente:

```text
annualStatementReportStep = COMPLETED
annualStatementsJob = COMPLETED
```

En los logs debe observarse una salida equivalente a:

```text
Finaliza Step annualMovementsStep - leidos=9, escritos=8, omitidos=1, estado=COMPLETED
```

y:

```text
annualStatementsJob ... status: [COMPLETED]
```

Cuando el Job indique `COMPLETED`, presionar:

```text
Ctrl+C
```

para cerrar el contenedor temporal.

### 17.6 Verificar los resultados en PostgreSQL

Después de ejecutar los tres Jobs, consultar la base de datos:

```bash
docker compose exec -T postgres psql \
  -U banco_user \
  -d banco_xyz \
  -At \
  -c "
SELECT 'transacciones_procesadas: ' || COUNT(*) || ' filas'
FROM transacciones_procesadas;

SELECT 'intereses_mensuales: ' || COUNT(*) || ' filas'
FROM intereses_mensuales;

SELECT 'movimientos_anuales: ' || COUNT(*) || ' filas'
FROM movimientos_anuales;
"
```

El resultado esperado es:

```text
transacciones_procesadas: 7 filas
intereses_mensuales: 6 filas
movimientos_anuales: 8 filas
```

### 17.7 Verificar el archivo anual

El servicio `app` utiliza un volumen para compartir la carpeta:

```text
./output
```

con:

```text
/app/output
```

dentro del contenedor.

Por esta razón, el archivo generado por `annualStatementsJob` permanece disponible en el proyecto después de finalizar el contenedor.

Comprobar:

```bash
ls -l output/estados_cuenta_anuales.csv
```

Mostrar su contenido:

```bash
cat output/estados_cuenta_anuales.csv
```

El archivo debe comenzar con:

```text
cuenta_id,total_movimientos,total_depositos,total_cargos,saldo_neto
```

### 17.8 Detener PostgreSQL

Después de finalizar las pruebas, detener los servicios:

```bash
docker compose down
```

Este comando detiene y elimina los contenedores, pero mantiene el volumen de PostgreSQL.

Si se desea eliminar también la base de datos y comenzar nuevamente desde cero:

```bash
docker compose down -v --remove-orphans
```

El parámetro:

```text
-v
```

elimina el volumen de PostgreSQL y, por lo tanto, todos los datos almacenados.

### 17.9 Resumen de ejecución mediante Docker Compose

La ejecución completa mediante Docker Compose queda formada por:

```text
docker compose build
        ↓
construye la aplicación con Java 17
        ↓
docker compose up -d postgres
        ↓
PostgreSQL queda healthy
        ↓
docker compose run --rm app dailyTransactionsJob
        ↓
10 / 7 / 3 / COMPLETED
        ↓
docker compose run --rm app monthlyInterestJob
        ↓
8 / 6 / 2 / COMPLETED
        ↓
docker compose run --rm app annualStatementsJob
        ↓
9 / 8 / 1 / COMPLETED
        ↓
PostgreSQL
7 / 6 / 8
        ↓
output/estados_cuenta_anuales.csv
```

Esta modalidad no reemplaza las instrucciones de ejecución y reproducción de evidencias indicadas anteriormente. Corresponde a una alternativa adicional para facilitar la construcción y ejecución completa de la solución mediante contenedores Docker.

## 18. SEMANA 4 - BACKEND FOR FRONTEND (BFF)

### Objetivo y estrategia

El patron Backend for Frontend adapta la informacion y las operaciones al tipo de cliente que las consume. Para este proyecto se seleccionaron endpoints separados por canal dentro de la misma aplicacion Spring Boot: Web, Mobile y ATM. Esta estrategia entrega contratos especificos, reutiliza PostgreSQL y la logica existente, y evita introducir microservicios o componentes adicionales fuera del alcance academico.

La capa BFF consulta solamente datos persistidos por los Jobs previos. No lee los CSV ni modifica la arquitectura Spring Batch.

### Estructura BFF

```text
cl.duoc.bancoxyz.bff/
├── BffBankingService.java
├── web/
│   ├── WebBffController.java
│   └── dto/
├── mobile/
│   ├── MobileBffController.java
│   └── dto/
└── atm/
    ├── AtmBffController.java
    └── dto/
```

### Canales y endpoints

| Canal  | Endpoint                                                 | Datos entregados                                                  |
| ------ | -------------------------------------------------------- | ----------------------------------------------------------------- |
| Web    | `GET /api/bff/web/accounts/{accountId}`                  | Cuenta, nombre, tipo, saldo inicial, tasa, interes y saldo final. |
| Web    | `GET /api/bff/web/accounts/{accountId}/annual-statement` | Movimientos, depositos, cargos y saldo neto anual.                |
| Mobile | `GET /api/bff/mobile/accounts/{accountId}`               | Cuenta, nombre, saldo y tipo.                                     |
| Mobile | `GET /api/bff/mobile/accounts/{accountId}/transactions`  | Ultimos cinco movimientos por fecha descendente.                  |
| ATM    | `GET /api/bff/atm/accounts/{accountId}/balance`          | Cuenta y saldo.                                                   |
| ATM    | `POST /api/bff/atm/accounts/{accountId}/withdraw`        | Retiro y saldo actualizado.                                       |

Web entrega el contrato mas completo; Mobile elimina tasa, saldo inicial e interes; ATM entrega solamente los datos necesarios para consultar y retirar efectivo.

### Seguridad por canal

Cada ruta BFF requiere el header `X-Channel-Token`. Los tokens se configuran mediante variables de entorno o `application.properties`:

| Canal  | Variable           | Valor local por defecto |
| ------ | ------------------ | ----------------------- |
| Web    | `BFF_WEB_TOKEN`    | `web-token-xyz`         |
| Mobile | `BFF_MOBILE_TOKEN` | `mobile-token-xyz`      |
| ATM    | `BFF_ATM_TOKEN`    | `atm-token-xyz`         |

Un token ausente, o de un canal distinto, responde `401 Unauthorized`. Una cuenta inexistente responde `404`; los retiros con monto no valido o saldo insuficiente responden `400`.

## Reproducción de evidencias por consola

Los siguientes comandos corresponden a las ejecuciones registradas en `evidencias/01_bff_web.txt` a `evidencias/06_bff_verificacion_final.txt`. Utilizan los datos ya persistidos por los Jobs Batch; no reinicializan la base de datos ni vuelven a ejecutar los Jobs.

### Preparación del entorno

```bash
cd "/home/francisco/proyecto/BACKEND III/SEMANA 4/Exp1_S4_Francisco_Henriquez/proyecto"
docker compose ps
```

La evidencia registró PostgreSQL healthy en el puerto `5433`. Si la API BFF no está disponible, iniciarla sin lanzar los Jobs Batch:

```bash
java -jar target/banco-xyz-batch-1.0.0.jar --spring.batch.job.enabled=false
```

### BFF Web

```bash
curl -i -H 'X-Channel-Token: web-token-xyz' \
  http://127.0.0.1:8080/api/bff/web/accounts/101

curl -i -H 'X-Channel-Token: web-token-xyz' \
  http://127.0.0.1:8080/api/bff/web/accounts/101/annual-statement
```

Las respuestas registradas fueron `HTTP 200`. El detalle Web de la cuenta 101 incluyó `initialBalance` 8000.00, `interestRate` 0.0200, `calculatedInterest` 160.00 y `finalBalance` 8160.00. El estado anual registró 14 movimientos, depósitos por 21000.00, cargos por -1300.00 y saldo neto de 19700.00.

### BFF Mobile

```bash
curl -i -H 'X-Channel-Token: mobile-token-xyz' \
  http://127.0.0.1:8080/api/bff/mobile/accounts/101

curl -i -H 'X-Channel-Token: mobile-token-xyz' \
  http://127.0.0.1:8080/api/bff/mobile/accounts/101/transactions
```

Ambas respuestas registraron `HTTP 200`. El contrato Mobile de la cuenta 101 incluyó solamente cuenta, nombre, saldo y tipo. La consulta de movimientos devolvió cinco registros, ordenados desde `2024-12-22` hasta `2024-07-09`.

### BFF ATM y persistencia

Antes de retirar, consultar las cuentas reales con saldo suficiente:

```bash
docker compose exec -T postgres psql -U banco_user -d banco_xyz -c "
SELECT cuenta_id, nombre, tipo, saldo_inicial, tasa, saldo_final
FROM intereses_mensuales
WHERE saldo_final >= 10000
ORDER BY saldo_final DESC
LIMIT 10;
"
```

La evidencia seleccionó la cuenta 117, cuyo saldo era 12240.00. La siguiente secuencia registró el saldo antes y después, el detalle Web y la persistencia del retiro:

```bash
curl -i -H 'X-Channel-Token: atm-token-xyz' \
  http://127.0.0.1:8080/api/bff/atm/accounts/117/balance

curl -i -H 'X-Channel-Token: web-token-xyz' \
  http://127.0.0.1:8080/api/bff/web/accounts/117

curl -i -X POST -H 'X-Channel-Token: atm-token-xyz' \
  -H 'Content-Type: application/json' -d '{"amount":10000}' \
  http://127.0.0.1:8080/api/bff/atm/accounts/117/withdraw

curl -i -H 'X-Channel-Token: atm-token-xyz' \
  http://127.0.0.1:8080/api/bff/atm/accounts/117/balance

curl -i -H 'X-Channel-Token: web-token-xyz' \
  http://127.0.0.1:8080/api/bff/web/accounts/117

docker compose exec -T postgres psql -U banco_user -d banco_xyz -c "
SELECT cuenta_id, nombre, tipo, saldo_inicial, tasa, saldo_final
FROM intereses_mensuales
WHERE cuenta_id = 117;
"
```

Todas las consultas y el retiro registraron `HTTP 200`. El retiro de 10000.00 dejó saldo 2240.00, tanto en ATM como en PostgreSQL. El detalle Web posterior mantuvo `calculatedInterest` en 240.00, calculado desde saldo inicial 12000.00 y tasa 0.0200, mientras `finalBalance` disminuyó a 2240.00.

### Seguridad y validaciones ATM

```bash
curl -i http://127.0.0.1:8080/api/bff/web/accounts/101

curl -i -H 'X-Channel-Token: web-token-xyz' \
  http://127.0.0.1:8080/api/bff/mobile/accounts/101

curl -i -H 'X-Channel-Token: mobile-token-xyz' \
  http://127.0.0.1:8080/api/bff/mobile/accounts/101

curl -i -H 'X-Channel-Token: web-token-xyz' \
  http://127.0.0.1:8080/api/bff/atm/accounts/117/balance

curl -i -X POST -H 'X-Channel-Token: atm-token-xyz' \
  -H 'Content-Type: application/json' -d '{"amount":5000}' \
  http://127.0.0.1:8080/api/bff/atm/accounts/117/withdraw

curl -i -X POST -H 'X-Channel-Token: atm-token-xyz' \
  -H 'Content-Type: application/json' -d '{"amount":-10000}' \
  http://127.0.0.1:8080/api/bff/atm/accounts/117/withdraw

curl -i -X POST -H 'X-Channel-Token: atm-token-xyz' \
  -H 'Content-Type: application/json' -d '{"amount":10000}' \
  http://127.0.0.1:8080/api/bff/atm/accounts/117/withdraw

curl -i -H 'X-Channel-Token: web-token-xyz' \
  http://127.0.0.1:8080/api/bff/web/accounts/999
```

Las evidencias registraron `401` sin token y con token de canal cruzado; `200` con el token Mobile correcto; `400` para monto 5000, monto negativo y saldo insuficiente; y `404` para la cuenta 999.

### Verificación final

```bash
docker compose ps

docker compose exec -T postgres psql -U banco_user -d banco_xyz -c "
SELECT 'transacciones_procesadas' AS tabla, COUNT(*) AS registros
FROM transacciones_procesadas
UNION ALL
SELECT 'intereses_mensuales', COUNT(*)
FROM intereses_mensuales
UNION ALL
SELECT 'movimientos_anuales', COUNT(*)
FROM movimientos_anuales;
"

find "../evidencias" -maxdepth 1 -type f -printf "%f\n" | sort
find "../evidencias/semana 03" -maxdepth 1 -type f -printf "%f\n" | sort
```

La verificación registrada confirmó PostgreSQL healthy, 387 transacciones procesadas, 50 intereses mensuales y 289 movimientos anuales. Los seis archivos de evidencia de Semana 04 están directamente en `evidencias/`; los archivos históricos de `evidencias/semana 03` se conservan separados.

# Semana 5 - Spring Security y HTTPS

## Objetivo y arquitectura

La aplicacion conserva una sola instancia Spring Boot, una base PostgreSQL y los BFF existentes. La seguridad de Semana 4 basada en `X-Channel-Token` fue reemplazada por Spring Security, HTTP Basic y roles por canal. No se modificaron controladores, DTO, servicio bancario ni procesos Spring Batch.

```text
Cliente HTTPS -> Spring Boot :8443 -> Spring Security -> BFF -> PostgreSQL
                                      ROLE_WEB    -> /api/bff/web/**
                                      ROLE_MOBILE -> /api/bff/mobile/**
                                      ROLE_ATM    -> /api/bff/atm/**
```

Se usa `BCryptPasswordEncoder` para construir usuarios en memoria, `SessionCreationPolicy.STATELESS` para no mantener sesiones y HTTP Basic como mecanismo de autenticacion. Las contrasenas se obtienen desde `application.properties` y se pueden sobrescribir mediante `WEB_PASSWORD`, `MOBILE_PASSWORD` y `ATM_PASSWORD`.

| Usuario      | ROLE          | Web | Mobile | ATM |
| ------------ | ------------- | --- | ------ | --- |
| `webuser`    | `ROLE_WEB`    | SI  | NO     | NO  |
| `mobileuser` | `ROLE_MOBILE` | NO  | SI     | NO  |
| `atmuser`    | `ROLE_ATM`    | NO  | NO     | SI  |

Un HTTP 401 significa que faltan credenciales o son invalidas. Un HTTP 403 significa que el usuario ya fue autenticado, pero su rol no puede utilizar el canal solicitado.

## HTTPS local

Toda la aplicacion escucha solamente en `https://localhost:8443`. El certificado de laboratorio es autofirmado, usa un keystore PKCS12, alias `bff-local`, CN `localhost` y SAN para `localhost` e `127.0.0.1`. No se usa certificado de cliente, JWT ni OAuth2.

### Preparar PostgreSQL desde cero

Desde la ruta real del proyecto:

```bash
cd "/home/francisco/proyecto/BACKEND III/SEMANA 5/Exp1_S5_Francisco_Henriquez/proyecto"

docker compose down -v --remove-orphans
docker compose up -d postgres

until docker compose exec -T postgres pg_isready -U banco_user -d banco_xyz
do
  sleep 1
done

docker compose ps
```

Este flujo elimina el volumen local de PostgreSQL, inicia una base limpia y espera hasta que PostgreSQL acepte conexiones. Permite reproducir el saldo inicial utilizado para la prueba ATM. `docker compose down -v --remove-orphans` elimina los datos locales almacenados en el volumen.

```bash
chmod +x mvnw
chmod +x scripts/generar-certificado.sh
./scripts/generar-certificado.sh
keytool -list -v -keystore src/main/resources/keystore.p12 -storetype PKCS12 -storepass changeit
./mvnw clean package
```

El script crea `src/main/resources/keystore.p12`, que se encuentra excluido del control de versiones. Para un certificado autofirmado local, `curl` usa `-k` exclusivamente en laboratorio.

### Preparar datos para los BFF

Si PostgreSQL se inicio desde cero, ejecutar los tres Jobs Batch antes de probar los BFF Web, Mobile y ATM.

```bash
java -jar target/banco-xyz-batch-1.0.0.jar \
  --spring.main.web-application-type=none \
  --spring.batch.job.name=dailyTransactionsJob \
  ejecucion=501,java.lang.Long,true

java -jar target/banco-xyz-batch-1.0.0.jar \
  --spring.main.web-application-type=none \
  --spring.batch.job.name=monthlyInterestJob \
  ejecucion=502,java.lang.Long,true

java -jar target/banco-xyz-batch-1.0.0.jar \
  --spring.main.web-application-type=none \
  --spring.batch.job.name=annualStatementsJob \
  ejecucion=503,java.lang.Long,true
```

Los resultados esperados finales son `transacciones_procesadas = 387`, `intereses_mensuales = 50` y `movimientos_anuales = 289`.

```bash
docker compose exec -T postgres psql \
  -U banco_user \
  -d banco_xyz \
  -c "
SELECT 'transacciones_procesadas' AS tabla, COUNT(*) AS registros
FROM transacciones_procesadas
UNION ALL
SELECT 'intereses_mensuales', COUNT(*)
FROM intereses_mensuales
UNION ALL
SELECT 'movimientos_anuales', COUNT(*)
FROM movimientos_anuales;
"

docker compose exec -T postgres psql \
  -U banco_user \
  -d banco_xyz \
  -c "
SELECT cuenta_id, saldo_final
FROM intereses_mensuales
WHERE cuenta_id = 102;
"
```

El resultado esperado para la cuenta 102 es `102 | 10200.00`; este saldo inicial permite reproducir el retiro documentado: `10200.00 - 10000.00 = 200.00`.

### Levantar API HTTPS

```bash
java -jar target/banco-xyz-batch-1.0.0.jar \
  --spring.batch.job.enabled=false
```

## Pruebas verificadas

Web autorizado, sin credenciales y con password incorrecta:

```bash
curl -k -sS -i -u webuser:web123 https://localhost:8443/api/bff/web/accounts/101
curl -k -sS -i https://localhost:8443/api/bff/web/accounts/101
curl -k -sS -i -u webuser:incorrecta https://localhost:8443/api/bff/web/accounts/101
```

Mobile autorizado, movimientos y rol Web rechazado:

```bash
curl -k -sS -i -u mobileuser:mobile123 https://localhost:8443/api/bff/mobile/accounts/101
curl -k -sS -i -u mobileuser:mobile123 https://localhost:8443/api/bff/mobile/accounts/101/transactions
curl -k -sS -i -u webuser:web123 https://localhost:8443/api/bff/mobile/accounts/101
```

ATM y retiro HTTPS sobre la cuenta verificada 102:

```bash
curl -k -sS -i -u atmuser:atm123 https://localhost:8443/api/bff/atm/accounts/102/balance
curl -k -sS -i -u webuser:web123 https://localhost:8443/api/bff/atm/accounts/102/balance
curl -k -sS -i -u mobileuser:mobile123 https://localhost:8443/api/bff/atm/accounts/102/balance
curl -k -sS -i -u atmuser:atm123 -X POST -H 'Content-Type: application/json' -d '{"amount":10000}' https://localhost:8443/api/bff/atm/accounts/102/withdraw
```

El POST ATM queda excluido de CSRF solamente bajo `/api/bff/**`, porque es una API REST stateless autenticada mediante HTTP Basic. La prueba real registro el saldo de la cuenta 102 desde 10200.00 hasta 200.00.

En Postman, para este certificado local, usar temporalmente `Settings -> General -> SSL certificate verification -> OFF`; en cada solicitud seleccionar `Authorization -> Basic Auth` y utilizar una de las credenciales de la tabla. No se debe enviar `X-Channel-Token`.

## Evidencias de Semana 5

| Evidencia                                 | Contenido                                                                                              |
| ----------------------------------------- | ------------------------------------------------------------------------------------------------------ |
| `evidencias/01_compilacion_https.txt`     | Compilacion, PostgreSQL y puerto HTTPS                                                                 |
| `evidencias/02_certificado_https.txt`     | Certificado PKCS12 y SAN                                                                               |
| `evidencias/03_seguridad_web.txt`         | Respuestas Web 200 y 401                                                                               |
| `evidencias/04_seguridad_mobile.txt`      | Respuestas Mobile 200 y 403                                                                            |
| `evidencias/05_seguridad_atm.txt`         | Respuestas ATM 200 y 403                                                                               |
| `evidencias/06_autorizacion_roles.txt`    | Matriz real de roles                                                                                   |
| `evidencias/07_atm_retiro_https.txt`      | Retiro HTTPS y persistencia PostgreSQL                                                                 |
| `evidencias/08_verificacion_final.txt`    | Estado final, puerto y conteos PostgreSQL                                                              |
| `evidencias/09_pruebas_automatizadas.txt` | Suite automatizada: pruebas unitarias, Spring Security/BFF e integracion Batch con PostgreSQL temporal |

### Pruebas automatizadas

El proyecto incorpora pruebas unitarias Batch, integración Spring Security y BFF, e integraciones Batch de regresión con PostgreSQL temporal. La suite protege resultados Batch y permisos por canal ya validados.

La ejecucion validada finalizo con 51 pruebas ejecutadas, 0 fallos, 0 errores y 0 pruebas omitidas.

```bash
cd "/home/francisco/proyecto/BACKEND III/SEMANA 5/Exp1_S5_Francisco_Henriquez/proyecto"
./mvnw test
./mvnw clean test
```

Las integraciones Batch usan Testcontainers y requieren Docker disponible para levantar PostgreSQL temporal.

# Semana 6 - Microservicios y seguridad con Spring Cloud

## Evolución arquitectónica

Semana 5 concentraba los controladores Web, Mobile y ATM y `BffBankingService` en una sola aplicación. Para esta entrega el proyecto se reorganizó en módulos Spring Boot independientes dentro de `./proyecto`:

```text
config-server:8888 -> configuración centralizada (config-repo)
eureka-server:8761 -> descubrimiento
core-banking-service:8081 -> PostgreSQL, consultas, movimientos y retiro
bff-web-service:8082 -> DTO Web, ROLE_WEB, JWT y Circuit Breaker
bff-mobile-service:8083 -> DTO Mobile, ROLE_MOBILE, JWT y Circuit Breaker
bff-atm-service:8084 -> saldo/retiro, ROLE_ATM, JWT y Circuit Breaker
```

El Core conserva el modelo, el esquema, los archivos de entrada y la configuración Batch de las semanas anteriores. Los BFF ya no acceden a PostgreSQL: resuelven `core-banking-service` mediante Eureka. Mobile conserva la consulta SQL `LIMIT 5` desde el Core; ATM valida monto múltiplo de 10.000, saldo disponible y persiste el nuevo saldo con una transacción.

## Seguridad

Cada BFF expone `POST /auth/login` con usuarios `webuser/web123`, `mobileuser/mobile123` y `atmuser/atm123`. Las contraseñas se codifican con BCrypt, las sesiones son STATELESS y los JWT llevan el rol del usuario. Los endpoints de cada canal requieren respectivamente `ROLE_WEB`, `ROLE_MOBILE` y `ROLE_ATM`; el filtro devuelve 401 para token ausente/inválido y 403 para rol incorrecto. HTTP Basic no se utiliza como mecanismo final.

Config Server usa `./config-repo`; todos los módulos tienen respaldo local de desarrollo y consumen `spring.config.import` cuando Config Server está disponible. Cada llamada BFF -> Core tiene un Circuit Breaker Resilience4j y fallback HTTP 503 controlado.

## Reproducción completa de evidencias — Semana 06

Este procedimiento corresponde a la Opción A y fue utilizado para generar las evidencias entregadas.

Se ejecuta en una sola consola WSL y mantiene los servicios en segundo plano mediante `nohup`.

### 1. Definir las rutas de trabajo

```bash
ROOT="/home/francisco/proyecto/BACKEND III/SEMANA 6/Exp3_S6_Grupo11"
PROY="$ROOT/proyecto"
EVID="$ROOT/evidencias/semana 06"

mkdir -p "$EVID"

cd "$PROY"

echo "PROYECTO:"
pwd

echo
echo "EVIDENCIAS:"
echo "$EVID"
```

Resultado esperado:

```text
PROYECTO:
/home/francisco/proyecto/BACKEND III/SEMANA 6/Exp3_S6_Grupo11/proyecto

EVIDENCIAS:
/home/francisco/proyecto/BACKEND III/SEMANA 6/Exp3_S6_Grupo11/evidencias/semana 06
```

```bash
ls -ld "$EVID"
```

### 2. Detener instancias anteriores

```bash
pkill -f 'config-server/target/config-server-1.0.0.jar' 2>/dev/null || true
pkill -f 'eureka-server/target/eureka-server-1.0.0.jar' 2>/dev/null || true
pkill -f 'core-banking-service/target/core-banking-service-1.0.0.jar' 2>/dev/null || true
pkill -f 'bff-web-service/target/bff-web-service-1.0.0.jar' 2>/dev/null || true
pkill -f 'bff-mobile-service/target/bff-mobile-service-1.0.0.jar' 2>/dev/null || true
pkill -f 'bff-atm-service/target/bff-atm-service-1.0.0.jar' 2>/dev/null || true

sleep 3

ss -ltnp | grep -E ':(8081|8082|8083|8084|8761|8888)\b' || true
```

Para un inicio limpio no deberia aparecer ninguno de estos puertos.

### 3. Reiniciar PostgreSQL desde una base limpia

```bash
cd "$PROY"

docker compose down -v --remove-orphans
docker compose up -d postgres
docker ps

until docker exec banco-xyz-postgres \
    pg_isready -U banco_user -d banco_xyz >/dev/null 2>&1
do
    echo "Esperando PostgreSQL..."
    sleep 2
done

echo "PostgreSQL OK"
```

docker compose down -v --remove-orphans elimina el volumen PostgreSQL y los datos de ejecuciones anteriores. De esta forma las pruebas de Semana 6 comienzan desde una base limpia y reproducible.

El archivo `docker-compose.yml` también contiene los servicios necesarios para la ejecución completa mediante Docker. Sin embargo, `docker compose up -d postgres` utilizado en esta reproducción inicia únicamente PostgreSQL; los demás componentes se ejecutan mediante los comandos `java -jar` documentados en esta sección. La ejecución completa mediante Docker Compose se presenta posteriormente como una alternativa.

### 4. Compilar el proyecto y generar evidencia 18 - Build real

Este comando ejecuta `clean package` sobre el proyecto multimodulo, compila los seis componentes y genera los JAR que se utilizaran en los pasos siguientes. La misma ejecucion se almacena como evidencia `18_build.txt`.

```bash
cd "$PROY"

{
    echo "DIRECTORIO DE EJECUCION:"
    pwd
    echo
    echo "COMANDO:"
    echo "./mvnw -f pom.xml clean package"
    echo
    ./mvnw -f pom.xml clean package
} 2>&1 | tee "$EVID/18_build.txt"

grep -E "BUILD SUCCESS|BUILD FAILURE" "$EVID/18_build.txt"
tail -30 "$EVID/18_build.txt"
```

El resultado esperado es `BUILD SUCCESS`.

### 5. Verificar los seis JAR

```bash
ls -lh \
  config-server/target/config-server-1.0.0.jar \
  eureka-server/target/eureka-server-1.0.0.jar \
  core-banking-service/target/core-banking-service-1.0.0.jar \
  bff-web-service/target/bff-web-service-1.0.0.jar \
  bff-mobile-service/target/bff-mobile-service-1.0.0.jar \
  bff-atm-service/target/bff-atm-service-1.0.0.jar
```

Deben aparecer los seis artefactos.

### 6. Crear función de espera

```bash
wait_url() {
    URL="$1"
    NAME="$2"

    for i in {1..90}
    do
        if curl -fsS "$URL" >/dev/null 2>&1
        then
            echo "$NAME OK"
            return 0
        fi

        sleep 1
    done

    echo "ERROR: $NAME no inicio correctamente"
    return 1
}
```

### 7. Levantar Config Server

```bash
cd "$PROY"

nohup java -jar config-server/target/config-server-1.0.0.jar \
    > /tmp/s6_config.log 2>&1 &

echo $! > /tmp/s6_config.pid
wait_url "http://localhost:8888/actuator/health" "Config Server"
```

Resultado esperado: `Config Server OK`.

### 8. Levantar Eureka Server

```bash
nohup java -jar eureka-server/target/eureka-server-1.0.0.jar \
    > /tmp/s6_eureka.log 2>&1 &

echo $! > /tmp/s6_eureka.pid
wait_url "http://localhost:8761/actuator/health" "Eureka Server"
```

Resultado esperado: `Eureka Server OK`.

### 9. Levantar Core Banking

```bash
nohup java -jar core-banking-service/target/core-banking-service-1.0.0.jar \
    > /tmp/s6_core.log 2>&1 &

echo $! > /tmp/s6_core.pid
wait_url "http://localhost:8081/actuator/health" "Core Banking"
```

Resultado esperado: `Core Banking OK`.

### 10. Ejecutar nuevamente los Jobs Batch

```bash
cd "$PROY"

nohup java -jar core-banking-service/target/core-banking-service-1.0.0.jar \
  --server.port=0 \
  --eureka.client.enabled=false \
  --spring.batch.job.enabled=true \
  --spring.batch.job.name=dailyTransactionsJob \
  ejecucion=601,java.lang.Long,true \
  > /tmp/s6_batch_daily.log 2>&1 &

DAILY_PID=$!

echo "PID dailyTransactionsJob: $DAILY_PID"

for i in {1..60}
do
    if grep -qE "dailyTransactionsJob.*(estado=COMPLETED|status: \[COMPLETED\])" \
        /tmp/s6_batch_daily.log 2>/dev/null
    then
        echo "dailyTransactionsJob COMPLETED"
        break
    fi

    if grep -qE "dailyTransactionsJob.*(estado=FAILED|status: \[FAILED\])|APPLICATION FAILED TO START" \
        /tmp/s6_batch_daily.log 2>/dev/null
    then
        echo "ERROR: dailyTransactionsJob FAILED"
        tail -40 /tmp/s6_batch_daily.log
        break
    fi

    if [ "$i" -eq 60 ]
    then
        echo "ERROR: timeout esperando dailyTransactionsJob"
        tail -40 /tmp/s6_batch_daily.log
    fi

    sleep 2
done

grep -E "dailyTransactionsJob|COMPLETED|FAILED" /tmp/s6_batch_daily.log | tail -20

kill "$DAILY_PID" 2>/dev/null || true
wait "$DAILY_PID" 2>/dev/null || true

nohup java -jar core-banking-service/target/core-banking-service-1.0.0.jar \
  --server.port=0 \
  --eureka.client.enabled=false \
  --spring.batch.job.enabled=true \
  --spring.batch.job.name=monthlyInterestJob \
  ejecucion=602,java.lang.Long,true \
  > /tmp/s6_batch_monthly.log 2>&1 &

MONTHLY_PID=$!

echo "PID monthlyInterestJob: $MONTHLY_PID"

for i in {1..60}
do
    if grep -qE "monthlyInterestJob.*(estado=COMPLETED|status: \[COMPLETED\])" \
        /tmp/s6_batch_monthly.log 2>/dev/null
    then
        echo "monthlyInterestJob COMPLETED"
        break
    fi

    if grep -qE "monthlyInterestJob.*(estado=FAILED|status: \[FAILED\])|APPLICATION FAILED TO START" \
        /tmp/s6_batch_monthly.log 2>/dev/null
    then
        echo "ERROR: monthlyInterestJob FAILED"
        tail -40 /tmp/s6_batch_monthly.log
        break
    fi

    if [ "$i" -eq 60 ]
    then
        echo "ERROR: timeout esperando monthlyInterestJob"
        tail -40 /tmp/s6_batch_monthly.log
    fi

    sleep 2
done

grep -E "monthlyInterestJob|COMPLETED|FAILED" /tmp/s6_batch_monthly.log | tail -20

kill "$MONTHLY_PID" 2>/dev/null || true
wait "$MONTHLY_PID" 2>/dev/null || true

nohup java -jar core-banking-service/target/core-banking-service-1.0.0.jar \
  --server.port=0 \
  --eureka.client.enabled=false \
  --spring.batch.job.enabled=true \
  --spring.batch.job.name=annualStatementsJob \
  ejecucion=603,java.lang.Long,true \
  > /tmp/s6_batch_annual.log 2>&1 &

ANNUAL_PID=$!

echo "PID annualStatementsJob: $ANNUAL_PID"

for i in {1..60}
do
    if grep -qE "annualStatementsJob.*(estado=COMPLETED|status: \[COMPLETED\])" \
        /tmp/s6_batch_annual.log 2>/dev/null
    then
        echo "annualStatementsJob COMPLETED"
        break
    fi

    if grep -qE "annualStatementsJob.*(estado=FAILED|status: \[FAILED\])|APPLICATION FAILED TO START" \
        /tmp/s6_batch_annual.log 2>/dev/null
    then
        echo "ERROR: annualStatementsJob FAILED"
        tail -40 /tmp/s6_batch_annual.log
        break
    fi

    if [ "$i" -eq 60 ]
    then
        echo "ERROR: timeout esperando annualStatementsJob"
        tail -40 /tmp/s6_batch_annual.log
    fi

    sleep 2
done

grep -E "annualStatementsJob|COMPLETED|FAILED" /tmp/s6_batch_annual.log | tail -20

kill "$ANNUAL_PID" 2>/dev/null || true
wait "$ANNUAL_PID" 2>/dev/null || true
```

Cada Job Batch se ejecuta mediante una instancia temporal del `core-banking-service` con un puerto aleatorio. El procedimiento espera explicitamente que el log informe `COMPLETED` o `FAILED`, con un tiempo maximo de espera de 120 segundos. Una vez obtenido el resultado, la instancia temporal se detiene automaticamente.

Se utiliza `--server.port=0` para mantener el contexto web requerido por la configuracion de seguridad sin entrar en conflicto con el Core Banking principal que utiliza el puerto 8081. Adicionalmente, `--eureka.client.enabled=false` evita registrar estas instancias temporales en Eureka.

### 11. Comprobar datos generados por los Jobs Batch

```bash
docker exec -i banco-xyz-postgres \
  psql -U banco_user -d banco_xyz \
  -c "
SELECT 'transacciones_procesadas' AS tabla, COUNT(*) AS registros
FROM transacciones_procesadas
UNION ALL
SELECT 'intereses_mensuales', COUNT(*)
FROM intereses_mensuales
UNION ALL
SELECT 'movimientos_anuales', COUNT(*)
FROM movimientos_anuales;
"

docker exec -i banco-xyz-postgres \
  psql -U banco_user -d banco_xyz \
  -c "
SELECT cuenta_id,nombre,tipo,saldo_inicial,tasa,saldo_final
FROM intereses_mensuales
WHERE cuenta_id=104;
"

docker exec -i banco-xyz-postgres \
  psql -U banco_user -d banco_xyz \
  -c "
SELECT COUNT(*) AS movimientos_cuenta_104
FROM movimientos_anuales
WHERE cuenta_id=104;
"
```

Estas consultas son solamente `SELECT` y no modifican ningun dato.

### 12. Levantar BFF Web

```bash
cd "$PROY"

nohup java -jar bff-web-service/target/bff-web-service-1.0.0.jar \
    > /tmp/s6_web.log 2>&1 &

echo $! > /tmp/s6_web.pid
wait_url "http://localhost:8082/actuator/health" "BFF Web"
```

### 13. Levantar BFF Mobile

```bash
nohup java -jar bff-mobile-service/target/bff-mobile-service-1.0.0.jar \
    > /tmp/s6_mobile.log 2>&1 &

echo $! > /tmp/s6_mobile.pid
wait_url "http://localhost:8083/actuator/health" "BFF Mobile"
```

### 14. Levantar BFF ATM

```bash
nohup java -jar bff-atm-service/target/bff-atm-service-1.0.0.jar \
    > /tmp/s6_atm.log 2>&1 &

echo $! > /tmp/s6_atm.pid
wait_url "http://localhost:8084/actuator/health" "BFF ATM"
```

### 15. Esperar registro en Eureka, evidencias de infraestructura y JWT

```bash
for i in {1..60}; do
    APPS=$(
        curl -sS \
            -H "Accept: application/xml" \
            http://localhost:8761/eureka/apps
    )

    if echo "$APPS" | grep -q '<name>CORE-BANKING-SERVICE</name>' \
        && echo "$APPS" | grep -q '<name>BFF-WEB-SERVICE</name>' \
        && echo "$APPS" | grep -q '<name>BFF-MOBILE-SERVICE</name>' \
        && echo "$APPS" | grep -q '<name>BFF-ATM-SERVICE</name>'; then
        echo "Los cuatro servicios estan registrados en Eureka."
        break
    fi

    echo "Esperando registro completo en Eureka..."
    sleep 2
done

curl -sS \
    -H "Accept: application/xml" \
    http://localhost:8761/eureka/apps \
    | grep -E '<name>|<status>|<port'

{
    echo "Comando: curl http://localhost:8888/core-banking-service/default"
    echo
    curl -sS http://localhost:8888/core-banking-service/default
    echo
} > "$EVID/01_config_server.txt"

{
    echo "Comando: curl -i http://localhost:8081/api/config/origin"
    echo
    curl -sS -i http://localhost:8081/api/config/origin
    echo
} > "$EVID/01b_consumo_config_server.txt"

{
    echo "Comando: curl -i http://localhost:8761/actuator/health"
    echo
    curl -sS -i http://localhost:8761/actuator/health
    echo
} > "$EVID/02_eureka_server.txt"

curl -sS \
    -H "Accept: application/xml" \
    http://localhost:8761/eureka/apps > "$EVID/03_servicios_registrados.xml"

{
    echo "Comando: curl -H 'Accept: application/xml' http://localhost:8761/eureka/apps"
    echo
    cat "$EVID/03_servicios_registrados.xml"
} > "$EVID/03_servicios_registrados.txt"

curl -sS \
    -D /tmp/s6_atm_login_headers \
    -o /tmp/s6_atm_login_body \
    -X POST \
    -H "Content-Type: application/json" \
    -d '{"username":"atmuser","password":"atm123"}' \
    http://localhost:8084/auth/login

ATM_TOKEN=$(python3 -c 'import json; print(json.load(open("/tmp/s6_atm_login_body"))["accessToken"])')

WEB_TOKEN=$(
    curl -sS \
        -X POST \
        -H "Content-Type: application/json" \
        -d '{"username":"webuser","password":"web123"}' \
        http://localhost:8082/auth/login \
        | python3 -c 'import json,sys; print(json.load(sys.stdin)["accessToken"])'
)

MOBILE_TOKEN=$(
    curl -sS \
        -X POST \
        -H "Content-Type: application/json" \
        -d '{"username":"mobileuser","password":"mobile123"}' \
        http://localhost:8083/auth/login \
        | python3 -c 'import json,sys; print(json.load(sys.stdin)["accessToken"])'
)

echo "WEB: ${#WEB_TOKEN}"
echo "MOBILE: ${#MOBILE_TOKEN}"
echo "ATM: ${#ATM_TOKEN}"
```

### Evidencias funcionales, seguridad y ATM

```bash
{
    echo "Usuario: atmuser"
    echo "Endpoint: POST /auth/login"
    echo "HTTP: $(head -n 1 /tmp/s6_atm_login_headers | tr -d '\r' | awk '{print $2}')"
    echo "Comando: curl -X POST -H 'Content-Type: application/json' -d '{\"username\":\"atmuser\",\"password\":\"atm123\"}' http://localhost:8084/auth/login"
    echo
    echo "Body real (JWT oculto):"
    python3 -c 'import json; d=json.load(open("/tmp/s6_atm_login_body")); d["accessToken"]="[JWT emitido]"; print(json.dumps(d,ensure_ascii=False))'
} > "$EVID/04_jwt_login.txt"

{
    echo "Usuario: webuser / ROLE_WEB"
    echo "Endpoint: GET /api/bff/web/accounts/104"
    echo
    curl -sS -i \
        -H "Authorization: Bearer $WEB_TOKEN" \
        http://localhost:8082/api/bff/web/accounts/104
} > "$EVID/05_web_200.txt"

{
    echo "Usuario: mobileuser / ROLE_MOBILE"
    echo "Endpoint: GET /api/bff/mobile/accounts/104"
    echo
    curl -sS -i \
        -H "Authorization: Bearer $MOBILE_TOKEN" \
        http://localhost:8083/api/bff/mobile/accounts/104
} > "$EVID/06_mobile_200.txt"

{
    echo "Usuario: atmuser / ROLE_ATM"
    echo "Endpoint: GET /api/bff/atm/accounts/104/balance"
    echo
    curl -sS -i \
        -H "Authorization: Bearer $ATM_TOKEN" \
        http://localhost:8084/api/bff/atm/accounts/104/balance
} > "$EVID/07_atm_200.txt"

{
    echo "Prueba: acceso Web sin JWT"
    echo
    curl -sS -i http://localhost:8082/api/bff/web/accounts/104
} > "$EVID/08_sin_token_401.txt"

{
    echo "Usuario autenticado: webuser / ROLE_WEB"
    echo "Endpoint solicitado: BFF Mobile"
    echo
    curl -sS -i \
        -H "Authorization: Bearer $WEB_TOKEN" \
        http://localhost:8083/api/bff/mobile/accounts/104
} > "$EVID/09_rol_incorrecto_403.txt"

{
    echo "Usuario: mobileuser / ROLE_MOBILE"
    echo "Endpoint: GET /api/bff/mobile/accounts/104/transactions"
    echo
    curl -sS -i \
        -H "Authorization: Bearer $MOBILE_TOKEN" \
        http://localhost:8083/api/bff/mobile/accounts/104/transactions
} > "$EVID/10_mobile_ultimos_5.txt"

curl -sS \
    -H "Authorization: Bearer $MOBILE_TOKEN" \
    http://localhost:8083/api/bff/mobile/accounts/104/transactions \
    | python3 -c 'import json,sys; d=json.load(sys.stdin); print("Cantidad de movimientos:",len(d))'

{
    echo "Cuenta: 104"
    echo "Usuario: atmuser"
    echo
    curl -sS -i \
        -H "Authorization: Bearer $ATM_TOKEN" \
        http://localhost:8084/api/bff/atm/accounts/104/balance
} > "$EVID/11_atm_saldo_inicial.txt"

{
    echo "Cuenta: 104"
    echo "Monto: 10000"
    echo "Usuario: atmuser"
    echo
    curl -sS -i -X POST \
        -H "Authorization: Bearer $ATM_TOKEN" \
        -H "Content-Type: application/json" \
        -d '{"amount":10000}' \
        http://localhost:8084/api/bff/atm/accounts/104/withdraw
} > "$EVID/12_atm_retiro.txt"

{
    echo "Cuenta: 104"
    echo "Saldo final por BFF y verificacion PostgreSQL:"
    echo
    curl -sS -i \
        -H "Authorization: Bearer $ATM_TOKEN" \
        http://localhost:8084/api/bff/atm/accounts/104/balance
    echo
    echo "CONSULTA POSTGRESQL:"
    echo "SQL: SELECT cuenta_id,saldo_final FROM intereses_mensuales WHERE cuenta_id=104;"
    docker exec -i banco-xyz-postgres \
        psql -U banco_user -d banco_xyz \
        -c "SELECT cuenta_id,saldo_final FROM intereses_mensuales WHERE cuenta_id=104;"
} > "$EVID/13_atm_saldo_final.txt"
```

El escenario esperado es saldo inicial `10200.00`, retiro `10000`, saldo final y PostgreSQL `200.00`.

### Circuit Breaker, recuperación y validación

```bash
kill "$(cat /tmp/s6_core.pid)"

for i in {1..30}; do
    if ! ss -ltn | grep -q ':8081 '; then
        echo "Core Banking detenido."
        break
    fi
    sleep 1
done

ss -ltnp | grep ':8081' || echo "Puerto 8081 libre - Core detenido correctamente"

{
    echo "Core Banking detenido."
    echo
    curl -sS -i \
        -H "Authorization: Bearer $WEB_TOKEN" \
        http://localhost:8082/api/bff/web/accounts/104
} > "$EVID/14_circuit_breaker_web.txt"

{
    echo "Core Banking detenido."
    echo
    curl -sS -i \
        -H "Authorization: Bearer $MOBILE_TOKEN" \
        http://localhost:8083/api/bff/mobile/accounts/104
} > "$EVID/15_circuit_breaker_mobile.txt"

{
    echo "Core Banking detenido."
    echo
    curl -sS -i \
        -H "Authorization: Bearer $ATM_TOKEN" \
        http://localhost:8084/api/bff/atm/accounts/104/balance
} > "$EVID/16_circuit_breaker_atm.txt"

nohup java -jar core-banking-service/target/core-banking-service-1.0.0.jar \
    > /tmp/s6_core.log 2>&1 &

echo $! > /tmp/s6_core.pid
wait_url "http://localhost:8081/actuator/health" "Core Banking recuperado"

for i in {1..60}; do
    if curl -fsS \
        -H "Accept: application/xml" \
        http://localhost:8761/eureka/apps/CORE-BANKING-SERVICE \
        | grep -q '<status>UP</status>'; then
        echo "CORE-BANKING-SERVICE nuevamente UP en Eureka."
        break
    fi
    sleep 2
done

RECOVERY_CODE=""

for i in {1..30}; do
    RECOVERY_CODE=$(
        curl -sS \
            -o /tmp/s6_recovery_body \
            -w "%{http_code}" \
            -H "Authorization: Bearer $WEB_TOKEN" \
            http://localhost:8082/api/bff/web/accounts/104
    )

    echo "Intento $i -> HTTP $RECOVERY_CODE"

    [ "$RECOVERY_CODE" = "200" ] && {
        echo "BFF Web recuperado correctamente."
        break
    }

    sleep 2
done

{
    echo "Core Banking reiniciado."
    echo
    echo "CORE-BANKING-SERVICE EN EUREKA:"
    curl -sS \
        -H "Accept: application/xml" \
        http://localhost:8761/eureka/apps/CORE-BANKING-SERVICE
    echo
    echo "LLAMADA WEB DESPUES DE LA RECUPERACION:"
    curl -sS -i \
        -H "Authorization: Bearer $WEB_TOKEN" \
        http://localhost:8082/api/bff/web/accounts/104
} > "$EVID/17_recuperacion_core.txt"

{
    echo "Puertos y procesos activos durante la prueba:"
    echo
    ss -ltnp | grep -E ':(8761|8888|8081|8082|8083|8084)\b'
} > "$EVID/19_servicios_activos.txt"

echo "=== ARCHIVOS DE EVIDENCIA ==="
find "$EVID" -maxdepth 1 -type f -printf '%f\n' | sort

echo
echo "=== RESULTADOS HTTP ==="
grep -H "HTTP/1.1" "$EVID"/*.txt

echo
echo "=== RESULTADO ATM ==="
grep -E '"balance"|"withdrawnAmount"|"finalBalance"|200.00' \
    "$EVID/11_atm_saldo_inicial.txt" \
    "$EVID/12_atm_retiro.txt" \
    "$EVID/13_atm_saldo_final.txt"

echo
echo "=== BUILD ==="
grep -E "Reactor Summary|BUILD SUCCESS|BUILD FAILURE" \
    "$EVID/18_build.txt"

echo
echo "=== EUREKA ==="
grep -E '<name>|<status>|<port' \
     "$EVID/03_servicios_registrados.xml"
```

Los resultados esperados son operaciones `200`, ausencia de token `401`, rol incorrecto `403`, los tres fallbacks `503` y nuevamente `200` tras recuperación. Los archivos esperados son `01_config_server.txt`, `01b_consumo_config_server.txt`, `02_eureka_server.txt`, `03_servicios_registrados.txt`, `03_servicios_registrados.xml`, `04_jwt_login.txt`, `05_web_200.txt`, `06_mobile_200.txt`, `07_atm_200.txt`, `08_sin_token_401.txt`, `09_rol_incorrecto_403.txt`, `10_mobile_ultimos_5.txt`, `11_atm_saldo_inicial.txt`, `12_atm_retiro.txt`, `13_atm_saldo_final.txt`, `14_circuit_breaker_web.txt`, `15_circuit_breaker_mobile.txt`, `16_circuit_breaker_atm.txt`, `17_recuperacion_core.txt`, `18_build.txt` y `19_servicios_activos.txt`.

### Ejecución alternativa completa mediante Docker Compose

Esta es una alternativa para facilitar la reproducción completa de la arquitectura. No se ejecuta después de la Opción A, no reemplaza el procedimiento detallado usado para generar las evidencias y no genera evidencias nuevas.

```bash
cd "/home/francisco/proyecto/BACKEND III/SEMANA 6/Exp3_S6_Grupo11/proyecto"

docker compose down -v --remove-orphans
docker compose build
docker compose up -d

docker compose ps -a
```

El resultado esperado es PostgreSQL, Config Server, Eureka, Core Banking y los tres BFF en ejecución saludable; `batch-init` debe finalizar con `Exited (0)`. La representación exacta puede variar según la versión de Docker Compose, pero el estado funcional debe ser equivalente.

```bash
curl -sS http://localhost:8888/actuator/health
curl -sS http://localhost:8761/actuator/health
curl -sS http://localhost:8081/actuator/health
curl -sS http://localhost:8082/actuator/health
curl -sS http://localhost:8083/actuator/health
curl -sS http://localhost:8084/actuator/health

docker compose logs batch-init
```

El comando `docker compose logs batch-init` permite revisar el resultado de los tres Jobs y observar los estados `COMPLETED` impresos por el script.

Para detener la Opción B usar `docker compose down`. Para eliminar también PostgreSQL usar `docker compose down -v --remove-orphans`.

# Semana 07 - Tolerancia a fallos y arquitectura orientada a eventos

## Objetivo y arquitectura

Semana 07 evoluciona el retiro ATM: el endpoint oficial `POST /api/bff/atm/accounts/{id}/withdraw` deja de ejecutar una operacion sincronica y pasa a publicar `WithdrawalRequested` en Kafka. El BFF responde `202 Accepted` con `transactionId` y `PENDING`; Core Banking procesa, persiste y publica `WithdrawalCompleted` o `WithdrawalRejected`. La ruta `/withdraw/async` y el retiro HTTP sincronico hacia Core ya no existen. Se mantiene Spring Cloud Config, Eureka, LoadBalancer, JWT por canal, PostgreSQL, Spring Batch y los Circuit Breakers de Resilience4j.

El flujo usa arquitectura Event-Driven con Kafka porque permite aceptar la solicitud ATM rapidamente y procesarla de forma desacoplada. No se implementa Saga: el retiro solo requiere una validacion y una actualizacion dentro del Core Banking.

El BFF ATM produce `WithdrawalRequested` y usa `accountId` como Kafka key. Asi, los eventos de una misma cuenta conservan orden dentro de una particion. Core Banking consume con el grupo `core-banking-withdrawal-group`, configurado con concurrencia 3. Los tres topics tienen 3 particiones y factor de replicacion 1.

- `bank.withdrawal.requested`: BFF ATM -> Core Banking.
- `bank.withdrawal.completed`: resultado exitoso publicado por Core Banking.
- `bank.withdrawal.rejected`: resultado rechazado publicado por Core Banking.

Los eventos compartidos viven en el modulo Maven `shared-events`; contiene solo los contratos de los tres eventos y los nombres de topics. Esta decision evita duplicar DTOs entre BFF y Core sin agregar un servicio nuevo.

Cada evento contiene `eventId`, `transactionId`, `accountId`, `amount` y `occurredAt`. Los eventos de resultado agregan solamente saldo final o motivo de rechazo cuando corresponde.

## Selección y justificación de arquitectura y patrón

Para Semana 07 se selecciona una arquitectura Event-Driven utilizando Apache Kafka como plataforma de mensajería asíncrona. El retiro ATM desacopla la solicitud recibida por BFF ATM del procesamiento realizado por Core Banking: se publica `WithdrawalRequested` y, una vez procesado, se emite `WithdrawalCompleted` o `WithdrawalRejected`.

Kafka fue la alternativa seleccionada frente a JMS para este caso de uso porque la actividad requiere trabajar explícitamente con eventos, topics, particiones y consumer groups. Sus tres particiones y la concurrencia 3 del grupo `core-banking-withdrawal-group` permiten demostrar procesamiento distribuido y escalabilidad. Esta elección no descarta JMS; Kafka se ajusta mejor a la demostración práctica de arquitectura orientada a eventos requerida en esta semana.

El patrón Event Sourcing se aplica de forma acotada exclusivamente al flujo de retiro ATM. La tabla `banking_events` conserva un historial inmutable para trazabilidad y auditoría de las secuencias `WithdrawalRequested` → `WithdrawalCompleted` y `WithdrawalRequested` → `WithdrawalRejected`. Banco XYZ no se migra por completo a Event Sourcing: el saldo operacional heredado continúa como proyección del estado actual.

Saga fue evaluado, pero no se implementa porque el retiro actual no coordina una transacción distribuida entre múltiples microservicios ni requiere acciones compensatorias entre servicios independientes. Las consultas que necesitan una respuesta inmediata pueden continuar por HTTP síncrono; Resilience4j protege esas comunicaciones entre los BFF y Core Banking donde corresponde.

## Estructura incorporada en Semana 07

La estructura relevante para comprender el flujo de retiro asíncrono es la siguiente:

```text
proyecto/
├── shared-events/          Contratos WithdrawalRequested, WithdrawalCompleted,
│                           WithdrawalRejected y nombres de topics
├── bff-atm-service/        Recibe el retiro ATM, lo publica y responde 202/PENDING
├── core-banking-service/   Consume, procesa, registra eventos e idempotencia
├── bff-web-service/        Consultas síncronas del canal Web
├── bff-mobile-service/     Consultas síncronas del canal Mobile
├── config-server/          Configuración centralizada
├── eureka-server/          Descubrimiento de servicios
└── docker-compose.yml      PostgreSQL, Kafka, Config Server, Eureka, Core y BFF
```

Core Banking utiliza `banking_events` como Event Store, `processed_events` para evitar reprocesos y `withdrawal_transactions` para consultar el estado de cada retiro. Docker Compose integra PostgreSQL, Kafka, Config Server, Eureka, Core Banking y los tres BFF de la solución.

## Diagrama de arquitectura

El diagrama de arquitectura correspondiente a Semana 07 es elaborado utilizando Archify. El diagrama representará BFF ATM, Core Banking, PostgreSQL, Kafka y sus tres topics; los eventos `WithdrawalRequested`, `WithdrawalCompleted` y `WithdrawalRejected`; el productor, consumidor, consumer group, tres particiones, Event Store, idempotencia y Resilience4j en las comunicaciones HTTP síncronas.

## Event Store e idempotencia

El alcance de Event Sourcing es acotado al flujo nuevo. `banking_events` almacena de forma inmutable los eventos `WithdrawalRequested`, `WithdrawalCompleted` o `WithdrawalRejected`. `intereses_mensuales.saldo_final` sigue siendo la proyeccion operacional heredada.

`processed_events` registra cada `eventId` procesado. Si Kafka reentrega el mismo evento, Core Banking lo ignora antes de descontar saldo. `withdrawal_transactions` conserva `PENDING`, `COMPLETED` o `REJECTED` y permite consultar el resultado. La persistencia, la validacion y la actualizacion del saldo se ejecutan dentro de una transaccion JDBC.

## Endpoints nuevos

El rol `ATM` y JWT se mantienen obligatorios.

```text
POST /api/bff/atm/accounts/{accountId}/withdraw
GET  /api/bff/atm/accounts/transactions/{transactionId}
```

La solicitud responde `202 Accepted` con `transactionId` y `PENDING`. El endpoint de consulta retorna el estado final cuando Core procesa el evento. No existe una variante `/withdraw/async` ni una llamada HTTP sincronica de retiro hacia Core.

## Ejecucion

### Paso 00 - Preparar entorno limpio

Este paso no genera una evidencia numerada. Debe ejecutarse desde el directorio indicado: `docker compose down -v --remove-orphans` afecta únicamente los recursos asociados a este Compose y elimina los datos locales persistidos por Docker Compose para este proyecto. Úselo solo cuando se desea reproducir las evidencias desde cero: elimina la base PostgreSQL anterior y, si Compose llegara a persistir Kafka en volúmenes del proyecto, también esa persistencia.

La preparación contempla elimina únicamente archivos `.txt` de evidencias de Semana 07, mediante el comando `rm`.

> **Solo si Docker Desktop/WSL presenta error con `docker-credential-desktop.exe`:** antes de ejecutar comandos Docker en la terminal, usar la configuración temporal siguiente. Es una solución local de entorno; no modifica Docker Compose ni la configuración global de Docker.
>
> ```bash
> mkdir -p /tmp/docker-config-s7
> printf '{}\n' > /tmp/docker-config-s7/config.json
> export DOCKER_CONFIG=/tmp/docker-config-s7
> docker info >/dev/null && echo "Docker OK"
> ```

Resultado esperado: `Docker OK`.

```bash
cd "/home/francisco/proyecto/BACKEND III/SEMANA 7/Exp3_S7_Grupo11/proyecto"

EVID="/home/francisco/proyecto/BACKEND III/SEMANA 7/Exp3_S7_Grupo11/evidencias/semana 07"
mkdir -p "$EVID"

set -o pipefail

docker compose down -v --remove-orphans
rm -f "$EVID"/*.txt

docker compose up -d postgres kafka

wait_url() {
    local url="$1"
    local nombre="$2"

    for i in {1..60}; do
        if curl -fsS "$url" > /dev/null; then
            echo "$nombre disponible."
            return 0
        fi
        sleep 2
    done

    echo "ERROR: $nombre no estuvo disponible a tiempo." >&2
    return 1
}

POSTGRES_OK=0
for i in {1..30}; do
    if docker compose exec -T postgres \
        pg_isready -U banco_user -d banco_xyz; then
        echo "PostgreSQL disponible."
        POSTGRES_OK=1
        break
    fi
    sleep 2

done

if [ "$POSTGRES_OK" -eq 0 ]; then
    echo "ERROR: PostgreSQL no estuvo disponible a tiempo."
fi

KAFKA_OK=0
for i in {1..30}; do
    if docker compose exec -T kafka \
        /opt/kafka/bin/kafka-topics.sh \
        --bootstrap-server localhost:9092 \
        --list > /dev/null; then
        echo "Kafka disponible."
        KAFKA_OK=1
        break
    fi
    sleep 2

done

if [ "$KAFKA_OK" -eq 0 ]; then
    echo "ERROR: Kafka no estuvo disponible a tiempo."
fi

docker compose ps
```

Después del levantamiento completo del Paso 03, `batch-init` ejecuta los Jobs reales `dailyTransactionsJob`, `monthlyInterestJob` y `annualStatementsJob`. Esos Jobs crean el esquema definido por `schema.sql`, las tablas Batch y los datos de `intereses_mensuales`, incluidos los datos deterministas de la cuenta 104. Las tablas `banking_events`, `processed_events` y `withdrawal_transactions` también provienen de `schema.sql` y estarán vacías antes de las operaciones de los pasos 11 y 13.

### Paso 01 - Build del proyecto

Compila los ocho módulos, ejecuta `verify` y deja el resumen Maven completo en `01_build.txt`.

```bash
{
    echo "=== BUILD SEMANA 07 ==="
    echo "Comando: ./mvnw clean verify"
    echo
    ./mvnw clean verify
} 2>&1 | tee "$EVID/01_build.txt"
```

Resultado esperado: `BUILD SUCCESS`.

### Paso 02 - Tests

Ejecuta el objetivo Maven de pruebas sin una segunda limpieza del árbol ya construido y guarda el detalle de clases y resultados.

```bash
{
    echo "=== TESTS SEMANA 07 ==="
    echo "Comando: ./mvnw test"
    echo
    ./mvnw test
} 2>&1 | tee "$EVID/02_tests.txt"
```

Resultado esperado: tests sin fallos ni errores.

### Paso 03 - Levantamiento completo con Docker Compose

Construye las imágenes actuales, inicia todos los servicios y espera sus healthchecks HTTP. `batch-init` finaliza con `Exited (0)` porque es un inicializador, no un servicio permanente.

```bash
docker compose up -d --build

wait_url "http://localhost:8888/actuator/health" "Config Server"
wait_url "http://localhost:8761/actuator/health" "Eureka"
wait_url "http://localhost:8081/actuator/health" "Core Banking"
wait_url "http://localhost:8082/actuator/health" "BFF Web"
wait_url "http://localhost:8083/actuator/health" "BFF Mobile"
wait_url "http://localhost:8084/actuator/health" "BFF ATM"

{
    echo "=== SERVICIOS DOCKER COMPOSE ==="
    docker compose ps -a
    echo
    echo "=== BATCH INIT ==="
    docker compose logs --no-color batch-init
} 2>&1 | tee "$EVID/03_docker_servicios.txt"
```

Resultado esperado: `postgres` y `kafka` saludables, todos los servicios HTTP saludables y `batch-init` terminado correctamente.

### Paso 04 - Config Server

Consulta la configuración real de `core-banking-service` servida desde `./config-repo`.

```bash
{
    echo "=== CONFIG SERVER ==="
    echo "GET /core-banking-service/default"
    echo
    curl -sS -i http://localhost:8888/core-banking-service/default
} | tee "$EVID/04_config_server.txt"
```

Resultado esperado: HTTP `200` y `propertySources` con `file:config-repo/core-banking-service.yml`.

### Paso 05 - Servicios registrados en Eureka

Espera hasta que los cuatro microservicios de negocio estén registrados y luego registra la respuesta XML.

```bash
for i in {1..60}; do
    APPS=$(curl -sS -H "Accept: application/xml" http://localhost:8761/eureka/apps)

    if echo "$APPS" | grep -q '<name>CORE-BANKING-SERVICE</name>' \
        && echo "$APPS" | grep -q '<name>BFF-WEB-SERVICE</name>' \
        && echo "$APPS" | grep -q '<name>BFF-MOBILE-SERVICE</name>' \
        && echo "$APPS" | grep -q '<name>BFF-ATM-SERVICE</name>'; then
        echo "Los cuatro servicios están UP en Eureka."
        break
    fi

    if [ "$i" -eq 60 ]; then
        echo "ERROR: registro incompleto en Eureka." >&2
        break
    fi

    sleep 2
done

{
    echo "=== EUREKA: SERVICIOS REGISTRADOS ==="
    curl -sS \
        -H "Accept: application/xml" \
        http://localhost:8761/eureka/apps \
        | grep -E '<name>|<status>|<port'
} | tee "$EVID/05_eureka_servicios.txt"
```

Resultado esperado: `CORE-BANKING-SERVICE`, `BFF-WEB-SERVICE`, `BFF-MOBILE-SERVICE` y `BFF-ATM-SERVICE` con estado `UP`.

### Paso 06 - Topics Kafka

Lista los topics reales desde el contenedor Kafka.

```bash
{
    echo "=== TOPICS KAFKA ==="
    docker compose exec -T kafka \
        /opt/kafka/bin/kafka-topics.sh \
        --bootstrap-server localhost:9092 \
        --list
} | tee "$EVID/06_kafka_topics.txt"
```

Resultado esperado: `bank.withdrawal.requested`, `bank.withdrawal.completed` y `bank.withdrawal.rejected`.

### Paso 07 - Particiones Kafka

Describe el topic de solicitudes para comprobar sus tres particiones.

```bash
{
    echo "=== PARTICIONES KAFKA ==="
    docker compose exec -T kafka \
        /opt/kafka/bin/kafka-topics.sh \
        --bootstrap-server localhost:9092 \
        --describe \
        --topic bank.withdrawal.requested
} | tee "$EVID/07_kafka_particiones.txt"
```

Resultado esperado: `PartitionCount: 3` y particiones `0`, `1` y `2`.

### Paso 08 - Consumer group Kafka

Muestra las asignaciones, offsets, lag y consumidores del grupo real de Core Banking.

```bash
{
    echo "=== CONSUMER GROUP KAFKA ==="
    docker compose exec -T kafka \
        /opt/kafka/bin/kafka-consumer-groups.sh \
        --bootstrap-server localhost:9092 \
        --describe \
        --group core-banking-withdrawal-group
} | tee "$EVID/08_kafka_consumer_group.txt"
```

Resultado esperado: filas del grupo `core-banking-withdrawal-group`; inicialmente los offsets pueden estar vacíos hasta el primer retiro.

### Paso 09 - JWT de ATM y tokens de trabajo

Obtiene tokens nuevos durante esta ejecución. El JWT completo se mantiene solo en variables de memoria; la evidencia muestra una versión enmascarada. También se crean `WEB_TOKEN` y `MOBILE_TOKEN` para los pasos posteriores sin generar evidencias extra.

```bash
ATM_LOGIN=$(curl -sS \
    -X POST \
    -H "Content-Type: application/json" \
    -d '{"username":"atmuser","password":"atm123"}' \
    http://localhost:8084/auth/login)

ATM_TOKEN=$(printf '%s' "$ATM_LOGIN" | python3 -c \
    'import json,sys; print(json.load(sys.stdin)["accessToken"])')

WEB_LOGIN=$(curl -sS \
    -X POST \
    -H "Content-Type: application/json" \
    -d '{"username":"webuser","password":"web123"}' \
    http://localhost:8082/auth/login)

WEB_TOKEN=$(printf '%s' "$WEB_LOGIN" | python3 -c \
    'import json,sys; print(json.load(sys.stdin)["accessToken"])')

MOBILE_LOGIN=$(curl -sS \
    -X POST \
    -H "Content-Type: application/json" \
    -d '{"username":"mobileuser","password":"mobile123"}' \
    http://localhost:8083/auth/login)

MOBILE_TOKEN=$(printf '%s' "$MOBILE_LOGIN" | python3 -c \
    'import json,sys; print(json.load(sys.stdin)["accessToken"])')

{
    echo "=== JWT ATM ==="
    echo "POST /auth/login con atmuser"
    printf '%s' "$ATM_LOGIN" | python3 -c '
import json,sys
item=json.load(sys.stdin)
token=item["accessToken"]
item["accessToken"]=token[:16] + "...[JWT oculto]"
print(json.dumps(item, ensure_ascii=False))
'
    echo "Longitudes de tokens en memoria: ATM=${#ATM_TOKEN}, WEB=${#WEB_TOKEN}, MOBILE=${#MOBILE_TOKEN}"
} | tee "$EVID/09_jwt_atm.txt"
```

Resultado esperado: rol `ATM`; no se imprime ni se hardcodea un JWT completo.

### Paso 10 - Saldo inicial

La cuenta estable de la base inicial es `104`. Se define `ACCOUNT_ID` para los pasos siguientes y se consulta su saldo mediante el BFF ATM protegido.

```bash
ACCOUNT_ID=104

{
    echo "=== SALDO INICIAL ==="
    echo "Cuenta: $ACCOUNT_ID"
    curl -sS -i \
        -H "Authorization: Bearer $ATM_TOKEN" \
        "http://localhost:8084/api/bff/atm/accounts/$ACCOUNT_ID/balance"
} | tee "$EVID/10_saldo_inicial.txt"
```

Resultado esperado: HTTP `200` y saldo inicial reproducible `10200.00`.

### Paso 11 - Retiro oficial exitoso

Publica un retiro por Kafka, extrae dinámicamente `TX_OK` y consulta el estado hasta que deja de estar `PENDING`. No se usa ningún UUID de una ejecución anterior.

```bash
curl -sS \
    -D /tmp/s7_withdraw_ok_headers \
    -o /tmp/s7_withdraw_ok_body \
    -X POST \
    -H "Authorization: Bearer $ATM_TOKEN" \
    -H "Content-Type: application/json" \
    -d '{"amount":10000}' \
    "http://localhost:8084/api/bff/atm/accounts/$ACCOUNT_ID/withdraw"

TX_OK=$(python3 -c \
    'import json; print(json.load(open("/tmp/s7_withdraw_ok_body"))["transactionId"])')

for i in {1..60}; do
    STATUS_OK=$(curl -fsS \
        -H "Authorization: Bearer $ATM_TOKEN" \
        "http://localhost:8084/api/bff/atm/accounts/transactions/$TX_OK" || true)

    if [ -n "$STATUS_OK" ] \
        && printf '%s' "$STATUS_OK" | python3 -c \
            'import json,sys; raise SystemExit(0 if json.load(sys.stdin)["status"] == "COMPLETED" else 1)'; then
        break
    fi

    if [ "$i" -eq 60 ]; then
        echo "ERROR: TX_OK no llegó a COMPLETED." >&2
        break
    fi

    sleep 2
done

{
    echo "=== RETIRO OFICIAL EXITOSO ==="
    cat /tmp/s7_withdraw_ok_headers
    cat /tmp/s7_withdraw_ok_body
    echo
    echo "TX_OK=$TX_OK"
    echo "Estado final:"
    printf '%s\n' "$STATUS_OK"
} | tee "$EVID/11_retiro_oficial_solicitud.txt"
```

Resultado esperado: solicitud HTTP `202`, estado inicial `PENDING` y estado final `COMPLETED`.

### Paso 12 - Logs Kafka del retiro exitoso

Filtra los logs reales del BFF ATM y Core Banking con el `transactionId` recién generado. Muestra productor, consumidor, partición/hilo y resultado del retiro.

```bash
{
    echo "=== LOGS KAFKA PARA TX_OK=$TX_OK ==="
    docker compose logs --no-color bff-atm-service core-banking-service \
        | grep -F "$TX_OK"
} | tee "$EVID/12_logs_kafka.txt"
```

Resultado esperado: líneas `WithdrawalRequested`, `[Kafka][Consumer]`, partición, hilo y confirmación de retiro completado.

### Paso 13 - Retiro rechazado

El primer retiro dejó el saldo de la cuenta 104 en `200.00`; por eso otro retiro de `10000` es determinísticamente rechazado. Se guarda `TX_REJECT` y se hace polling hasta `REJECTED`.

```bash
curl -sS \
    -D /tmp/s7_withdraw_reject_headers \
    -o /tmp/s7_withdraw_reject_body \
    -X POST \
    -H "Authorization: Bearer $ATM_TOKEN" \
    -H "Content-Type: application/json" \
    -d '{"amount":10000}' \
    "http://localhost:8084/api/bff/atm/accounts/$ACCOUNT_ID/withdraw"

TX_REJECT=$(python3 -c \
    'import json; print(json.load(open("/tmp/s7_withdraw_reject_body"))["transactionId"])')

for i in {1..60}; do
    STATUS_REJECT=$(curl -fsS \
        -H "Authorization: Bearer $ATM_TOKEN" \
        "http://localhost:8084/api/bff/atm/accounts/transactions/$TX_REJECT" || true)

    if [ -n "$STATUS_REJECT" ] \
        && printf '%s' "$STATUS_REJECT" | python3 -c \
            'import json,sys; raise SystemExit(0 if json.load(sys.stdin)["status"] == "REJECTED" else 1)'; then
        break
    fi

    if [ "$i" -eq 60 ]; then
        echo "ERROR: TX_REJECT no llegó a REJECTED." >&2
        break
    fi

    sleep 2
done

{
    echo "=== RETIRO RECHAZADO ==="
    cat /tmp/s7_withdraw_reject_headers
    cat /tmp/s7_withdraw_reject_body
    echo
    echo "TX_REJECT=$TX_REJECT"
    echo "Estado final:"
    printf '%s\n' "$STATUS_REJECT"
} | tee "$EVID/13_retiro_rechazado.txt"
```

Resultado esperado: HTTP `202`, estado final `REJECTED` y `rejectionReason` equivalente a `Saldo insuficiente`.

### Paso 14 - Saldo final

Comprueba que solo el retiro exitoso descontó saldo.

```bash
{
    echo "=== SALDO FINAL ==="
    echo "Cuenta: $ACCOUNT_ID"
    curl -sS -i \
        -H "Authorization: Bearer $ATM_TOKEN" \
        "http://localhost:8084/api/bff/atm/accounts/$ACCOUNT_ID/balance"
} | tee "$EVID/14_saldo_final.txt"
```

Resultado esperado: HTTP `200` y saldo `200.00`; el retiro rechazado no realiza un segundo descuento.

### Paso 15 - Event Store

Consulta PostgreSQL dentro del contenedor real y filtra exclusivamente las dos transacciones de esta ejecución.

```bash
{
    echo "=== BANKING EVENTS ==="
    docker compose exec -T postgres \
        psql -U banco_user -d banco_xyz \
        -c "
SELECT event_type, transaction_id, account_id, amount, occurred_at
FROM banking_events
WHERE transaction_id IN ('$TX_OK', '$TX_REJECT')
ORDER BY occurred_at;"

    echo
    echo "=== WITHDRAWAL TRANSACTIONS ==="
    docker compose exec -T postgres \
        psql -U banco_user -d banco_xyz \
        -c "
SELECT transaction_id, status, final_balance, rejection_reason, updated_at
FROM withdrawal_transactions
WHERE transaction_id IN ('$TX_OK', '$TX_REJECT')
ORDER BY created_at;"
} | tee "$EVID/15_event_store.txt"
```

Resultado esperado: `WithdrawalRequested` y `WithdrawalCompleted` para `TX_OK`; `WithdrawalRequested` y `WithdrawalRejected` para `TX_REJECT`.

### Paso 16 - Estado de transacciones

Consulta ambos estados por la API oficial del BFF ATM.

```bash
{
    echo "=== ESTADO TX_OK ==="
    curl -sS \
        -H "Authorization: Bearer $ATM_TOKEN" \
        "http://localhost:8084/api/bff/atm/accounts/transactions/$TX_OK"
    echo
    echo "=== ESTADO TX_REJECT ==="
    curl -sS \
        -H "Authorization: Bearer $ATM_TOKEN" \
        "http://localhost:8084/api/bff/atm/accounts/transactions/$TX_REJECT"
} | tee "$EVID/16_estado_transaccion.txt"
```

Resultado esperado: un objeto `COMPLETED` y otro `REJECTED`.

### Paso 17 - Idempotencia por eventId

Obtiene desde `banking_events` el `eventId` y el JSON exacto de `WithdrawalRequested` para `TX_OK`. Lo republica en el topic real con el header Spring `__TypeId__`, usa la cuenta como Kafka key y verifica que Core Banking lo ignore sin descontar nuevamente el saldo.

```bash
EVENT_ID_OK=$(docker compose exec -T postgres \
    psql -At -U banco_user -d banco_xyz \
    -c "
SELECT event_id
FROM banking_events
WHERE transaction_id = '$TX_OK'
  AND event_type = 'WithdrawalRequested';")

EVENT_JSON=$(docker compose exec -T postgres \
    psql -At -U banco_user -d banco_xyz \
    -c "
SELECT payload::text
FROM banking_events
WHERE event_id = '$EVENT_ID_OK';")

EVENT_ACCOUNT_ID=$(printf '%s' "$EVENT_JSON" | python3 -c \
    'import json,sys; print(json.load(sys.stdin)["accountId"])')

BALANCE_BEFORE=$(docker compose exec -T postgres \
    psql -At -U banco_user -d banco_xyz \
    -c "SELECT saldo_final FROM intereses_mensuales WHERE cuenta_id = $EVENT_ACCOUNT_ID;")

printf '__TypeId__:cl.duoc.bancoxyz.events.WithdrawalRequestedEvent|%s|%s\n' \
    "$EVENT_ACCOUNT_ID" "$EVENT_JSON" \
    | docker compose exec -T kafka \
        /opt/kafka/bin/kafka-console-producer.sh \
        --bootstrap-server localhost:9092 \
        --topic bank.withdrawal.requested \
        --property parse.headers=true \
        --property headers.delimiter='|' \
        --property headers.separator=',' \
        --property headers.key.separator=':' \
        --property parse.key=true \
        --property key.separator='|'

IDEMPOTENCY_OK=0

for i in {1..30}; do
    if docker compose logs --no-color core-banking-service \
        | grep -F -q "[Idempotency] Evento $EVENT_ID_OK ya procesado"; then
        IDEMPOTENCY_OK=1
        echo "Evento duplicado detectado en el intento $i."
        break
    fi

    sleep 1
done

BALANCE_AFTER=$(docker compose exec -T postgres \
    psql -At -U banco_user -d banco_xyz \
    -c "SELECT saldo_final FROM intereses_mensuales WHERE cuenta_id = $EVENT_ACCOUNT_ID;")

{
    echo "=== IDEMPOTENCIA ==="
    echo "EVENT_ID_OK=$EVENT_ID_OK"
    echo "Saldo antes: $BALANCE_BEFORE"
    echo "Saldo después: $BALANCE_AFTER"
    echo

    if [ "$IDEMPOTENCY_OK" -eq 1 ]; then
        echo "=== LOG DE IDEMPOTENCIA ==="
        docker compose logs --no-color core-banking-service \
            | grep -F "$EVENT_ID_OK"
    else
        echo "ERROR: no se observó el log de idempotencia tras 30 intentos."
    fi

    echo
    if [ "$IDEMPOTENCY_OK" -eq 1 ] \
        && [ "$BALANCE_BEFORE" = "$BALANCE_AFTER" ]; then
        echo "RESULTADO: OK - El evento duplicado fue ignorado y el saldo no cambió."
    else
        echo "RESULTADO: ERROR - Revisar la prueba de idempotencia."
    fi
} | tee "$EVID/17_idempotencia.txt"
```

Resultado esperado: `[Idempotency] Evento ... ya procesado, ignorando`, el mismo saldo antes y después y `RESULTADO: OK`.

### Paso 18 - Escalabilidad Kafka

Publica seis retiros pequeños sobre cuentas distintas, con claves diferentes, y muestra particiones, hilos y estado del consumer group. Los identificadores de transacción se generan dinámicamente en cada solicitud.

```bash
SCALING_ACCOUNTS=(102 105 106 108 110 111)

{
    echo "=== SOLICITUDES PARA ESCALABILIDAD ==="

    for account_id in "${SCALING_ACCOUNTS[@]}"; do
        response=$(curl -sS \
            -X POST \
            -H "Authorization: Bearer $ATM_TOKEN" \
            -H "Content-Type: application/json" \
            -d '{"amount":1}' \
            "http://localhost:8084/api/bff/atm/accounts/$account_id/withdraw")
        echo "accountId=$account_id response=$response"
    done

    echo
    echo "=== LOGS DE CONSUMO ==="
    sleep 2
    docker compose logs --no-color core-banking-service \
        | grep -E '\[Kafka\]\[Consumer\].*partition='

    echo
    echo "=== CONSUMER GROUP ==="
    docker compose exec -T kafka \
        /opt/kafka/bin/kafka-consumer-groups.sh \
        --bootstrap-server localhost:9092 \
        --describe \
        --group core-banking-withdrawal-group
} | tee "$EVID/18_escalabilidad_kafka.txt"
```

Resultado esperado: actividad en las particiones `0`, `1` y `2`, hilos consumidores y lag observable o `0` una vez procesada la cola.

### Paso 19 - Resilience4j en operación normal

Prueba la consulta ATM protegida mientras Core Banking está disponible.

```bash
{
    echo "=== RESILIENCE4J NORMAL ==="
    curl -sS -i \
        -H "Authorization: Bearer $ATM_TOKEN" \
        "http://localhost:8084/api/bff/atm/accounts/$ACCOUNT_ID/balance"
} | tee "$EVID/19_resilience4j_normal.txt"
```

Resultado esperado: HTTP `200`.

### Paso 20 - Resilience4j ante fallo y apertura

Detiene únicamente el servicio Compose real `core-banking-service`, confirma la caída de su healthcheck y mantiene BFF ATM en ejecución para registrar los fallbacks controlados. No se mezcla `java -jar` con Docker Compose.

```bash
docker compose stop core-banking-service

for i in {1..30}; do
    if ! curl -fsS http://localhost:8081/actuator/health > /dev/null 2>&1; then
        echo "Core Banking detenido."
        break
    fi

    if [ "$i" -eq 30 ]; then
        echo "ERROR: Core Banking no se detuvo." >&2
        break
    fi

    sleep 1
done

{
    echo "=== RESILIENCE4J FALLO / OPEN ==="
    for attempt in {1..5}; do
        echo "Intento $attempt"
        curl -sS -i \
            -H "Authorization: Bearer $ATM_TOKEN" \
            "http://localhost:8084/api/bff/atm/accounts/$ACCOUNT_ID/balance"
        echo
    done
} | tee "$EVID/20_resilience4j_fallo_open.txt"
```

Resultado esperado: BFF ATM permanece disponible y devuelve HTTP `503` controlado.

### Paso 21 - Recuperación Resilience4j

Reinicia únicamente el contenedor existente de Core Banking, sin procesar dependencias. La opción `--no-deps` evita una nueva ejecución de `batch-init` y conserva el estado operacional de PostgreSQL; luego espera su healthcheck, el registro Eureka y la recuperación HTTP `200` del BFF ATM.

```bash
docker compose restart --no-deps core-banking-service
wait_url "http://localhost:8081/actuator/health" "Core Banking recuperado"

for i in {1..60}; do
    if curl -fsS \
        -H "Accept: application/xml" \
        http://localhost:8761/eureka/apps/CORE-BANKING-SERVICE \
        | grep -q '<status>UP</status>'; then
        echo "CORE-BANKING-SERVICE nuevamente UP en Eureka."
        break
    fi

    if [ "$i" -eq 60 ]; then
        echo "ERROR: Core Banking no se registró en Eureka." >&2
        break
    fi

    sleep 2
done

for i in {1..30}; do
    RECOVERY_HTTP=$(curl -sS \
        -o /tmp/s7_recovery_body \
        -w '%{http_code}' \
        -H "Authorization: Bearer $ATM_TOKEN" \
        "http://localhost:8084/api/bff/atm/accounts/$ACCOUNT_ID/balance")

    if [ "$RECOVERY_HTTP" = "200" ]; then
        break
    fi

    if [ "$i" -eq 30 ]; then
        echo "ERROR: BFF ATM no se recuperó." >&2
        break
    fi

    sleep 1
done

BALANCE_RECOVERED=$(python3 -c \
    'import json; print(json.load(open("/tmp/s7_recovery_body")).get("balance", ""))')


{
    echo "=== RECUPERACIÓN RESILIENCE4J ==="
    echo "CORE-BANKING-SERVICE en Eureka:"
    curl -sS \
        -H "Accept: application/xml" \
        http://localhost:8761/eureka/apps/CORE-BANKING-SERVICE \
        | grep -E '<name>|<status>|<port'
    echo
    echo "HTTP recuperado: $RECOVERY_HTTP"
    cat /tmp/s7_recovery_body
    echo
    echo "Saldo recuperado: $BALANCE_RECOVERED"
    if [ -n "$BALANCE_RECOVERED" ] \
        && printf '%s' "$BALANCE_RECOVERED" | python3 -c \
            'from decimal import Decimal; import sys; raise SystemExit(0 if Decimal(sys.stdin.read().strip()) == Decimal("200.00") else 1)'; then
        echo "Saldo operativo conservado: $BALANCE_RECOVERED"
    else
        echo "ERROR: El saldo cambio durante la recuperacion."
    fi
} | tee "$EVID/21_resilience4j_recuperacion.txt"
```

Resultado esperado: Core Banking `UP` en Eureka, HTTP `200` y saldo operativo `200.00`. La guía no inventa estados internos `OPEN`, `HALF_OPEN` o `CLOSED`; registra la respuesta observable real.

### Paso 22 - Seguridad y rutas retiradas

Comprueba los cuatro casos solicitados con los tokens creados en el Paso 09.

```bash
{
    echo "=== SIN JWT: 401 ==="
    curl -sS -i \
        -X POST \
        -H "Content-Type: application/json" \
        -d '{"amount":1}' \
        "http://localhost:8084/api/bff/atm/accounts/$ACCOUNT_ID/withdraw"

    echo
    echo "=== JWT WEB CONTRA ATM: 403 ==="
    curl -sS -i \
        -H "Authorization: Bearer $WEB_TOKEN" \
        "http://localhost:8084/api/bff/atm/accounts/$ACCOUNT_ID/balance"

    echo
    echo "=== RUTA /withdraw/async RETIRADA: 404 ==="
    curl -sS -i \
        -X POST \
        -H "Authorization: Bearer $ATM_TOKEN" \
        -H "Content-Type: application/json" \
        -d '{"amount":1}' \
        "http://localhost:8084/api/bff/atm/accounts/$ACCOUNT_ID/withdraw/async"

    echo
    echo "=== RETIRO HTTP SINCRÓNICO CORE RETIRADO: 404 ==="
    curl -sS -i \
        -X POST \
        -H "Content-Type: application/json" \
        -d '{"amount":1}' \
        "http://localhost:8081/api/core/accounts/$ACCOUNT_ID/withdraw"
} | tee "$EVID/22_seguridad_y_rutas_retiradas.txt"
```

Resultado esperado: HTTP `401`, `403`, `404` y `404`, respectivamente.

### Paso 23 - Regresión de componentes heredados

Comprueba servicios Config/Eureka/Core/BFF, seguridad, consultas síncronas disponibles y la ejecución Batch existente. Tras la recuperación del Core, espera activamente la propagación de la instancia hacia los tres BFF antes de registrar las consultas finales; no se prueba el retiro síncrono eliminado deliberadamente.

```bash
wait_bff_200() {
    local channel="$1"
    local token="$2"
    local url="$3"
    local response_file="$4"

    BFF_HTTP=000
    for attempt in {1..30}; do
        BFF_HTTP=$(curl -sS -i \
            -o "$response_file" \
            -w '%{http_code}' \
            -H "Authorization: Bearer $token" \
            "$url" || true)
        echo "BFF $channel intento $attempt: HTTP $BFF_HTTP"

        if [ "$BFF_HTTP" = "200" ]; then
            return 0
        fi

        sleep 2
    done

    echo "ERROR: BFF $channel no se recuperó."
    return 1
}

{
    echo "=== HEALTHCHECKS ==="
    for url in \
        http://localhost:8888/actuator/health \
        http://localhost:8761/actuator/health \
        http://localhost:8081/actuator/health \
        http://localhost:8082/actuator/health \
        http://localhost:8083/actuator/health \
        http://localhost:8084/actuator/health; do
        echo "$url"
        curl -sS "$url"
    done

    echo
    echo "=== CONFIG SERVER ==="
    curl -sS http://localhost:8888/core-banking-service/default \
        | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["name"], d["propertySources"][0]["name"])'

    echo
    echo "=== EUREKA ==="
    curl -sS \
        -H "Accept: application/xml" \
        http://localhost:8761/eureka/apps \
        | grep -E '<name>|<status>'

    echo
    echo "=== CONSULTAS BFF ==="
    wait_bff_200 "Web" "$WEB_TOKEN" \
        "http://localhost:8082/api/bff/web/accounts/$ACCOUNT_ID" \
        /tmp/s7_regression_web_response || true
    echo "WEB HTTP FINAL: $BFF_HTTP"
    cat /tmp/s7_regression_web_response

    wait_bff_200 "Mobile" "$MOBILE_TOKEN" \
        "http://localhost:8083/api/bff/mobile/accounts/$ACCOUNT_ID" \
        /tmp/s7_regression_mobile_response || true
    echo "MOBILE HTTP FINAL: $BFF_HTTP"
    cat /tmp/s7_regression_mobile_response

    wait_bff_200 "ATM" "$ATM_TOKEN" \
        "http://localhost:8084/api/bff/atm/accounts/$ACCOUNT_ID/balance" \
        /tmp/s7_regression_atm_response || true
    echo "ATM HTTP FINAL: $BFF_HTTP"
    cat /tmp/s7_regression_atm_response

    echo
    echo "=== SEGURIDAD ==="
    SECURITY_HTTP=$(curl -sS -i \
        -o /tmp/s7_regression_no_jwt_response \
        -w '%{http_code}' \
        "http://localhost:8082/api/bff/web/accounts/$ACCOUNT_ID" || true)
    echo "SIN JWT HTTP FINAL: $SECURITY_HTTP"
    cat /tmp/s7_regression_no_jwt_response
    echo
    echo "=== BATCH INIT ==="
    docker compose logs --no-color batch-init \
        | grep -E 'dailyTransactionsJob|monthlyInterestJob|annualStatementsJob|BATCH INIT COMPLETED'
} | tee "$EVID/23_regresion_semana06.txt"
```

Resultado esperado: healthchecks `UP`, consultas protegidas `200`, sin JWT `401` y Jobs Batch `COMPLETED`. Si un BFF no llega a `200` después de 30 intentos separados por 2 segundos, el Paso 23 registra `ERROR` y continúa para dejar el diagnóstico en la evidencia.

### Paso 24 - Verificación final

Se ejecuta después de los pasos 01 a 23. `tee` crea `24_verificacion_final.txt` antes de contar, por lo que la validación debe informar exactamente 24 archivos `.txt`.

```bash
{
    echo "=== VERIFICACIÓN FINAL SEMANA 07 ==="
    echo
    echo "=== ARCHIVOS DE EVIDENCIA ==="
    find "$EVID" \
        -maxdepth 1 \
        -type f \
        -name '*.txt' \
        -printf '%f\n' \
        | sort

    echo
    echo "=== CANTIDAD DE EVIDENCIAS ==="
    find "$EVID" \
        -maxdepth 1 \
        -type f \
        -name '*.txt' \
        | wc -l

    echo
    echo "=== SERVICIOS ==="
    docker compose ps -a

    echo
    echo "=== BUILD ==="
    grep -E 'Reactor Summary|BUILD SUCCESS|BUILD FAILURE' "$EVID/01_build.txt"

    echo
    echo "=== HTTP PRINCIPALES ==="
    grep -H -E 'HTTP/.* (200|202|401|403|404|503)' \
        "$EVID/10_saldo_inicial.txt" \
        "$EVID/11_retiro_oficial_solicitud.txt" \
        "$EVID/14_saldo_final.txt" \
        "$EVID/19_resilience4j_normal.txt" \
        "$EVID/20_resilience4j_fallo_open.txt" \
        "$EVID/21_resilience4j_recuperacion.txt" \
        "$EVID/22_seguridad_y_rutas_retiradas.txt"
    echo
    echo "=== PASO 21 RECUPERACION ==="
    if grep -q 'HTTP recuperado: 200' "$EVID/21_resilience4j_recuperacion.txt" \
        && grep -Eq '"balance"[[:space:]]*:[[:space:]]*200(\.0+)?([,}]|$)' \
            "$EVID/21_resilience4j_recuperacion.txt"; then
        echo "PASO 21 RECUPERACION: OK"
    else
        echo "PASO 21 RECUPERACION: ERROR"
    fi
    echo
    echo "=== PASO 23 REGRESION ==="
    if grep -q 'WEB HTTP FINAL: 200' "$EVID/23_regresion_semana06.txt" \
        && grep -q 'MOBILE HTTP FINAL: 200' "$EVID/23_regresion_semana06.txt" \
        && grep -q 'ATM HTTP FINAL: 200' "$EVID/23_regresion_semana06.txt" \
        && grep -q 'SIN JWT HTTP FINAL: 401' "$EVID/23_regresion_semana06.txt"; then
        echo "PASO 23 REGRESION: OK"
    else
        echo "PASO 23 REGRESION: ERROR"
    fi

    echo "=== KAFKA ==="
    docker compose exec -T kafka \
        /opt/kafka/bin/kafka-consumer-groups.sh \
        --bootstrap-server localhost:9092 \
        --describe \
        --group core-banking-withdrawal-group

    echo
    echo "=== ESTADOS DE TRANSACCIONES ==="
    printf '%s\n' "$STATUS_OK"
    printf '%s\n' "$STATUS_REJECT"
} | tee "$EVID/24_verificacion_final.txt"
```

Resultado esperado: 24 archivos `.txt`, servicios activos, `BUILD SUCCESS`, respuestas HTTP principales, estado Kafka y transacciones `COMPLETED`/`REJECTED`.

## Colección final esperada

La carpeta `evidencias/semana 07` contiene exactamente los siguientes 24 archivos:

1. `01_build.txt`
2. `02_tests.txt`
3. `03_docker_servicios.txt`
4. `04_config_server.txt`
5. `05_eureka_servicios.txt`
6. `06_kafka_topics.txt`
7. `07_kafka_particiones.txt`
8. `08_kafka_consumer_group.txt`
9. `09_jwt_atm.txt`
10. `10_saldo_inicial.txt`
11. `11_retiro_oficial_solicitud.txt`
12. `12_logs_kafka.txt`
13. `13_retiro_rechazado.txt`
14. `14_saldo_final.txt`
15. `15_event_store.txt`
16. `16_estado_transaccion.txt`
17. `17_idempotencia.txt`
18. `18_escalabilidad_kafka.txt`
19. `19_resilience4j_normal.txt`
20. `20_resilience4j_fallo_open.txt`
21. `21_resilience4j_recuperacion.txt`
22. `22_seguridad_y_rutas_retiradas.txt`
23. `23_regresion_semana06.txt`
24. `24_verificacion_final.txt`

Advertencias para la futura ejecución manual: iniciar desde el Paso 00 elimina datos Compose locales del proyecto y las evidencias `.txt` de Semana 07; ejecutar siempre en una sola terminal; no reutilizar JWT, `transactionId` ni `eventId` de ejecuciones anteriores; y no lanzar una segunda ejecución mientras la primera siga en curso.


# Semana 08 - OAuth2, seguridad distribuida y preparación Cloud

## Objetivo

Semana 08 incorpora OAuth2.0 con Spring Authorization Server, Access Tokens JWT, OAuth2 Resource Servers y scopes por canal para proteger los servicios del Banco XYZ. Core Banking exige un token válido y los BFF propagan el Bearer en sus consultas HTTP. Docker y Docker Compose permiten ejecutar el stack completo de forma portable, conservando Kafka, Resilience4j y Spring Batch.

## Evolución de la seguridad

| Seguridad anterior      | Semana 08                        |
| ----------------------- | -------------------------------- |
| /auth/login propio      | /oauth2/token                    |
| JwtService propio       | Spring Authorization Server      |
| JWT HMAC propio         | Access Token JWT firmado con RSA |
| JwtAuthenticationFilter | OAuth2 Resource Server           |
| ROLE_WEB                | SCOPE_web                        |
| ROLE_MOBILE             | SCOPE_mobile                     |
| ROLE_ATM                | SCOPE_atm                        |

JWT continúa como formato del Access Token OAuth2. La emisión y validación propias fueron reemplazadas por Spring Authorization Server y Spring Security OAuth2 Resource Server.

## Arquitectura OAuth2

~~~mermaid
flowchart TD
    C[Clientes] -->|client_credentials| AS[Authorization Server :9000]
    AS -->|RSA / JWK| JWT[Access Token JWT]
    JWT --> W[BFF Web - SCOPE_web]
    JWT --> M[BFF Mobile - SCOPE_mobile]
    JWT --> A[BFF ATM - SCOPE_atm]
    W -->|Authorization: Bearer| CORE[Core Banking - Resource Server]
    M -->|Authorization: Bearer| CORE
    A -->|Authorization: Bearer en consultas HTTP| CORE
    A -->|WithdrawalRequested y ACK| K[Kafka - 3 particiones]
    K --> CORE
    CORE -->|WithdrawalCompleted / WithdrawalRejected| K
~~~

El cliente envía el encabezado Authorization: Bearer con su Access Token. Para consultas HTTP, cada BFF propaga el mismo token hacia Core, que vuelve a validarlo. El retiro ATM se publica como evento Kafka; el HTTP 202 confirma la aceptación tras el ACK, mientras el procesamiento bancario ocurre de forma asíncrona.

## Authorization Server

El módulo proyecto/authorization-server utiliza Java 17 y Spring Authorization Server. La configuración real define:

- Puerto 9000.
- POST /oauth2/token para obtener Access Tokens.
- Grant client_credentials y autenticación client_secret_basic.
- JWT firmado con una clave RSA de 2048 bits.
- GET /oauth2/jwks para consultar las claves públicas.
- Access Token con duración configurada de 30 minutos.
- RegisteredClientRepository en memoria.
- Secretos codificados mediante DelegatingPasswordEncoder, cuyo codificador predeterminado es BCrypt.
- GET /actuator/health público para infraestructura.

La clave RSA se genera al iniciar Authorization Server. Después de reiniciarlo se deben obtener tokens nuevos. Los secretos se configuran mediante OAUTH2_WEB_SECRET, OAUTH2_MOBILE_SECRET y OAUTH2_ATM_SECRET; esta sección utiliza variables y conserva sus valores fuera de los archivos de evidencias.

Las evidencias 05, 06 y 07 registraron HTTP 200, token_type Bearer, los scopes web, mobile y atm respectivamente y expires_in 1799 segundos. La evidencia 08 confirmó alg RS256 y los subjects web-client, mobile-client y atm-client, cada uno con su scope correspondiente. La diferencia entre exp e iat fue de 1800 segundos; expires_in refleja el tiempo restante al responder. Los Access Tokens completos y los secretos no se guardaron en las evidencias.

## Clientes y scopes

| client_id     | grant_type         | scope  |
| ------------- | ------------------ | ------ |
| web-client    | client_credentials | web    |
| mobile-client | client_credentials | mobile |
| atm-client    | client_credentials | atm    |

Spring Security convierte los scopes en autoridades: web → SCOPE_web, mobile → SCOPE_mobile y atm → SCOPE_atm.

## Resource Servers y propagación Bearer

| Servicio     | Puerto host | Protección                 |
| ------------ | ----------- | -------------------------- |
| BFF Web      | 8082        | SCOPE_web                  |
| BFF Mobile   | 8083        | SCOPE_mobile               |
| BFF ATM      | 8084        | SCOPE_atm                  |
| Core Banking | 8081        | Access Token OAuth2 válido |

Los Resource Servers validan firma, vigencia e issuer. /actuator/health es público para los healthchecks; las APIs bancarias requieren autenticación. Los tests WebBearerPropagationTest, MobileBearerPropagationTest y AtmBearerPropagationTest comprueban el encabezado Bearer hacia Core.

## Issuer por entorno

| Entorno          | issuer                           | jwk-set-uri                                  |
| ---------------- | -------------------------------- | -------------------------------------------- |
| Local, Maven/IDE | http://localhost:9000            | http://localhost:9000/oauth2/jwks            |
| Docker Compose   | http://authorization-server:9000 | http://authorization-server:9000/oauth2/jwks |

La configuración utiliza valores locales predeterminados y overrides mediante OAUTH2_ISSUER y OAUTH2_JWK_SET_URI. Core, los tres BFF y batch-init reciben los valores internos mediante Compose. Config Server continúa utilizando config-repo.

Desde el host se solicita el token en http://localhost:9000/oauth2/token. Bajo Compose, el claim iss real fue http://authorization-server:9000, que coincide con los Resource Servers. Estos valores corresponden a configuración por entorno y se mantienen tal como están implementados.

## Resultados de seguridad

| Operación ejecutada                | Resultado obtenido  | Evidencia |
| ---------------------------------- | ------------------- | --------- |
| Token web-client / scope web       | HTTP 200, Bearer    | 05        |
| Token mobile-client / scope mobile | HTTP 200, Bearer    | 06        |
| Token atm-client / scope atm       | HTTP 200, Bearer    | 07        |
| Web sin token                      | HTTP 401            | 09        |
| Web con scope web                  | HTTP 200 final      | 10        |
| Web con scope atm                  | HTTP 403            | 11        |
| Web con token inválido             | HTTP 401            | 12        |
| Mobile con scope mobile            | HTTP 200            | 13        |
| ATM, consulta con scope atm        | HTTP 200 final      | 14        |
| Core sin token                     | HTTP 401            | 15        |
| Core con token válido              | HTTP 200            | 16        |
| Retiro ATM autorizado              | HTTP 202, PENDING   | 17        |
| Consulta del retiro final          | HTTP 200, COMPLETED | 18        |

Las consultas autorizadas de Web (10) y saldo ATM (14) registraron un HTTP 503 transitorio antes del HTTP 200 final, durante la estabilización de las consultas hacia Core. Ambas evidencias terminaron con RESULTADO: PASS.

## Migración del JWT propio y componentes eliminados

Se eliminaron AuthController, JwtService y JwtAuthenticationFilter, del login /auth/login, de ROLE_WEB, ROLE_MOBILE, ROLE_ATM, de security.jwt.secret y de las dependencias jjwt-api, jjwt-impl y jjwt-jackson. Los usuarios del login propio fueron sustituidos por clientes OAuth2.

Las referencias históricas a esos componentes en semanas anteriores permanecen intactas para explicar la evolución.

### Nueve archivos eliminados

Rutas relativas a la carpeta de este README:

~~~text
proyecto/bff-web-service/src/main/java/cl/duoc/bancoxyz/security/AuthController.java
proyecto/bff-web-service/src/main/java/cl/duoc/bancoxyz/security/JwtService.java
proyecto/bff-web-service/src/main/java/cl/duoc/bancoxyz/security/JwtAuthenticationFilter.java
proyecto/bff-mobile-service/src/main/java/cl/duoc/bancoxyz/security/AuthController.java
proyecto/bff-mobile-service/src/main/java/cl/duoc/bancoxyz/security/JwtService.java
proyecto/bff-mobile-service/src/main/java/cl/duoc/bancoxyz/security/JwtAuthenticationFilter.java
proyecto/bff-atm-service/src/main/java/cl/duoc/bancoxyz/security/AuthController.java
proyecto/bff-atm-service/src/main/java/cl/duoc/bancoxyz/security/JwtService.java
proyecto/bff-atm-service/src/main/java/cl/duoc/bancoxyz/security/JwtAuthenticationFilter.java
~~~

## Validación automatizada

Se ejecutó mvn test desde proyecto/, sin flags para omitir pruebas. La ejecución real terminó con:

~~~text
Tests run: 92
Failures: 0
Errors: 0
Skipped: 0
BUILD SUCCESS
~~~

| Módulo               | Tests en la suite completa |
| -------------------- | -------------------------: |
| Authorization Server |                          8 |
| Core Banking         |                         43 |
| BFF Web              |                         10 |
| BFF Mobile           |                         10 |
| BFF ATM              |                         21 |
| Total                |                         92 |

## Docker

El Dockerfile proyecto/docker/Dockerfile.service utiliza Java 17 JDK para compilar y Java 17 JRE para ejecutar. ARG MODULE selecciona el JAR de cada servicio. El archivo incorpora COPY authorization-server authorization-server.

La reproducción actual ejecutó docker compose build y terminó con RESULTADO: PASS en 02_docker_compose_build.txt. La evidencia 25 confirmó las ocho imágenes propias construidas:

~~~text
proyecto-authorization-server
proyecto-config-server
proyecto-eureka-server
proyecto-core-banking-service
proyecto-bff-web-service
proyecto-bff-mobile-service
proyecto-bff-atm-service
proyecto-batch-init
~~~

PostgreSQL y Kafka utilizan las imágenes configuradas directamente en docker-compose.yml y no requieren ser construidas mediante el Dockerfile del proyecto. La validación completa de la aplicación se realiza de forma independiente mediante mvn test.

## Docker Compose

Authorization Server utiliza la red default existente, publica 9000:9000 y verifica /actuator/health mediante curl. Core, BFF y batch-init esperan su estado healthy. Se conservan las dependencias de infraestructura y batch-init con service_completed_successfully.

| Componente           | Puerto host   | Estado estable |
| -------------------- | ------------- | -------------- |
| PostgreSQL           | 5433          | healthy        |
| Kafka                | 29092         | healthy        |
| Config Server        | 8888          | healthy        |
| Eureka Server        | 8761          | healthy        |
| Authorization Server | 9000          | healthy        |
| Core Banking         | 8081          | healthy        |
| BFF Web              | 8082          | healthy        |
| BFF Mobile           | 8083          | healthy        |
| BFF ATM              | 8084          | healthy        |
| batch-init           | Inicializador | Exited (0)     |

batch-init ejecutó dailyTransactionsJob, monthlyInterestJob y annualStatementsJob, registró COMPLETED y terminó con código 0.

La evidencia 04 registró siete healthchecks HTTP 200 con status UP. La evidencia 03 muestra el estado Compose de esa etapa del arranque; la comprobación final de los nueve servicios healthy y batch-init Exited (0), después de las pruebas de recuperación, está en la evidencia 34.

## Kafka y retiro asíncrono

Se consultaron los datos recién cargados y se seleccionó la cuenta 102 con saldo inicial 10200.00. El retiro de 10000.00 fue aceptado con HTTP 202 y PENDING; el productor espera el ACK de Kafka antes de responder.

La consulta de estado de la evidencia 18 obtuvo HTTP 200 y COMPLETED en el primer intento. Core descontó 10000.00 y el saldo final fue 200.00. El transactionId de esta ejecución queda identificado en las evidencias 17, 18 y 21, que relacionan la aceptación, el resultado final y la trazabilidad del retiro.

Los topics bank.withdrawal.requested, bank.withdrawal.completed y bank.withdrawal.rejected mostraron cada uno PartitionCount: 3, con particiones 0, 1 y 2 y factor de replicación 1. El consumer group core-banking-withdrawal-group mostró tres consumidores, uno por partición. La transacción se procesó en la partición 0, con current-offset 1, log-end-offset 1 y lag 0; las otras dos particiones no habían recibido mensajes y todavía no tenían offset confirmado en esa captura.

La configuración centralizada mostrada en la evidencia 27 mantiene concurrency: 3. Las evidencias 19 y 20 contienen las consultas reales de topics, particiones y consumer group. La evidencia 21 filtra los logs por transactionId y muestra WithdrawalRequested, consumo por Core, procesamiento COMPLETED y publicación en bank.withdrawal.completed. La consulta del Event Store confirma WithdrawalRequested y WithdrawalCompleted para la misma transacción.

En caso de error o timeout al publicar un retiro en Kafka, el servicio responde HTTP 503 con estado NOT_PUBLISHED. Este comportamiento fue validado mediante pruebas automatizadas incluidas en la suite Maven. Durante la ejecución manual no se realizó una prueba deteniendo Kafka para provocar este escenario.

### Idempotencia

La evidencia 22 fue generada nuevamente durante esta reproducción mediante la ejecución específica de AsyncWithdrawalServiceTest. Su método ignoresAnEventAlreadyProcessed obtuvo PASS: 1 test, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS y RESULTADO: PASS. El mismo comportamiento quedó cubierto dentro de la suite completa de 92 tests de la evidencia 01.

Este test comprueba que el servicio identifica un eventId ya procesado. La evidencia 22 corresponde a una prueba unitaria; durante la ejecución manual no se republicó un evento duplicado en la base.

## Resilience4j: configuración y validación final

Los Circuit Breakers `coreWeb`, `coreMobile` y `coreAtm` protegen las consultas HTTP de los BFF hacia Core. Config Server entregó los siguientes parámetros productivos mediante sus endpoints reales; los valores y sus fuentes están en [32_resilience4j_configuracion_productiva.txt](evidencias/semana 08/32_resilience4j_configuracion_productiva.txt).

| Circuito   | Ventana | Mínimo llamadas | Umbral fallo | Espera OPEN | Llamadas HALF_OPEN |
| ---------- | ------: | --------------: | -----------: | ----------: | -----------------: |
| coreWeb    |       5 |               5 |         50 % |         5 s |                  2 |
| coreMobile |       5 |               5 |         50 % |         5 s |                  2 |
| coreAtm    |       5 |               5 |         50 % |         5 s |                  2 |

`sliding-window-size=5` conserva una ventana de cinco llamadas; `minimum-number-of-calls=5` exige cinco resultados registrados antes de evaluar la tasa de fallos. `failure-rate-threshold=50` abre el circuito al alcanzar el 50 % de fallos. `wait-duration-in-open-state=5s` establece la espera en OPEN y `permitted-number-of-calls-in-half-open-state=2` permite dos llamadas de prueba en HALF_OPEN. Estos valores corresponden a la configuración validada en la evidencia 32.

### Validación runtime

Las evidencias [28_resilience4j_runtime.txt](evidencias/semana 08/28_resilience4j_runtime.txt) y [31_resilience4j_recuperacion_sin_dependencias.txt](evidencias/semana 08/31_resilience4j_recuperacion_sin_dependencias.txt) corresponden a una misma prueba de recuperación. Con Core operativo, Web, Mobile y ATM respondieron HTTP 200. Al detener Core, cada canal respondió HTTP 503. Tras reiniciar Core, Mobile y ATM recuperaron inmediatamente con HTTP 200, mientras que Web presentó respuestas 503 transitorias antes de volver a HTTP 200. La prueba finalizó con tres respuestas 200 consecutivas por canal y RESULTADO: PASS.

La evidencia [33_resilience4j_runtime_final.txt](evidencias/semana 08/33_resilience4j_runtime_final.txt) es la prueba runtime definitiva. Se consultó la cuenta 102 mediante los tres BFF; ATM utilizó solamente la consulta de saldo.

| Canal  | Core operativo | Core detenido: llamadas 1 a 6 | Core recuperado |
| ------ | -------------: | ----------------------------- | --------------: |
| Web    |            200 | 503, 503, 503, 503, 503, 503  |             200 |
| Mobile |            200 | 503, 503, 503, 503, 503, 503  |             200 |
| ATM    |            200 | 503, 503, 503, 503, 503, 503  |             200 |

Se detuvo exclusivamente Core con `docker compose stop core-banking-service` y se recuperó mediante `docker compose restart --no-deps core-banking-service`. Su healthcheck volvió a HTTP 200 con status UP. Después de la espera de recuperación, Web registró 503 transitorios en los intentos 1–8 y respondió 200 en los intentos 9, 10 y 11. Mobile y ATM obtuvieron 200 en sus tres primeros intentos. La prueba terminó con tres respuestas 200 consecutivas por canal y RESULTADO: PASS.

En las comprobaciones 28/31 y 33, `batch-init` mantuvo el mismo identificador, estado Exited (0) y fecha de arranque; no se reejecutó Batch. No se realizaron retiros durante estas pruebas y los 50 saldos permanecieron idénticos antes y después; la cuenta 102 mantuvo 200.00.

La evidencia 23 registró nueve tests JUnit PASS, tres por canal: CLOSED, OPEN, HALF_OPEN, recuperación a CLOSED, fallback y short-circuit. Los estados internos se comprobaron directamente mediante CircuitBreakerRegistry; las pruebas runtime registraron el comportamiento observable HTTP 503 → 200 con la configuración productiva, sin deducir OPEN únicamente de un 503.

## Spring Batch

La evidencia 24 registra la ejecución específica de DailyTransactionsJobIntegrationTest, MonthlyInterestJobIntegrationTest y AnnualMovementsStepIntegrationTest: 3 tests, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS y RESULTADO: PASS. Los tres también pasaron dentro de la suite completa de la evidencia 01.

batch-init ejecutó dailyTransactionsJob, monthlyInterestJob y annualStatementsJob después del reset; los tres Jobs finalizaron COMPLETED y el inicializador registró BATCH INIT COMPLETED. La evidencia 24 muestra los logs y el estado de los Jobs. Batch no se reejecutó durante las pruebas de recuperación Resilience4j.

## Verificación final de la colección

[34_verificacion_final_semana_08.txt](evidencias/semana%2008/34_verificacion_final_semana_08.txt) contiene el resumen definitivo de la reproducción iniciada en RUN_STARTED, indicado en el índice 00. El cierre comprobó 35 evidencias nuevas y no vacías, todas posteriores a RUN_STARTED, incluido el índice y los TXT 01–34.

| Comprobación                            | Resultado de la ejecución                    |
| --------------------------------------- | -------------------------------------------- |
| Maven, evidencia 01                     | 92/92 PASS; BUILD SUCCESS                    |
| Idempotencia, evidencia 22              | 1/1 PASS; BUILD SUCCESS                      |
| Resilience4j JUnit, evidencia 23        | 9/9 PASS; BUILD SUCCESS                      |
| Spring Batch, evidencia 24              | 3/3 PASS; BUILD SUCCESS; tres Jobs COMPLETED |
| OAuth2, JWT y seguridad HTTP            | PASS                                         |
| Retiro ATM y Kafka                      | PASS                                         |
| Docker, Compose, Eureka y Config Server | PASS                                         |
| Resilience4j, evidencias 28/31          | PASS; Batch y balances sin cambios           |
| Resilience4j, evidencia 33              | PASS; seis 503 por canal y recuperación 200  |
| Cierre de la colección                  | RESULTADO FINAL: PASS                        |

## Ejecución

Ejecutar los pasos 00–33 en orden, preferentemente en **una misma terminal Bash/WSL**. Se mantienen `EVID`, `WEB_CLIENT_SECRET`, `MOBILE_CLIENT_SECRET`, `ATM_CLIENT_SECRET`, `WEB_TOKEN`, `MOBILE_TOKEN`, `ATM_TOKEN`, `ACCOUNT_ID` y `TRANSACTION_ID`. Se necesitan Java 17, Maven, Docker Compose, curl y Python 3.

Los pasos siguientes permiten reproducir manualmente la colección completa de evidencias de Semana 08. El Paso 00 respalda la colección existente y posteriormente limpia la carpeta activa antes de iniciar una nueva generación. Se crean desde cero el índice 00 y los TXT 01–34 con los comandos de esta nueva secuencia. El respaldo solo se compara antes de la limpieza y no se utiliza para reconstruir ni completar evidencias. Si un comando falla, detener la secuencia antes de pasar al siguiente paso. No iniciar dos reproducciones simultáneas.

La reproducción busca resultados **funcionalmente equivalentes**. Pueden variar timestamps, `transactionId`, `eventId`, identificadores de contenedores y consumidores, offsets Kafka, tiempos de arranque, cuenta y saldo seleccionados y orden de logs. Se mantienen los criterios HTTP, de procesamiento, configuración y conservación del estado.

### Paso 00 - Preparar entorno y variables

Definir las rutas y funciones auxiliares, crear y verificar un respaldo completo fuera de la carpeta activa, vaciar exclusivamente su contenido y generar el índice 00. No elimina la carpeta. El respaldo queda disponible solo para recuperación. La preparación no ejecuta todavía el reset de Docker.

**Advertencia para el Paso 03:** `docker compose down -v --remove-orphans` es **destructivo** para los volúmenes locales de este Compose: elimina los datos PostgreSQL y cualquier persistencia Compose asociada. Utilizarlo solamente cuando se desea comenzar desde cero. No ejecutar de nuevo ese reset durante los pasos de recuperación.

~~~bash
cd "/home/francisco/proyecto/BACKEND III/SEMANA 8/Exp3_S8_Francisco_Henriquez/proyecto"

EVID="/home/francisco/proyecto/BACKEND III/SEMANA 8/Exp3_S8_Francisco_Henriquez/evidencias/semana 08"
mkdir -p "$EVID"
set -o pipefail
set +x

RUN_STARTED=$(date -Iseconds)
EXPECTED_EVIDENCES=(
    '00_indice_evidencias.md'
    '01_maven_test.txt'
    '02_docker_compose_build.txt'
    '03_docker_compose_ps.txt'
    '04_healthchecks.txt'
    '05_oauth2_token_web.txt'
    '06_oauth2_token_mobile.txt'
    '07_oauth2_token_atm.txt'
    '08_oauth2_jwt_claims.txt'
    '09_web_sin_token_401.txt'
    '10_web_scope_correcto.txt'
    '11_web_scope_incorrecto_403.txt'
    '12_web_token_invalido_401.txt'
    '13_mobile_autorizado.txt'
    '14_atm_saldo_autorizado.txt'
    '15_core_sin_token_401.txt'
    '16_core_token_valido.txt'
    '17_atm_retiro_pending.txt'
    '18_atm_retiro_resultado_final.txt'
    '19_kafka_topics_particiones.txt'
    '20_kafka_consumer_group.txt'
    '21_kafka_flujo_retiro.txt'
    '22_kafka_idempotencia_test.txt'
    '23_resilience4j_tests.txt'
    '24_batch_regresion.txt'
    '25_docker_images.txt'
    '26_eureka_servicios.txt'
    '27_config_server.txt'
    '28_resilience4j_runtime.txt'
    '29_verificacion_documental_alcance.txt'
    '30_docker_reset_arranque.txt'
    '31_resilience4j_recuperacion_sin_dependencias.txt'
    '32_resilience4j_configuracion_productiva.txt'
    '33_resilience4j_runtime_final.txt'
    '34_verificacion_final_semana_08.txt'
)
BACKUP="${EVID%/*}/respaldo-semana-08-$(date +%Y%m%d-%H%M%S)"

prepare_evidence_collection() (
    set -e
    if [ "$EVID" != "/home/francisco/proyecto/BACKEND III/SEMANA 8/Exp3_S8_Francisco_Henriquez/evidencias/semana 08" ] \
        || [ -L "$EVID" ]; then
        echo 'ERROR: EVID vacía, distinta de la ruta autorizada o enlace simbólico; no limpiar.' >&2
        exit 1
    fi
    python3 - "$EVID" "$BACKUP" <<'PY'
import pathlib,sys
active=pathlib.Path(sys.argv[1]); backup=pathlib.Path(sys.argv[2])
expected=pathlib.Path("/home/francisco/proyecto/BACKEND III/SEMANA 8/Exp3_S8_Francisco_Henriquez/evidencias/semana 08")
assert active.resolve() == expected and active.is_dir() and not active.is_symlink()
assert backup.parent.resolve() == expected.parent and backup.name.startswith("respaldo-semana-08-")
assert not backup.exists() and not backup.is_symlink(), "El respaldo ya existe; elegir otro timestamp"
PY
    cp -a -- "$EVID" "$BACKUP"
    python3 - "$EVID" "$BACKUP" <<'PY'
import hashlib,pathlib,sys
def manifest(base):
    entries={}
    for path in sorted(base.rglob("*")):
        name=str(path.relative_to(base))
        if path.is_symlink():
            entries[name]=("symlink",str(path.readlink()))
        elif path.is_dir():
            entries[name]=("directory",)
        elif path.is_file():
            digest=hashlib.sha256()
            with path.open("rb") as stream:
                for chunk in iter(lambda:stream.read(1024*1024),b""):
                    digest.update(chunk)
            entries[name]=("file",digest.hexdigest())
        else:
            raise ValueError("Tipo de archivo no admitido: "+name)
    return entries
assert manifest(pathlib.Path(sys.argv[1])) == manifest(pathlib.Path(sys.argv[2])), "Respaldo incompleto"
print("Respaldo completo verificado:",sys.argv[2])
PY
    # La ruta exacta y el respaldo se han verificado; no seguir enlaces.
    find "$EVID" -mindepth 1 -maxdepth 1 -exec rm -rf -- {} +
    python3 - "$EVID" <<'PY'
import pathlib,sys
assert not any(pathlib.Path(sys.argv[1]).iterdir()), "La carpeta activa no quedó vacía"
PY
    python3 - "$RUN_STARTED" "${EXPECTED_EVIDENCES[@]}" <<'PY' | tee "$EVID/00_indice_evidencias.md"
import sys
started=sys.argv[1]; names=sys.argv[2:]
descriptions=[
    "Suite Maven completa: 92 tests y BUILD SUCCESS",
    "Construcción de las ocho imágenes propias",
    "Estado de servicios Compose e inicializador Batch",
    "Healthchecks HTTP 200 y UP",
    "Access Token OAuth2 Web: metadatos seguros",
    "Access Token OAuth2 Mobile: metadatos seguros",
    "Access Token OAuth2 ATM: metadatos seguros",
    "Claims JWT públicos, issuer y vigencia",
    "Web sin token: HTTP 401",
    "Web con scope correcto: HTTP 200",
    "Web con scope ATM: HTTP 403",
    "Web con token inválido: HTTP 401",
    "Mobile autorizado: HTTP 200",
    "Cuenta real y saldo ATM autorizado",
    "Core sin token: HTTP 401",
    "Core con token válido: HTTP 200",
    "Retiro ATM aceptado: HTTP 202 y PENDING",
    "Estado terminal del retiro y comprobación de saldo",
    "Topics Kafka y sus tres particiones",
    "Consumer group, miembros, offsets y lag",
    "Trazabilidad Kafka de la transacción actual",
    "Test de idempotencia del evento ya procesado",
    "Nueve tests Resilience4j: estados, fallback y recuperación",
    "Tres tests de integración y Jobs Spring Batch",
    "Ocho imágenes propias del proyecto",
    "Cuatro servicios registrados UP en Eureka",
    "Configuración centralizada sin secretos",
    "Runtime Resilience4j seguro: captura compartida con 31",
    "Inspección documental, migración e inventario",
    "Reset único, arranque y espera del stack",
    "Recuperación de Core sin dependencias ni reejecución Batch",
    "Parámetros productivos Resilience4j desde Config Server",
    "Runtime definitivo: seis 503 por canal y recuperación 200",
    "Verificación final de las 35 evidencias y criterios funcionales"
]
assert len(names) == 35 and names[0] == "00_indice_evidencias.md"
assert len(descriptions) == 34
print("# Índice de evidencias - Semana 08")
print()
print("Inicio de la reproducción:",started)
print()
print("Procedencia: colección regenerada mediante la secuencia manual de Semana 08.")
print("Este índice se crea al inicio; los TXT se generan en los pasos posteriores.")
print("34_verificacion_final_semana_08.txt comprueba el cierre de la colección completa.")
print("El respaldo externo es solo recuperación; no forma parte de esta entrega.")
print()
print("| Evidencia | Descripción |")
print("| --- | --- |")
for name,description in zip(names[1:],descriptions):
    print("| "+chr(96)+name+chr(96)+" | "+description+" |")
PY
)
prepare_evidence_collection

unset WEB_TOKEN MOBILE_TOKEN ATM_TOKEN ACCOUNT_ID TRANSACTION_ID
unset WEB_CLIENT_SECRET MOBILE_CLIENT_SECRET ATM_CLIENT_SECRET

begin_evidence() {
    printf 'EVIDENCIA: %s\nFECHA: %s\nOBJETIVO: %s\nCOMANDO: %s\n\nRESULTADO REAL:\n' \
        "$1" "$(date -Iseconds)" "$2" "$3"
}

wait_url() {
    local url="$1" name="$2" attempt
    for attempt in {1..90}; do
        if curl -fsS --max-time 5 "$url" | python3 -c \
            'import json,sys; assert json.load(sys.stdin)["status"] == "UP"' 2>/dev/null; then
            printf '%s disponible: HTTP 200, status UP.\n' "$name"
            return 0
        fi
        sleep 2
    done
    printf 'ERROR: timeout esperando %s.\n' "$name" >&2
    return 1
}

http_request() {
    local method="$1" url="$2" token="${3:-}" data="${4:-}" response
    if [ "$method" = POST ]; then
        response=$(curl -sS --max-time 15 -X POST \
            -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
            --data "$data" -w '\n%{http_code}' "$url") || return 1
    elif [ -n "$token" ]; then
        response=$(curl -sS --max-time 15 \
            -H "Authorization: Bearer $token" -w '\n%{http_code}' "$url") || return 1
    else
        response=$(curl -sS --max-time 15 -w '\n%{http_code}' "$url") || return 1
    fi
    HTTP_STATUS=$(printf '%s\n' "$response" | tail -n 1)
    HTTP_BODY=$(printf '%s\n' "$response" | sed '$d')
}

record_get() {
    local file="$1" expected="$2" url="$3" token="${4:-}" attempt limit=1
    begin_evidence "$file" "Comprobar HTTP $expected" "GET $url; Bearer en memoria cuando corresponde" \
        | tee "$EVID/$file"
    if [ "$expected" = 200 ]; then limit=60; fi
    for ((attempt=1; attempt<=limit; attempt++)); do
        http_request GET "$url" "$token" || return 1
        printf 'Intento: %s\nHTTP status: %s\n%s\n' "$attempt" "$HTTP_STATUS" "$HTTP_BODY" \
            | tee -a "$EVID/$file"
        if [ "$HTTP_STATUS" = "$expected" ]; then
            echo 'RESULTADO: PASS' | tee -a "$EVID/$file"
            return 0
        fi
        if [ "$expected" != 200 ] || [ "$HTTP_STATUS" != 503 ]; then break; fi
        sleep 2
    done
    echo 'RESULTADO: FAIL' | tee -a "$EVID/$file"
    return 1
}

request_token() {
    local client="$1" scope="$2" secret="$3" variable="$4" file="$5"
    local response code body token title
    response=$(curl -sS --max-time 15 --user "$client:$secret" \
        --data-urlencode 'grant_type=client_credentials' \
        --data-urlencode "scope=$scope" \
        -w '\n%{http_code}' http://localhost:9000/oauth2/token) || return 1
    code=$(printf '%s\n' "$response" | tail -n 1)
    body=$(printf '%s\n' "$response" | sed '$d')
    case "$scope" in
        web) title='Token OAuth2 Web' ;;
        mobile) title='Token OAuth2 Mobile' ;;
        atm) title='Token OAuth2 ATM' ;;
        *) title="Token OAuth2 $scope" ;;
    esac
    begin_evidence "$title" \
        "Obtener Access Token OAuth2 para $client sin almacenar el JWT completo" \
        "POST http://localhost:9000/oauth2/token - client=$client - grant_type=client_credentials - scope=$scope" \
        | tee "$EVID/$file"
    printf 'HTTP status: %s\n' "$code" | tee -a "$EVID/$file"
    if [ "$code" != 200 ]; then
        echo 'ERROR: solicitud OAuth2 fallida; no se guarda el body sensible.' >&2
        return 1
    fi
    token=$(printf '%s' "$body" | python3 -c '
import json,sys
d=json.load(sys.stdin)
assert d["token_type"].lower() == "bearer"
assert d["scope"].split() == [sys.argv[1]]
assert 0 < int(d["expires_in"]) <= 1800
assert len(d["access_token"].split(".")) == 3
print(d["access_token"])
' "$scope") || return 1
    printf -v "$variable" '%s' "$token"
    # Se imprimen solo metadatos; la función se llama sin pipe exterior.
    printf '%s' "$body" | python3 -c '
import json,sys
d=json.load(sys.stdin)
print(json.dumps({k:d[k] for k in ("token_type","scope","expires_in")},ensure_ascii=False))
print("Access Token mantenido exclusivamente en memoria.")
' | tee -a "$EVID/$file"
}

report_tests() {
    python3 -c '
import pathlib,sys,xml.etree.ElementTree as ET
expected=int(sys.argv[1]); names=set(sys.argv[2:]); totals=[0,0,0,0]; found=set()
for path in sorted(pathlib.Path(".").glob("*/target/surefire-reports/TEST-*.xml")):
    root=ET.parse(path).getroot()
    short=root.attrib["name"].split(".")[-1]
    if names and short not in names:
        continue
    found.add(short)
    counts=[int(root.attrib.get(k,0)) for k in ("tests","failures","errors","skipped")]
    totals=[a+b for a,b in zip(totals,counts)]
    print(root.attrib["name"], dict(zip(("tests","failures","errors","skipped"),counts)))
    for case in root.findall("testcase"):
        passed=all(case.find(k) is None for k in ("failure","error","skipped"))
        print("  "+case.attrib["name"]+": "+("PASS" if passed else "FAIL"))
for key,value in zip(("Tests run","Failures","Errors","Skipped"),totals):
    print(f"{key}: {value}")
assert not names or found == names, "Faltan reportes solicitados"
assert totals == [expected,0,0,0], totals
print("RESULTADO: PASS")
' "$@"
}


wait_eureka() {
    local attempt
    for attempt in {1..60}; do
        if curl -fsS --max-time 10 -H 'Accept: application/xml' \
            http://localhost:8761/eureka/apps | python3 -c '
import sys,xml.etree.ElementTree as ET
root=ET.fromstring(sys.stdin.read())
expected={"CORE-BANKING-SERVICE","BFF-WEB-SERVICE","BFF-MOBILE-SERVICE","BFF-ATM-SERVICE"}
up={a.findtext("name") for a in root.findall("application")
    if any(i.findtext("status")=="UP" for i in a.findall("instance"))}
assert expected <= up
for name in sorted(expected):
    print(name, "UP")
' 2>/dev/null; then
            return 0
        fi
        sleep 2
    done
    echo 'ERROR: timeout de registro Eureka.' >&2
    return 1
}

wait_compose_ready() {
    local attempt id state service infra_ready=0 batch_ready=0
    for attempt in {1..90}; do
        if docker compose exec -T postgres pg_isready -U banco_user -d banco_xyz \
            && docker compose exec -T kafka /opt/kafka/bin/kafka-topics.sh \
                --bootstrap-server localhost:9092 --list >/dev/null; then
            echo 'PostgreSQL y Kafka disponibles mediante sus herramientas.'
            infra_ready=1
            break
        fi
        sleep 2
    done
    test "$infra_ready" -eq 1 || { echo 'ERROR: timeout PostgreSQL/Kafka.' >&2; return 1; }
    for attempt in {1..180}; do
        id=$(docker compose ps -a -q batch-init)
        if [ -n "$id" ]; then
            state=$(docker inspect --format '{{.State.Status}} {{.State.ExitCode}}' "$id")
            if [ "$state" = 'exited 0' ]; then
                echo 'batch-init: Exited (0).'
                batch_ready=1
                break
            fi
            if [[ "$state" == exited* ]]; then
                echo "ERROR: batch-init: $state" >&2
                return 1
            fi
        fi
        sleep 2
    done
    test "$batch_ready" -eq 1 || { echo 'ERROR: timeout batch-init.' >&2; return 1; }
    wait_url http://localhost:8888/actuator/health 'Config Server' || return 1
    wait_url http://localhost:8761/actuator/health 'Eureka' || return 1
    wait_url http://localhost:9000/actuator/health 'Authorization Server' || return 1
    wait_url http://localhost:8081/actuator/health 'Core' || return 1
    wait_url http://localhost:8082/actuator/health 'Web' || return 1
    wait_url http://localhost:8083/actuator/health 'Mobile' || return 1
    wait_url http://localhost:8084/actuator/health 'ATM' || return 1
    wait_eureka || return 1
    for service in postgres kafka config-server eureka-server authorization-server \
        core-banking-service bff-web-service bff-mobile-service bff-atm-service; do
        for attempt in {1..60}; do
            id=$(docker compose ps -a -q "$service") || return 1
            state=$(docker inspect --format '{{.State.Status}} {{.State.Health.Status}}' "$id") || return 1
            if [ "$state" = 'running healthy' ]; then break; fi
            sleep 2
        done
        printf '%s: %s\n' "$service" "$state"
        test "$state" = 'running healthy' || return 1
    done
}
~~~

Resultado esperado: respaldo completo verificado fuera de la carpeta activa, eliminación de su contenido anterior, nuevo índice 00 y funciones disponibles. Evidencia: `00_indice_evidencias.md`, con fecha/hora de RUN_STARTED y listado ordenado de 01–34.

**Ingreso de secretos: ejecutar las tres líneas siguientes una a una.** Los caracteres no serán visibles. **No pegar estos bloques juntos ni con otros comandos:** `read -s` puede consumir líneas posteriores del búfer. Los secretos no se escriben en el índice ni en evidencias.

Web:

~~~bash
read -r -s -p 'Secreto elegido para Web: ' WEB_CLIENT_SECRET; printf '\n'
~~~

Mobile, después de completar Web:

~~~bash
read -r -s -p 'Secreto elegido para Mobile: ' MOBILE_CLIENT_SECRET; printf '\n'
~~~

ATM, después de completar Mobile:

~~~bash
read -r -s -p 'Secreto elegido para ATM: ' ATM_CLIENT_SECRET; printf '\n'
~~~

Una vez completadas las tres entradas, comprobar que no están vacías y exportarlas para Authorization Server. Este bloque se copia por separado y no muestra sus valores:

~~~bash
test -n "$WEB_CLIENT_SECRET" && test -n "$MOBILE_CLIENT_SECRET" && test -n "$ATM_CLIENT_SECRET" && \
    export OAUTH2_WEB_SECRET="$WEB_CLIENT_SECRET" \
           OAUTH2_MOBILE_SECRET="$MOBILE_CLIENT_SECRET" \
           OAUTH2_ATM_SECRET="$ATM_CLIENT_SECRET"
~~~

Resultado esperado: WEB_CLIENT_SECRET, MOBILE_CLIENT_SECRET y ATM_CLIENT_SECRET en memoria, con sus equivalentes OAUTH2_WEB_SECRET, OAUTH2_MOBILE_SECRET y OAUTH2_ATM_SECRET exportados. 
Evidencia: ninguna adicional; no imprimir secretos.

**Solo si Docker Desktop/WSL presenta un error relacionado con `docker-credential-desktop.exe`**, antes del Paso 02 utilizar esta configuración temporal. No modifica la configuración global:

~~~bash
mkdir -p /tmp/docker-config-s8
printf '{}\n' > /tmp/docker-config-s8/config.json
export DOCKER_CONFIG=/tmp/docker-config-s8
docker info >/dev/null && echo "Docker OK"
~~~

Resultado esperado del bloque opcional: `Docker OK`. 
Evidencia: ninguna.

### Paso 01 - Tests Maven

Ejecutar la suite completa desde proyecto/, sin omitir pruebas. El filtro conserva el resumen Maven y evita volcar logs DEBUG de las pruebas de autenticación. El resumen agregado se calcula desde los reportes Surefire recién producidos.

~~~bash
(
    set -e
    begin_evidence 'Tests Maven' 'Validar la suite completa' 'mvn test'
    mvn test 2>&1 \
        | sed -E 's/\x1B\[[0-9;]*[mK]//g' \
        | grep -E '^\[INFO\]|^\[ERROR\]|^\[WARNING\]|^Tests run:'
    report_tests 92
) 2>&1 | tee "$EVID/01_maven_test.txt"
~~~

Resultado esperado: Tests run: 92, Failures: 0, Errors: 0, Skipped: 0 y `BUILD SUCCESS`. 
Evidencia: `01_maven_test.txt`.

### Paso 02 - Docker build

Construir las ocho imágenes propias declaradas por el Compose actual. Los secretos definidos en el Paso 00 se utilizarán al arrancar Authorization Server.

~~~bash
(
    set -e
    begin_evidence 'Docker build' 'Construir ocho imágenes propias' 'docker compose build'
    docker compose build
    echo 'RESULTADO: PASS'
) 2>&1 | tee "$EVID/02_docker_compose_build.txt"
~~~

Resultado esperado: build exitoso de authorization-server, config-server, eureka-server, core-banking-service, los tres BFF y batch-init. 
Evidencia: `02_docker_compose_build.txt`.

### Paso 03 - Reset, arranque y espera de servicios

Este es el **único reset destructivo** de la secuencia. El respaldo del Paso 00 contiene la colección anterior completa. La carpeta activa ya tiene únicamente el nuevo índice 00 y los TXT generados por esta secuencia; no restaurar archivos desde el respaldo. Compose inicia las dependencias y el inicializador; esperar su finalización y los servicios HTTP antes de continuar.

~~~bash
(
    set -e
    begin_evidence 'Reset y arranque' 'Reproducir desde cero' \
        'docker compose down -v --remove-orphans; docker compose up -d'
    docker compose down -v --remove-orphans
    docker compose up -d
    wait_compose_ready
    docker compose ps -a
    echo 'RESULTADO: PASS'
) 2>&1 | tee "$EVID/30_docker_reset_arranque.txt"
~~~

Resultado esperado: PostgreSQL y Kafka disponibles; Config :8888, Eureka :8761, Authorization Server :9000, Core :8081, Web :8082, Mobile :8083 y ATM :8084 disponibles; batch-init `Exited (0)`. 
Evidencia: `30_docker_reset_arranque.txt`.

### Paso 04 - Estado Docker Compose

Distinguir los nueve servicios permanentes del inicializador que ya terminó.

~~~bash
{
    begin_evidence 'Estado Compose' 'Inspeccionar servicios e inicializador' \
        'docker compose ps; docker compose ps -a'
    docker compose ps
    docker compose ps -a
} 2>&1 | tee "$EVID/03_docker_compose_ps.txt"
~~~

Resultado esperado: nueve servicios permanentes healthy y batch-init `Exited (0)`. 
Evidencia: `03_docker_compose_ps.txt`.

### Paso 05 - Healthchecks HTTP

Registrar código y body de los siete endpoints públicos de health. El bloque falla si algún servicio no responde 200/UP.

~~~bash
(
    set -e
    begin_evidence 'Healthchecks' 'Comprobar HTTP 200 y UP' 'GET /actuator/health en siete servicios'
    for port in 8888 8761 9000 8081 8082 8083 8084; do
        http_request GET "http://localhost:$port/actuator/health"
        printf 'Puerto: %s\nHTTP status: %s\n%s\n' "$port" "$HTTP_STATUS" "$HTTP_BODY"
        test "$HTTP_STATUS" = 200
        printf '%s' "$HTTP_BODY" | python3 -c \
            'import json,sys; assert json.load(sys.stdin)["status"] == "UP"'
    done
    echo 'RESULTADO: PASS'
) 2>&1 | tee "$EVID/04_healthchecks.txt"
~~~

Resultado esperado: siete respuestas HTTP 200 y status UP. 
Evidencia: `04_healthchecks.txt`.

### Paso 06 - Token OAuth2 Web

Solicitar un Access Token real con client_credentials y scope web. La función mantiene el JWT en WEB_TOKEN.

~~~bash
request_token web-client web "$WEB_CLIENT_SECRET" WEB_TOKEN '05_oauth2_token_web.txt'
~~~

Resultado esperado: HTTP 200, token_type Bearer, scope web y expires_in positivo, normalmente 1799; WEB_TOKEN disponible en memoria. 
Evidencia: `05_oauth2_token_web.txt`; contiene solamente HTTP status, token_type, scope y expires_in.

### Paso 07 - Token OAuth2 Mobile

Utilizar el cliente y secreto del canal Mobile.

~~~bash
request_token mobile-client mobile "$MOBILE_CLIENT_SECRET" MOBILE_TOKEN '06_oauth2_token_mobile.txt'
~~~

Resultado esperado: HTTP 200, Bearer, scope mobile y vigencia restante de aproximadamente 30 minutos. 
Evidencia: `06_oauth2_token_mobile.txt`; MOBILE_TOKEN completo queda solo en memoria.

### Paso 08 - Token OAuth2 ATM

Obtener el token que se utilizará en saldo, Core y retiro.

~~~bash
request_token atm-client atm "$ATM_CLIENT_SECRET" ATM_TOKEN '07_oauth2_token_atm.txt'
~~~

Resultado esperado: HTTP 200, Bearer y scope atm. Evidencia: `07_oauth2_token_atm.txt`; ATM_TOKEN completo queda solo en memoria.

Los Access Tokens duran 30 minutos. Si expiran durante una reproducción larga, **repetir exclusivamente los pasos 06, 07 y 08** para actualizar las tres variables; no reiniciar el stack ni repetir retiros. Después de reiniciar Authorization Server también deben obtenerse tokens nuevos, porque su clave RSA cambia. Mantener desactivado `set -x` y no imprimir variables, encabezados Authorization ni respuestas completas del endpoint de tokens.

### Paso 09 - Claims JWT en memoria

Decodificar header y payload de los tres tokens y comprobar los campos mediante aserciones locales. Esto comprueba contenido y vigencia; la decodificación Base64 no verifica criptográficamente la firma. Los Resource Servers validan firma RSA, issuer y vigencia en las consultas autorizadas posteriores.

~~~bash
(
    set -e
    begin_evidence 'Claims JWT' 'Comprobar claims públicos sin publicar JWT' 'Decodificación local en Python'
    for channel in WEB MOBILE ATM; do
        variable="${channel}_TOKEN"
        printf '%s' "${!variable}" | python3 -c '
import base64,json,sys,time
channel=sys.argv[1].lower()
parts=sys.stdin.read().strip().split(".")
assert len(parts) == 3
decode=lambda value:json.loads(base64.urlsafe_b64decode(value+"="*((-len(value))%4)))
header,payload=decode(parts[0]),decode(parts[1])
scope=payload["scope"]
scopes=scope.split() if isinstance(scope,str) else scope
assert header["alg"] == "RS256"
assert payload["iss"] == "http://authorization-server:9000"
assert payload["sub"] == channel+"-client"
assert scopes == [channel]
assert payload["exp"]-payload["iat"] == 1800
assert payload["iat"] <= time.time()+60 < payload["exp"]
print(json.dumps({"alg":header["alg"],**{k:payload[k] for k in ("iss","sub","scope","iat","exp")}},ensure_ascii=False))
' "$channel"
    done
    echo 'RESULTADO: PASS'
) 2>&1 | tee "$EVID/08_oauth2_jwt_claims.txt"
~~~

Resultado esperado: alg RS256, sub correspondiente, scope correcto y exp − iat = 1800. Durante Docker, iss debe ser `http://authorization-server:9000`. Local/IDE conserva `http://localhost:9000`; no cambiar esa configuración. 
Evidencia: `08_oauth2_jwt_claims.txt`.

### Paso 10 - Seleccionar cuenta real y consultar saldo ATM

La cuenta se selecciona **antes de todas las consultas que dependen de ACCOUNT_ID**. Elegir una cuenta de la consulta real con saldo suficiente para un retiro de 10000 (Se propone 102). Validar la entrada numérica antes de interpolarla en SQL.

~~~bash
{
    begin_evidence 'Saldo ATM' 'Seleccionar cuenta real con saldo suficiente' \
        'SELECT cuenta_id,saldo_final FROM intereses_mensuales; GET saldo ATM'
    docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U banco_user -d banco_xyz -c \
        "SELECT cuenta_id, saldo_final FROM intereses_mensuales WHERE saldo_final >= 10000 ORDER BY cuenta_id LIMIT 5;"
} 2>&1 | tee "$EVID/14_atm_saldo_autorizado.txt"

select_account() {
    read -r -p 'Cuenta de la consulta anterior: ' ACCOUNT_ID
    [[ "$ACCOUNT_ID" =~ ^[0-9]+$ ]] || { echo 'ERROR: cuenta no numérica.' >&2; return 1; }
    BALANCE_BEFORE=$(docker compose exec -T postgres \
        psql -v ON_ERROR_STOP=1 -At -U banco_user -d banco_xyz -c \
        "SELECT saldo_final FROM intereses_mensuales WHERE cuenta_id = $ACCOUNT_ID;") || return 1
    printf '%s' "$BALANCE_BEFORE" | python3 -c \
        'from decimal import Decimal; import sys; assert Decimal(sys.stdin.read().strip()) >= Decimal("10000")' \
        || return 1
    local attempt
    for attempt in {1..60}; do
        http_request GET "http://localhost:8084/api/bff/atm/accounts/$ACCOUNT_ID/balance" "$ATM_TOKEN" || return 1
        printf 'Espera ATM intento=%s HTTP=%s\n' "$attempt" "$HTTP_STATUS" \
            | tee -a "$EVID/14_atm_saldo_autorizado.txt"
        if [ "$HTTP_STATUS" = 200 ]; then break; fi
        test "$HTTP_STATUS" = 503 || return 1
        sleep 2
    done
    printf 'Cuenta: %s\nSaldo SQL previo: %s\nHTTP status: %s\n%s\n' \
        "$ACCOUNT_ID" "$BALANCE_BEFORE" "$HTTP_STATUS" "$HTTP_BODY" \
        | tee -a "$EVID/14_atm_saldo_autorizado.txt"
    test "$HTTP_STATUS" = 200 || return 1
    printf '%s' "$HTTP_BODY" | python3 -c '
from decimal import Decimal
import json,sys
d=json.load(sys.stdin)
assert int(d["accountId"]) == int(sys.argv[1])
assert Decimal(str(d["balance"])) == Decimal(sys.argv[2])
' "$ACCOUNT_ID" "$BALANCE_BEFORE" || return 1
    echo 'RESULTADO: PASS' | tee -a "$EVID/14_atm_saldo_autorizado.txt"
}
select_account
~~~

Resultado esperado: ACCOUNT_ID de una cuenta existente, saldo SQL ≥ 10000 y ATM HTTP 200 con el mismo saldo. 
Evidencia: `14_atm_saldo_autorizado.txt`.

### Paso 11 - Web sin token

Comprobar el endpoint real del canal Web sin Authorization.

~~~bash
record_get '09_web_sin_token_401.txt' 401 \
    "http://localhost:8082/api/bff/web/accounts/$ACCOUNT_ID"
~~~

Resultado esperado: HTTP 401. 
Evidencia: `09_web_sin_token_401.txt`.

### Paso 12 - Web con scope correcto

Consultar la misma cuenta utilizando WEB_TOKEN.

~~~bash
record_get '10_web_scope_correcto.txt' 200 \
    "http://localhost:8082/api/bff/web/accounts/$ACCOUNT_ID" "$WEB_TOKEN"
~~~

Resultado esperado: HTTP 200 y datos de la cuenta. 
Evidencia: `10_web_scope_correcto.txt`.

### Paso 13 - Web con scope incorrecto

Enviar un token válido del canal ATM al endpoint protegido con SCOPE_web.

~~~bash
record_get '11_web_scope_incorrecto_403.txt' 403 \
    "http://localhost:8082/api/bff/web/accounts/$ACCOUNT_ID" "$ATM_TOKEN"
~~~

Resultado esperado: HTTP 403. 
Evidencia: `11_web_scope_incorrecto_403.txt`.

### Paso 14 - Web con token inválido

Utilizar una cadena inválida explícita, sin publicar un JWT real.

~~~bash
record_get '12_web_token_invalido_401.txt' 401 \
    "http://localhost:8082/api/bff/web/accounts/$ACCOUNT_ID" 'invalid-token'
~~~

Resultado esperado: HTTP 401. 
Evidencia: `12_web_token_invalido_401.txt`.

### Paso 15 - Mobile autorizado

Consultar la cuenta con el scope Mobile.

~~~bash
record_get '13_mobile_autorizado.txt' 200 \
    "http://localhost:8083/api/bff/mobile/accounts/$ACCOUNT_ID" "$MOBILE_TOKEN"
~~~

Resultado esperado: HTTP 200. 
Evidencia: `13_mobile_autorizado.txt`.

### Paso 16 - Core sin token

Comprobar directamente la protección de Core Banking.

~~~bash
record_get '15_core_sin_token_401.txt' 401 \
    "http://localhost:8081/api/core/accounts/$ACCOUNT_ID/balance"
~~~

Resultado esperado: HTTP 401. 
Evidencia: `15_core_sin_token_401.txt`.

### Paso 17 - Core con token válido

Core exige un Access Token válido; la consulta utiliza ATM_TOKEN.

~~~bash
record_get '16_core_token_valido.txt' 200 \
    "http://localhost:8081/api/core/accounts/$ACCOUNT_ID/balance" "$ATM_TOKEN"
~~~

Resultado esperado: HTTP 200. 
Evidencia: `16_core_token_valido.txt`.

### Paso 18 - Publicar retiro ATM y conservar transactionId

La regla real de AsyncWithdrawalService exige monto positivo y múltiplo de 10000. En una reproducción sin operaciones concurrentes, el saldo verificado permite retirar 10000. **Este paso modifica el saldo cuando Core procesa el retiro; ejecutarlo una sola vez.** HTTP 202 confirma aceptación/publicación tras el ACK Kafka, no el resultado bancario final.

~~~bash
submit_withdrawal() {
    http_request POST "http://localhost:8084/api/bff/atm/accounts/$ACCOUNT_ID/withdraw" \
        "$ATM_TOKEN" '{"amount":10000}' || return 1
    {
        begin_evidence 'Retiro ATM' 'Publicar monto válido por Kafka' \
            'POST /api/bff/atm/accounts/$ACCOUNT_ID/withdraw; amount=10000'
        printf 'Cuenta: %s\nHTTP status: %s\n%s\n' "$ACCOUNT_ID" "$HTTP_STATUS" "$HTTP_BODY"
    } | tee "$EVID/17_atm_retiro_pending.txt"
    test "$HTTP_STATUS" = 202 || return 1
    TRANSACTION_ID=$(printf '%s' "$HTTP_BODY" | python3 -c '
import json,sys,uuid
d=json.load(sys.stdin)
assert d["status"] == "PENDING"
print(uuid.UUID(d["transactionId"]))
') || return 1
    printf 'TRANSACTION_ID=%s\nRESULTADO: PASS\n' "$TRANSACTION_ID" \
        | tee -a "$EVID/17_atm_retiro_pending.txt"
}
submit_withdrawal
~~~

Resultado esperado: HTTP 202, PENDING y TRANSACTION_ID extraído de la respuesta real; no reutilizar IDs anteriores. 
Evidencia: `17_atm_retiro_pending.txt`.

### Paso 19 - Esperar estado final del retiro

Consultar exclusivamente TRANSACTION_ID con límite de 60 intentos. PENDING y un 503 transitorio no son estados terminales; no exigir un 503 en el primer intento. Un 401 requiere renovar el token antes de continuar el polling, sin volver a publicar el retiro.

~~~bash
poll_withdrawal() {
    local attempt
    FINAL_STATUS=''
    begin_evidence 'Resultado del retiro' 'Esperar estado terminal y verificar saldo' \
        'GET /api/bff/atm/accounts/transactions/$TRANSACTION_ID; SELECT saldo' \
        | tee "$EVID/18_atm_retiro_resultado_final.txt"
    for attempt in {1..60}; do
        http_request GET "http://localhost:8084/api/bff/atm/accounts/transactions/$TRANSACTION_ID" \
            "$ATM_TOKEN" || return 1
        printf 'Intento: %s\nHTTP status: %s\n%s\n' "$attempt" "$HTTP_STATUS" "$HTTP_BODY" \
            | tee -a "$EVID/18_atm_retiro_resultado_final.txt"
        if [ "$HTTP_STATUS" = 200 ]; then
            FINAL_STATUS=$(printf '%s' "$HTTP_BODY" | python3 -c \
                'import json,sys; print(json.load(sys.stdin)["status"])') || return 1
            if [ "$FINAL_STATUS" = COMPLETED ] || [ "$FINAL_STATUS" = REJECTED ]; then
                break
            fi
        elif [ "$HTTP_STATUS" != 503 ]; then
            echo "ERROR: HTTP inesperado $HTTP_STATUS; no republicar el retiro." >&2
            return 1
        fi
        sleep 2
    done
    if [ "$FINAL_STATUS" != COMPLETED ] && [ "$FINAL_STATUS" != REJECTED ]; then
        echo 'ERROR: timeout; conservar TRANSACTION_ID y revisar Core/Kafka.' >&2
        return 1
    fi
    BALANCE_AFTER=$(docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -At \
        -U banco_user -d banco_xyz -c \
        "SELECT saldo_final FROM intereses_mensuales WHERE cuenta_id = $ACCOUNT_ID;") || return 1
    printf 'Estado final: %s\nSaldo antes: %s\nSaldo después: %s\n' \
        "$FINAL_STATUS" "$BALANCE_BEFORE" "$BALANCE_AFTER" \
        | tee -a "$EVID/18_atm_retiro_resultado_final.txt"
    python3 -c '
from decimal import Decimal
import sys
before,after=map(Decimal,sys.argv[1:3]); state=sys.argv[3]
assert after == (before-Decimal("10000") if state=="COMPLETED" else before)
' "$BALANCE_BEFORE" "$BALANCE_AFTER" "$FINAL_STATUS" || return 1
    echo 'RESULTADO: PASS' | tee -a "$EVID/18_atm_retiro_resultado_final.txt"
}
poll_withdrawal
~~~

Resultado esperado: HTTP 200 con COMPLETED para la cuenta y monto válidos de esta secuencia, y descuento de 10000. Si termina REJECTED, registrar el motivo real y saldo sin descuento; el polling termina igualmente, pero no equivale al retiro exitoso de la evidencia original. 
Evidencia: `18_atm_retiro_resultado_final.txt`.

### Paso 20 - Topics Kafka y particiones

Consultar el broker real desde el contenedor Kafka y describir los tres topics existentes.

~~~bash
(
    set -e
    begin_evidence 'Topics Kafka' 'Comprobar topics y tres particiones' 'kafka-topics.sh --list y --describe'
    docker compose exec -T kafka /opt/kafka/bin/kafka-topics.sh \
        --bootstrap-server localhost:9092 --list
    for topic in bank.withdrawal.requested bank.withdrawal.completed bank.withdrawal.rejected; do
        docker compose exec -T kafka /opt/kafka/bin/kafka-topics.sh \
            --bootstrap-server localhost:9092 --describe --topic "$topic"
    done
) 2>&1 | tee "$EVID/19_kafka_topics_particiones.txt"
~~~

Resultado esperado: los tres topics, PartitionCount: 3 y particiones 0, 1 y 2, con replicación 1. 
Evidencia: `19_kafka_topics_particiones.txt`.

### Paso 21 - Consumer group de Core

Mostrar offsets y miembros después del procesamiento del retiro; no crear otro grupo.

~~~bash
(
    set -e
    begin_evidence 'Consumer group' 'Inspeccionar consumidores, asignaciones y lag' \
        'kafka-consumer-groups.sh --describe --group core-banking-withdrawal-group'
    docker compose exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh \
        --bootstrap-server localhost:9092 --describe --group core-banking-withdrawal-group
    docker compose exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh \
        --bootstrap-server localhost:9092 --describe \
        --group core-banking-withdrawal-group --members --verbose
) 2>&1 | tee "$EVID/20_kafka_consumer_group.txt"
~~~

Resultado esperado: topic, particiones, CONSUMER-ID, CURRENT-OFFSET, LOG-END-OFFSET y LAG; tres miembros y asignaciones de las tres particiones. Tras el procesamiento, la partición utilizada converge a lag 0. Las particiones sin mensajes pueden mostrar offset sin confirmar. 
Evidencia: `20_kafka_consumer_group.txt`.

### Paso 22 - Trazabilidad del retiro Kafka

Filtrar únicamente el transactionId de esta ejecución. El productor del resultado puede aparecer unos instantes después de que la consulta de estado muestre COMPLETED.

~~~bash
(
    set -e
    begin_evidence 'Flujo Kafka' 'Relacionar publicación, consumo y resultado' \
        'docker compose logs --no-color bff-atm-service core-banking-service; filtro TRANSACTION_ID'
    sleep 2
    docker compose logs --no-color bff-atm-service core-banking-service \
        | grep -F "$TRANSACTION_ID"
    docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U banco_user -d banco_xyz -c \
        "SELECT event_type, transaction_id, account_id, amount FROM banking_events WHERE transaction_id = '$TRANSACTION_ID' ORDER BY occurred_at;"
) 2>&1 | tee "$EVID/21_kafka_flujo_retiro.txt"
~~~

Resultado esperado: WithdrawalRequested, log [Kafka][Consumer] con partición/hilo, resultado bancario y publicación en bank.withdrawal.completed o bank.withdrawal.rejected; Event Store de la misma transacción. No volcar miles de líneas ni consumir como resultado nuevo un mensaje antiguo del topic. 
Evidencia: `21_kafka_flujo_retiro.txt`.

### Paso 23 - Test de idempotencia

Ejecutar el test unitario real, sin republicar eventos ni efectuar otro descuento en la base Compose. `surefire.failIfNoSpecifiedTests=false` permite que los módulos previos del reactor sin esa clase participen; no omite el test solicitado.

~~~bash
(
    set -e
    begin_evidence 'Idempotencia' 'Comprobar evento ya procesado' \
        'mvn -pl core-banking-service -am -Dtest=AsyncWithdrawalServiceTest -Dsurefire.failIfNoSpecifiedTests=false test'
    mvn -pl core-banking-service -am -Dtest=AsyncWithdrawalServiceTest \
        -Dsurefire.failIfNoSpecifiedTests=false test 2>&1 \
        | sed -E 's/\x1B\[[0-9;]*[mK]//g' \
        | grep -E '^\[INFO\]|^\[ERROR\]|^\[WARNING\]|^Tests run:'
    report_tests 1 AsyncWithdrawalServiceTest
) 2>&1 | tee "$EVID/22_kafka_idempotencia_test.txt"
~~~

Resultado esperado: AsyncWithdrawalServiceTest.ignoresAnEventAlreadyProcessed PASS; 1 test, cero fallos, errores y omitidos, BUILD SUCCESS. 
Evidencia: `22_kafka_idempotencia_test.txt`.

### Paso 24 - Nueve tests JUnit Resilience4j

Ejecutar exclusivamente las tres clases reales, tres tests por canal. El resumen selecciona sus reportes para no confundirlos con la suite completa conservada en 01.

~~~bash
(
    set -e
    begin_evidence 'Resilience4j JUnit' 'Comprobar estados, fallback y short-circuit' \
        'mvn -pl bff-web-service,bff-mobile-service,bff-atm-service -am -Dtest=WebResilience4jTest,MobileResilience4jTest,AtmResilience4jTest -Dsurefire.failIfNoSpecifiedTests=false test'
    mvn -pl bff-web-service,bff-mobile-service,bff-atm-service -am \
        -Dtest=WebResilience4jTest,MobileResilience4jTest,AtmResilience4jTest \
        -Dsurefire.failIfNoSpecifiedTests=false test 2>&1 \
        | sed -E 's/\x1B\[[0-9;]*[mK]//g' \
        | grep -E '^\[INFO\]|^\[ERROR\]|^\[WARNING\]|^Tests run:'
    report_tests 9 WebResilience4jTest MobileResilience4jTest AtmResilience4jTest
) 2>&1 | tee "$EVID/23_resilience4j_tests.txt"
~~~

Resultado esperado: 9 tests, 0 failures, 0 errors, 0 skipped y BUILD SUCCESS. Los métodos reales comprueban **CLOSED**, **OPEN**, **HALF_OPEN**, **CLOSED recuperado**, fallback y short-circuit mediante CircuitBreakerRegistry y métricas; el tercer intento con circuito abierto no llega a Core. Sus overrides exclusivos de test (ventana/mínimo 2 y espera 1s) no sustituyen los parámetros productivos 5/5/50/5s/2. 
Evidencia: `23_resilience4j_tests.txt`.

### Paso 25 - Regresión Spring Batch y Jobs existentes

Ejecutar los tres tests de integración reales, que utilizan su entorno de pruebas. Después inspeccionar los Jobs ya ejecutados por batch-init en el Paso 03; no relanzar el inicializador.

~~~bash
(
    set -e
    begin_evidence 'Regresión Batch' 'Relacionar tres tests con tres Jobs reales' \
        'mvn -pl core-banking-service -am -Dtest=DailyTransactionsJobIntegrationTest,MonthlyInterestJobIntegrationTest,AnnualMovementsStepIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test; SELECT y logs batch-init'
    mvn -pl core-banking-service -am \
        -Dtest=DailyTransactionsJobIntegrationTest,MonthlyInterestJobIntegrationTest,AnnualMovementsStepIntegrationTest \
        -Dsurefire.failIfNoSpecifiedTests=false test 2>&1 \
        | sed -E 's/\x1B\[[0-9;]*[mK]//g' \
        | grep -E '^\[INFO\]|^\[ERROR\]|^\[WARNING\]|^Tests run:'
    report_tests 3 DailyTransactionsJobIntegrationTest MonthlyInterestJobIntegrationTest AnnualMovementsStepIntegrationTest
    batch_logs=$(docker compose logs --no-color batch-init)
    for job in dailyTransactionsJob monthlyInterestJob annualStatementsJob; do
        printf '%s\n' "$batch_logs" | grep -E "$job.*COMPLETED"
    done
    printf '%s\n' "$batch_logs" \
        | grep -E 'dailyTransactionsJob.*COMPLETED|monthlyInterestJob.*COMPLETED|annualStatementsJob.*COMPLETED|BATCH INIT COMPLETED'
    docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U banco_user -d banco_xyz -c \
        "SELECT i.job_name, e.job_execution_id, e.status FROM batch_job_instance i JOIN batch_job_execution e USING (job_instance_id) ORDER BY e.job_execution_id;"
    echo 'RESULTADO: PASS'
) 2>&1 | tee "$EVID/24_batch_regresion.txt"
~~~

Resultado esperado: DailyTransactionsJobIntegrationTest, MonthlyInterestJobIntegrationTest y AnnualMovementsStepIntegrationTest PASS; 3 tests sin fallos, errores ni omitidos, BUILD SUCCESS. Los Jobs reales dailyTransactionsJob, monthlyInterestJob y annualStatementsJob muestran COMPLETED y el log incluye BATCH INIT COMPLETED. 
Evidencia: `24_batch_regresion.txt`.

### Paso 26 - Imágenes propias del proyecto

Inspeccionar únicamente las ocho imágenes construidas por este Compose, con el nombre de proyecto derivado del directorio proyecto. Esto evita depender de otras imágenes presentes en el equipo.

~~~bash
(
    set -e
    begin_evidence 'Imágenes Docker' 'Inspeccionar ocho imágenes propias' 'docker image inspect proyecto-<servicio>:latest'
    for service in authorization-server config-server eureka-server core-banking-service \
        bff-web-service bff-mobile-service bff-atm-service batch-init; do
        docker image inspect --format '{{.RepoTags}} {{.Id}} bytes={{.Size}}' \
            "proyecto-$service:latest"
    done
    echo 'RESULTADO: PASS'
) 2>&1 | tee "$EVID/25_docker_images.txt"
~~~

Resultado esperado: ocho imágenes propias con tag, ID y tamaño; PostgreSQL y Kafka utilizan sus imágenes de infraestructura. 
Evidencia: `25_docker_images.txt`. No establecer otro COMPOSE_PROJECT_NAME ni utilizar -p durante esta secuencia.

### Paso 27 - Servicios registrados en Eureka

Esperar el registro de los cuatro servicios de negocio y comprobar sus estados XML, sin asumir que la sola presencia del nombre implica UP.

~~~bash
(
    set -e
    begin_evidence 'Eureka' 'Comprobar cuatro servicios UP' 'GET http://localhost:8761/eureka/apps'
    wait_eureka
    echo 'RESULTADO: PASS'
) 2>&1 | tee "$EVID/26_eureka_servicios.txt"
~~~

Resultado esperado: CORE-BANKING-SERVICE, BFF-WEB-SERVICE, BFF-MOBILE-SERVICE y BFF-ATM-SERVICE UP. 
Evidencia: `26_eureka_servicios.txt`. Antes de las primeras consultas autorizadas, si el descubrimiento aún devuelve 503, esperar y repetir la consulta correspondiente; conservar el diagnóstico y no publicar otro retiro.

### Paso 28 - Config Server sin secretos

Consultar los cuatro endpoints de Config Server y guardar en la evidencia solo las propiedades necesarias, evitando registrar credenciales. La configuración usa el issuer local fuera de Docker y el issuer interno cuando se ejecuta con Docker Compose.

~~~bash
(
    set -e
    begin_evidence 'Config Server' 'Comprobar configuración centralizada y fuentes' 'GET /<servicio>/default; selección de propiedades públicas'
    for service in core-banking-service bff-web-service bff-mobile-service bff-atm-service; do
        curl -fsS --max-time 15 "http://localhost:8888/$service/default" | python3 -c '
import json,sys
d=json.load(sys.stdin)
allowed={"server.port","spring.security.oauth2.resourceserver.jwt.issuer-uri",
         "spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
         "spring.kafka.consumer.group-id","spring.kafka.listener.concurrency",
         "spring.kafka.producer.value-serializer"}
sources=[{"name":p["name"],"source":{k:v for k,v in p["source"].items() if k in allowed}}
         for p in d["propertySources"]]
assert d["name"] == sys.argv[1] and sources
assert any(sys.argv[1]+".yml" in p["name"] for p in sources)
print(json.dumps({"name":d["name"],"profiles":d["profiles"],"propertySources":sources},ensure_ascii=False))
' "$service"
    done
    echo 'RESULTADO: PASS'
) 2>&1 | tee "$EVID/27_config_server.txt"
~~~

Resultado esperado: cuatro respuestas HTTP 200 (curl -f rechaza errores), name/profiles y propertySources procedentes de config-repo, con propiedades públicas seleccionadas. 
Evidencia: `27_config_server.txt`.

### Paso 29 - Verificación documental y alcance

Revisar la estructura del proyecto y comprobar la existencia de las tres clases de prueba de Resilience4j y sus nueve métodos de prueba. Verificar además que los componentes y dependencias JWT propios de la implementación anterior hayan sido eliminados, que la evidencia 01 registre los 92 tests exitosos y que la estructura documental de Semana 08 sea correcta.

~~~bash
(
    set -e
    begin_evidence 'Verificación documental' 'Comprobar tests, migración e inventario' \
        'Lectura de fuentes/POM/evidencias; git status --short'
    python3 - "$EVID" <<'PY'
import pathlib,re,sys
root=pathlib.Path("."); evid=pathlib.Path(sys.argv[1])
methods=("circuitBreakerStartsClosedAndAllowsSuccessfulCall",
         "circuitBreakerOpensAfterConfiguredFailuresAndShortCircuits",
         "circuitBreakerRecoversThroughHalfOpenAndReturnsToClosed")
count=0
for channel,cls in (("web","Web"),("mobile","Mobile"),("atm","Atm")):
    path=root/f"bff-{channel}-service/src/test/java/cl/duoc/bancoxyz/{channel}/{cls}Resilience4jTest.java"
    text=path.read_text()
    for method in methods:
        assert re.search(r"@Test\s+void\s+"+method+r"\(",text), (path,method)
        count+=1
        print(path,method,"PRESENTE")
    for old in ("AuthController","JwtService","JwtAuthenticationFilter"):
        matches=list((root/f"bff-{channel}-service/src/main").rglob(old+".java"))
        assert not matches, matches
        print(channel,old,"AUSENTE")
assert count == 9
for pom in [root/"pom.xml",*root.glob("*/pom.xml")]:
    assert not re.search(r"\bjjwt(?:-api|-impl|-jackson)?\b",pom.read_text()), pom
print("Dependencias jjwt propias: AUSENTES")
full=(evid/"01_maven_test.txt").read_text()
for line in ("Tests run: 92","Failures: 0","Errors: 0","Skipped: 0","BUILD SUCCESS","RESULTADO: PASS"):
    assert line in full, line
    print(line)
doc=pathlib.Path("../README.md").read_text()
assert len(re.findall(r"^# Semana 08 - .+$",doc,re.M)) == 1
assert re.search(r"^## Ejecución(?: manual reproducible)?$",doc,re.M)
for path in sorted(evid.iterdir()):
    if path.is_file():
        print(path.name)
print("00: índice nuevo. 28 y 31: runtime seguro generado en el Paso 30. 33: runtime definitiva.")
PY
    GIT_OPTIONAL_LOCKS=0 git status --short
    echo 'RESULTADO: PASS'
) 2>&1 | tee "$EVID/29_verificacion_documental_alcance.txt"
~~~

Resultado esperado: tres clases de prueba de Resilience4j con nueve métodos en total, componentes JWT anteriores y dependencias jjwt ausentes, evidencia 01 con 92 tests y BUILD SUCCESS, estructura documental válida y RESULTADO: PASS.
Evidencia: `29_verificacion_documental_alcance.txt`.

### Paso 30 - Runtime Resilience4j y recuperación segura

Comprobar la respuesta de Web, Mobile y ATM antes, durante y después de detener Core, verificando la secuencia HTTP 200 → 503 → 200. La prueba no realiza retiros ni modificaciones SQL y genera las evidencias 28 y 31 en una misma ejecución. Si los tokens OAuth2 han vencido, deben renovarse antes de comenzar.

Antes y después de la prueba se comprueba el estado de batch-init y los saldos de las cuentas, para verificar que Batch no se reejecute y que los datos no sean modificados. Si ocurre un error mientras Core está detenido, el procedimiento intenta iniciarlo nuevamente antes de finalizar.

~~~bash
batch_snapshot() {
    local id
    id=$(docker compose ps -a -q batch-init) || return 1
    test -n "$id" || return 1
    docker inspect --format '{{.Id}} {{.State.Status}} {{.State.ExitCode}} {{.State.StartedAt}} {{.RestartCount}}' "$id"
}

balances_snapshot() {
    docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -At -U banco_user -d banco_xyz -c \
        'SELECT cuenta_id, saldo_final FROM intereses_mensuales ORDER BY cuenta_id;'
}

runtime_call() {
    local channel="$1" phase="$2" attempt="$3" url token
    case "$channel" in
        web) url="http://localhost:8082/api/bff/web/accounts/$ACCOUNT_ID"; token="$WEB_TOKEN" ;;
        mobile) url="http://localhost:8083/api/bff/mobile/accounts/$ACCOUNT_ID"; token="$MOBILE_TOKEN" ;;
        atm) url="http://localhost:8084/api/bff/atm/accounts/$ACCOUNT_ID/balance"; token="$ATM_TOKEN" ;;
        *) return 1 ;;
    esac
    http_request GET "$url" "$token" || return 1
    printf '%s %s intento=%s HTTP=%s %s\n' "$phase" "$channel" "$attempt" "$HTTP_STATUS" "$HTTP_BODY"
}

resilience_runtime() (
    set -e
    local calls="$1" file="$2" channel attempt round consecutive
    local core_stopped=0 batch_before batch_after balances_before balances_after
    restore_core() {
        if [ "$core_stopped" -eq 1 ]; then
            echo 'Guarda: recuperar exclusivamente Core.'
            docker compose restart --no-deps core-banking-service
            wait_url http://localhost:8081/actuator/health 'Core tras guarda'
        fi
    }
    trap restore_core EXIT
    trap 'exit 130' INT
    trap 'exit 143' TERM

    begin_evidence "$file" 'Comprobar fallback, recuperación y conservación de Batch/saldos' \
        'GET por canal; docker compose stop core-banking-service; docker compose restart --no-deps core-banking-service; inspección y SELECT'
    batch_before=$(batch_snapshot)
    [[ "$batch_before" == *" exited 0 "* ]]
    balances_before=$(balances_snapshot)
    test -n "$balances_before"
    printf 'batch-init antes: %s\nSaldos antes:\n%s\n' "$batch_before" "$balances_before"

    for channel in web mobile atm; do
        for attempt in {1..60}; do
            runtime_call "$channel" DISPONIBLE "$attempt"
            if [ "$HTTP_STATUS" = 200 ]; then break; fi
            test "$HTTP_STATUS" = 503
            sleep 2
        done
        test "$HTTP_STATUS" = 200
    done

    core_stopped=1
    docker compose stop core-banking-service
    test "$(docker inspect --format '{{.State.Status}}' "$(docker compose ps -a -q core-banking-service)")" = exited
    for ((round=1; round<=calls; round++)); do
        for channel in web mobile atm; do
            runtime_call "$channel" DETENIDO "$round"
            test "$HTTP_STATUS" = 503
        done
    done

    docker compose restart --no-deps core-banking-service
    wait_url http://localhost:8081/actuator/health 'Core recuperado'
    core_stopped=0
    sleep 6
    for channel in web mobile atm; do
        consecutive=0
        for attempt in {1..60}; do
            runtime_call "$channel" RECUPERADO "$attempt"
            if [ "$HTTP_STATUS" = 200 ]; then
                consecutive=$((consecutive+1))
                if [ "$consecutive" -eq 3 ]; then break; fi
            else
                consecutive=0
                test "$HTTP_STATUS" = 503
            fi
            sleep 2
        done
        test "$consecutive" -eq 3
    done

    batch_after=$(batch_snapshot)
    balances_after=$(balances_snapshot)
    printf 'batch-init después: %s\nSaldos después:\n%s\n' "$batch_after" "$balances_after"
    test "$batch_before" = "$batch_after"
    test "$balances_before" = "$balances_after"
    echo 'batch-init no reejecutado: PASS'
    echo 'Balances no modificados: PASS'
    echo 'RESULTADO: PASS'
    trap - EXIT INT TERM
)

resilience_runtime 1 'Runtime Resilience4j y recuperación segura - evidencias 28 y 31' \
    2>&1 | tee "$EVID/28_resilience4j_runtime.txt" \
                   "$EVID/31_resilience4j_recuperacion_sin_dependencias.txt"
~~~

Resultado esperado: Web, Mobile y ATM deben responder HTTP 503 mientras Core está detenido y recuperar posteriormente tres respuestas HTTP 200 consecutivas por canal. batch-init no debe volver a ejecutarse y los saldos deben permanecer sin cambios.
Evidencias: `28_resilience4j_runtime.txt` y `31_resilience4j_recuperacion_sin_dependencias.txt`, generadas en la misma ejecución. La recuperación reinicia únicamente core-banking-service, sin reiniciar sus dependencias.

### Paso 31 - Configuración productiva de Resilience4j

Consultar Config Server y verificar los valores efectivos de Resilience4j para cada circuito, identificando además la fuente de configuración utilizada. La comprobación se realiza sobre la respuesta real de Config Server y no solo sobre los archivos YAML locales.

~~~bash
(
    set -e
    begin_evidence 'Resilience4j productivo' 'Comprobar configuración centralizada efectiva' \
        'GET /bff-web-service/default, /bff-mobile-service/default, /bff-atm-service/default'
    for pair in 'bff-web-service coreWeb' 'bff-mobile-service coreMobile' 'bff-atm-service coreAtm'; do
        read -r service circuit <<< "$pair"
        curl -fsS --max-time 15 "http://localhost:8888/$service/default" | python3 -c '
import json,sys
d=json.load(sys.stdin); service,circuit=sys.argv[1:3]
assert d["name"] == service
expected={"sliding-window-size":5,"minimum-number-of-calls":5,"failure-rate-threshold":50,
          "wait-duration-in-open-state":"5s","permitted-number-of-calls-in-half-open-state":2}
values={}; sources={}
for field,wanted in expected.items():
    key="resilience4j.circuitbreaker.instances."+circuit+"."+field
    for source in d["propertySources"]:
        if key in source["source"]:
            values[field]=source["source"][key]; sources[field]=source["name"]
            break
    assert field in values, key
    actual=str(values[field]) if isinstance(wanted,str) else int(values[field])
    assert actual == wanted, (circuit,field,actual,wanted)
print(json.dumps({"endpoint":"http://localhost:8888/"+service+"/default","HTTP":200,
                  "circuit":circuit,"values":values,"sources":sources},ensure_ascii=False))
' "$service" "$circuit"
    done
    echo 'RESULTADO: PASS'
) 2>&1 | tee "$EVID/32_resilience4j_configuracion_productiva.txt"
~~~

Resultado esperado: coreWeb, coreMobile y coreAtm con sliding-window-size=5, minimum-number-of-calls=5, failure-rate-threshold=50, wait-duration-in-open-state=5s y permitted-number-of-calls-in-half-open-state=2, entregados por Config Server. 
Evidencia: `32_resilience4j_configuracion_productiva.txt`.

### Paso 32 - Runtime definitivo Resilience4j

Reutilizar la prueba del Paso 30, realizando seis consultas por cada canal mientras Core está detenido. La validación utiliza únicamente consultas GET de cuenta y saldo, sin ejecutar retiros ni modificar balances. Si los tokens OAuth2 han vencido, deben renovarse antes de comenzar, manteniendo la misma cuenta seleccionada.

~~~bash
resilience_runtime 6 '33_resilience4j_runtime_final.txt' \
    2>&1 | tee "$EVID/33_resilience4j_runtime_final.txt"
~~~

Resultado esperado: con Core disponible, Web, Mobile y ATM responden HTTP 200. Con Core detenido, cada canal registra seis respuestas HTTP 503. Después de reiniciar únicamente Core, se verifica su healthcheck y la recuperación de los tres canales mediante tres respuestas HTTP 200 consecutivas. batch-init no debe reejecutarse y los saldos deben permanecer sin cambios.
Evidencia: `33_resilience4j_runtime_final.txt` — evidencia runtime definitiva.
Esta prueba demuestra el fallback observable durante la caída de Core y la posterior recuperación del servicio. Los estados internos OPEN y HALF_OPEN de Resilience4j se validan mediante las pruebas JUnit correspondientes.

### Paso 33 - Verificación final Semana 08

Realizar la verificación final de las evidencias de Semana 08, comprobando el estado de Docker Compose y la información de Kafka. Este paso no ejecuta nuevas pruebas ni modifica servicios o datos; solo genera el resumen final.
Se verifica que la carpeta activa contenga exactamente las 35 evidencias esperadas, que todas estén generadas durante la ejecución actual y que no existan archivos adicionales. Los respaldos externos no forman parte de esta comprobación.

~~~bash
begin_evidence 'Verificación final Semana 08' 'Resumir criterios reales de la ejecución manual' \
    'Lectura de las 35 evidencias nuevas; docker compose ps -a; docker inspect; consultas Kafka' \
    | tee "$EVID/34_verificacion_final_semana_08.txt"

(
    set -e
    trap 'verification_status=$?; if [ "$verification_status" -eq 0 ]; then echo "RESULTADO FINAL: PASS"; else echo "RESULTADO FINAL: FAIL - código $verification_status"; fi' EXIT
    python3 - "$EVID" "$RUN_STARTED" "${EXPECTED_EVIDENCES[@]}" <<'PY'
import datetime,json,pathlib,re,sys
evid=pathlib.Path(sys.argv[1]); started=datetime.datetime.fromisoformat(sys.argv[2]).timestamp()
expected=sys.argv[3:]
assert len(expected) == 35
print("=== INVENTARIO Y PROCEDENCIA ===")
active=pathlib.Path("/home/francisco/proyecto/BACKEND III/SEMANA 8/Exp3_S8_Francisco_Henriquez/evidencias/semana 08")
assert evid == active and evid.resolve() == active and not evid.is_symlink()
assert len(set(expected)) == 35
observed={path.name for path in evid.iterdir()}
assert observed == set(expected), {"faltantes":sorted(set(expected)-observed),"extras":sorted(observed-set(expected))}
for name in expected:
    path=evid/name
    assert path.is_file() and not path.is_symlink(), ("No es archivo regular propio",name)
    info=path.stat()
    assert info.st_size > 0, ("Evidencia vacía",name)
    assert info.st_mtime > started, ("Evidencia anterior o igual a RUN_STARTED",name)
    print(name,"bytes="+str(info.st_size),"mtime="+datetime.datetime.fromtimestamp(info.st_mtime).astimezone().isoformat())
print("Inventario: PASS - 35 archivos nuevos, no vacíos, posteriores a RUN_STARTED.")
print("00: índice nuevo de esta reproducción; 28 y 31: runtime seguro generado conjuntamente.")
print("33: EVIDENCIA RUNTIME DEFINITIVA. El respaldo no interviene en la comprobación.")

def content(prefix):
    matches=[n for n in expected if n.startswith(prefix+"_")]
    assert len(matches) == 1
    return (evid/matches[0]).read_text()

def require(prefix,pattern):
    assert re.search(pattern,content(prefix),re.M), (prefix,pattern)

for prefix,total in (("01",92),("22",1),("23",9),("24",3)):
    for pattern in (rf"^Tests run: {total}$",r"^Failures: 0$",r"^Errors: 0$",r"^Skipped: 0$",
                    r"BUILD SUCCESS",r"^RESULTADO: PASS$"):
        require(prefix,pattern)
    print(f"Maven {prefix}: {total}/{total}; 0 failures/errors/skipped; BUILD SUCCESS.")
require("22",r"ignoresAnEventAlreadyProcessed: PASS")
for method in ("circuitBreakerStartsClosedAndAllowsSuccessfulCall",
               "circuitBreakerOpensAfterConfiguredFailuresAndShortCircuits",
               "circuitBreakerRecoversThroughHalfOpenAndReturnsToClosed"):
    assert content("23").count(method+": PASS") == 3, method
print("Resilience4j JUnit: CLOSED, OPEN, HALF_OPEN, CLOSED recuperado, fallback y short-circuit PASS.")

for prefix,code in (("09",401),("10",200),("11",403),("12",401),("13",200),
                    ("14",200),("15",401),("16",200),("17",202),("18",200)):
    require(prefix,rf"^HTTP status: {code}$")
    require(prefix,r"^RESULTADO: PASS$")
    print(f"HTTP evidencia {prefix}: {code} PASS.")
for prefix,scope in (("05","web"),("06","mobile"),("07","atm")):
    text=content(prefix)
    assert "HTTP status: 200" in text
    metadata=json.loads(next(line for line in text.splitlines() if line.startswith("{")))
    assert metadata["scope"] == scope and metadata["token_type"].lower() == "bearer"
    assert 0 < int(metadata["expires_in"]) <= 1800
    print(f"OAuth2 {scope}: HTTP 200, Bearer, scope y expires_in PASS.")
require("08",r"^RESULTADO: PASS$")
print("JWT claims: RS256, issuer Docker, sub, scope y vigencia PASS.")
require("17",r'"status"\s*:\s*"PENDING"')
require("18",r"^Estado final: COMPLETED$")
print("Retiro: 202/PENDING -> 200/COMPLETED; saldo comprobado. Core protegido: 401/200.")

for topic in ("bank.withdrawal.requested","bank.withdrawal.completed","bank.withdrawal.rejected"):
    assert topic in content("19"), topic
assert re.search(r"Topic:\s*bank\.withdrawal\.requested.*PartitionCount:\s*3",content("19"))
require("20",r"core-banking-withdrawal-group")
for field in ("CURRENT-OFFSET","LOG-END-OFFSET","LAG","CONSUMER-ID"):
    assert field in content("20"), field
for marker in ("WithdrawalRequested","[Kafka][Consumer]","bank.withdrawal.completed","WithdrawalCompleted"):
    assert marker in content("21"), marker
print("Kafka: tres topics, tres particiones, consumer group, offsets/lag y trazabilidad PASS.")
for job in ("dailyTransactionsJob","monthlyInterestJob","annualStatementsJob"):
    assert re.search(job+r".*COMPLETED",content("24")), job
require("24",r"BATCH INIT COMPLETED")
print("Spring Batch: tres tests y tres Jobs COMPLETED PASS.")

for prefix in ("02","04","25","26","27","28","29","30","31","32","33"):
    require(prefix,r"^RESULTADO: PASS$")
print("Build, health, ocho imágenes, Eureka, Config Server y alcance documental PASS.")
for service in ("authorization-server","config-server","eureka-server","core-banking-service",
                "bff-web-service","bff-mobile-service","bff-atm-service","batch-init"):
    assert "proyecto-"+service+":latest" in content("25"), service
for service in ("CORE-BANKING-SERVICE","BFF-WEB-SERVICE","BFF-MOBILE-SERVICE","BFF-ATM-SERVICE"):
    assert service+" UP" in content("26"), service

configs=[json.loads(line) for line in content("32").splitlines() if line.startswith("{")]
wanted={"sliding-window-size":5,"minimum-number-of-calls":5,"failure-rate-threshold":50,
        "wait-duration-in-open-state":"5s","permitted-number-of-calls-in-half-open-state":2}
assert {d["circuit"] for d in configs} == {"coreWeb","coreMobile","coreAtm"}
for config in configs:
    assert config["HTTP"] == 200
    for key,value in wanted.items():
        actual=config["values"][key]
        assert (str(actual) if isinstance(value,str) else int(actual)) == value
    print(config["circuit"],config["values"])
print("Configuración productiva desde Config Server: PASS.")

for prefix,calls in (("28",1),("31",1),("33",6)):
    text=content(prefix)
    for channel in ("web","mobile","atm"):
        assert re.search(rf"^DISPONIBLE {channel} intento=\d+ HTTP=200 ",text,re.M)
        failures=re.findall(rf"^DETENIDO {channel} intento=(\d+) HTTP=503 ",text,re.M)
        assert failures == [str(i) for i in range(1,calls+1)], (prefix,channel,failures)
        assert len(re.findall(rf"^RECUPERADO {channel} intento=\d+ HTTP=200 ",text,re.M)) >= 3
    assert "batch-init no reejecutado: PASS" in text and "Balances no modificados: PASS" in text
    print(f"Runtime {prefix}: 200 -> {calls} respuestas 503 por canal -> 200; Batch y balances conservados PASS.")
print("Comprobaciones documentales de esta ejecución: PASS.")
PY

    echo '=== COMPOSE ACTUAL ==='
    docker compose ps -a
    for service in postgres kafka config-server eureka-server authorization-server \
        core-banking-service bff-web-service bff-mobile-service bff-atm-service; do
        id=$(docker compose ps -a -q "$service")
        test -n "$id"
        state=$(docker inspect --format '{{.State.Status}} {{.State.Health.Status}}' "$id")
        printf '%s: %s\n' "$service" "$state"
        test "$state" = 'running healthy'
    done
    batch_snapshot
    [[ "$(batch_snapshot)" == *" exited 0 "* ]]

    echo '=== KAFKA ACTUAL ==='
    topics=$(docker compose exec -T kafka /opt/kafka/bin/kafka-topics.sh \
        --bootstrap-server localhost:9092 --describe --topic bank.withdrawal.requested)
    printf '%s\n' "$topics"
    printf '%s\n' "$topics" | grep -E 'PartitionCount:[[:space:]]*3'
    group=$(docker compose exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh \
        --bootstrap-server localhost:9092 --describe --group core-banking-withdrawal-group)
    printf '%s\n' "$group"
    printf '%s\n' "$group" | grep 'core-banking-withdrawal-group'
) 2>&1 | tee -a "$EVID/34_verificacion_final_semana_08.txt"
~~~

Resultado esperado: la carpeta debe contener exactamente las 35 evidencias de Semana 08, todas generadas durante la ejecución actual y ninguna vacía. La verificación final debe confirmar Maven con 92/92 tests y BUILD SUCCESS, seguridad OAuth2/JWT, validaciones HTTP, retiro ATM, Kafka, idempotencia, Resilience4j, Spring Batch y configuración productiva con resultado correcto. Los nueve servicios deben permanecer healthy y batch-init en estado Exited (0).
Evidencia: `34_verificacion_final_semana_08.txt`. El procedimiento registra RESULTADO FINAL: PASS cuando todas las comprobaciones terminan correctamente y RESULTADO FINAL: FAIL si alguna falla.

### Mapa paso / evidencia

El número de paso sigue el orden de ejecución; el número del TXT conserva el inventario existente.

| Paso | Evidencia                                                                          | Objetivo                                                    |
| ---- | ---------------------------------------------------------------------------------- | ----------------------------------------------------------- |
| 00   | `00_indice_evidencias.md`                                                          | Respaldo verificado, limpieza e índice nuevo                |
| 01   | `01_maven_test.txt`                                                                | Suite Maven: 92 tests                                       |
| 02   | `02_docker_compose_build.txt`                                                      | Ocho imágenes propias                                       |
| 03   | `30_docker_reset_arranque.txt`                                                     | Reset único, arranque y espera                              |
| 04   | `03_docker_compose_ps.txt`                                                         | Estado Compose                                              |
| 05   | `04_healthchecks.txt`                                                              | Healthchecks                                                |
| 06   | `05_oauth2_token_web.txt`                                                          | OAuth2 Web                                                  |
| 07   | `06_oauth2_token_mobile.txt`                                                       | OAuth2 Mobile                                               |
| 08   | `07_oauth2_token_atm.txt`                                                          | OAuth2 ATM                                                  |
| 09   | `08_oauth2_jwt_claims.txt`                                                         | Claims JWT                                                  |
| 10   | `14_atm_saldo_autorizado.txt`                                                      | Cuenta real y saldo ATM                                     |
| 11   | `09_web_sin_token_401.txt`                                                         | Web sin token:401                                           |
| 12   | `10_web_scope_correcto.txt`                                                        | Web con scope: 200                                          |
| 13   | `11_web_scope_incorrecto_403.txt`                                                  | Web con scope ATM: 403                                      |
| 14   | `12_web_token_invalido_401.txt`                                                    | Token inválido: 401                                         |
| 15   | `13_mobile_autorizado.txt`                                                         | Mobile: 200                                                 |
| 16   | `15_core_sin_token_401.txt`                                                        | Core sin token: 401                                         |
| 17   | `16_core_token_valido.txt`                                                         | Core con token: 200                                         |
| 18   | `17_atm_retiro_pending.txt`                                                        | Retiro 202/PENDING                                          |
| 19   | `18_atm_retiro_resultado_final.txt`                                                | Polling terminal y saldo                                    |
| 20   | `19_kafka_topics_particiones.txt`                                                  | Topics y particiones                                        |
| 21   | `20_kafka_consumer_group.txt`                                                      | Consumer group                                              |
| 22   | `21_kafka_flujo_retiro.txt`                                                        | Trazabilidad Kafka                                          |
| 23   | `22_kafka_idempotencia_test.txt`                                                   | Idempotencia                                                |
| 24   | `23_resilience4j_tests.txt`                                                        | Nueve tests Resilience4j                                    |
| 25   | `24_batch_regresion.txt`                                                           | Tres tests y Jobs Batch                                     |
| 26   | `25_docker_images.txt`                                                             | Imágenes del proyecto                                       |
| 27   | `26_eureka_servicios.txt`                                                          | Eureka UP                                                   |
| 28   | `27_config_server.txt`                                                             | Config Server sin secretos                                  |
| 29   | `29_verificacion_documental_alcance.txt`                                           | Inspección documental                                       |
| 30   | `28_resilience4j_runtime.txt`, `31_resilience4j_recuperacion_sin_dependencias.txt` | Una comprobación runtime segura capturada en dos TXT nuevos |
| 31   | `32_resilience4j_configuracion_productiva.txt`                                     | Parámetros productivos desde Config                         |
| 32   | `33_resilience4j_runtime_final.txt`                                                | Runtime definitivo sin retiros                              |
| 33   | `34_verificacion_final_semana_08.txt`                                              | Verificación final                                          |

## Colección final esperada - Semana 08

La carpeta activa `evidencias/semana 08` queda formada exclusivamente por **35 evidencias**: el índice 00 y los 34 TXT 01–34. El Paso 00 crea el índice desde cero y el Paso 30 genera conjuntamente 28 y 31 con una única ejecución runtime segura. El respaldo está fuera de la carpeta activa y solo sirve para recuperación; no integra la entrega ni se lee para validar o reconstruir evidencias. Registrar las salidas reales, incluidos errores o estados transitorios.

1. `00_indice_evidencias.md` — Índice generado en el Paso 00.
2. `01_maven_test.txt`
3. `02_docker_compose_build.txt`
4. `03_docker_compose_ps.txt`
5. `04_healthchecks.txt`
6. `05_oauth2_token_web.txt`
7. `06_oauth2_token_mobile.txt`
8. `07_oauth2_token_atm.txt`
9. `08_oauth2_jwt_claims.txt`
10. `09_web_sin_token_401.txt`
11. `10_web_scope_correcto.txt`
12. `11_web_scope_incorrecto_403.txt`
13. `12_web_token_invalido_401.txt`
14. `13_mobile_autorizado.txt`
15. `14_atm_saldo_autorizado.txt`
16. `15_core_sin_token_401.txt`
17. `16_core_token_valido.txt`
18. `17_atm_retiro_pending.txt`
19. `18_atm_retiro_resultado_final.txt`
20. `19_kafka_topics_particiones.txt`
21. `20_kafka_consumer_group.txt`
22. `21_kafka_flujo_retiro.txt`
23. `22_kafka_idempotencia_test.txt`
24. `23_resilience4j_tests.txt`
25. `24_batch_regresion.txt`
26. `25_docker_images.txt`
27. `26_eureka_servicios.txt`
28. `27_config_server.txt`
29. `28_resilience4j_runtime.txt` — Runtime seguro generado en el Paso 30, junto con 31.
30. `29_verificacion_documental_alcance.txt`
31. `30_docker_reset_arranque.txt`
32. `31_resilience4j_recuperacion_sin_dependencias.txt`
33. `32_resilience4j_configuracion_productiva.txt`
34. `33_resilience4j_runtime_final.txt` — **EVIDENCIA RUNTIME DEFINITIVA.**
35. `34_verificacion_final_semana_08.txt`

## Diagrama de arquitectura - Semana 08

Como documentación complementaria, se incluye un diagrama de la arquitectura final del proyecto correspondiente a Semana 08, generado mediante Archify.

El diagrama representa de forma visual los principales componentes e interacciones de la solución desarrollada.

Diagrama: [arquitectura_semana08.html](documentacion/semana-08/arquitectura_semana08.html)

Archivos asociados:

`documentacion/semana-08/`


# EFT

## 1. Objetivo EFT

Banco XYZ implementa Gestion de Cuentas, Gestion de Pagos y Gestion de Clientes como tres microservicios Java 17. Conserva los BFF Web, Mobile y ATM, OAuth2/JWT, Spring Batch, PostgreSQL, Kafka, Config Server, Eureka, Spring Cloud LoadBalancer y Resilience4j.

## 2. Requerimientos de negocio

Los tres requerimientos centrales justifican la separacion de servicios: Clientes mantiene identidad, datos y perfiles para validar la asociacion de cuentas; Cuentas concentra el ciclo administrativo y los saldos bajo una transaccion local para preservar la integridad monetaria; Pagos coordina las operaciones HTTP/Kafka y conserva su identidad para recuperar resultados ante fallos. Las necesidades de canales, archivos, seguridad, resiliencia y escalabilidad completan esos tres procesos de negocio.

| Necesidad               | Implementacion y criterio                                                                                                                  |
| ----------------------- | ------------------------------------------------------------------------------------------------------------------------------------------ |
| Administrar clientes    | Registro, consulta, mantenimiento de datos y consulta/actualizacion de perfil ESTANDAR/PREFERENTE; correo unico sin distinguir mayusculas. |
| Administrar cuentas     | Apertura asociada a cliente existente, saldo inicial cero, mantenimiento administrativo y cierre solamente con saldo cero.                 |
| Procesar dinero         | Core realiza DEBIT, CREDIT y TRANSFER atomicos; Pagos coordina por HTTP y conserva el UUID original.                                       |
| Atender canales         | Web administra y consulta; Mobile entrega contratos reducidos y consultas; ATM consulta saldo, publica retiros y consulta su resultado.    |
| Procesar archivos       | Los tres Jobs originales generan resultados historicos desde los CSV, con validacion, skip y retry.                                        |
| Proteger accesos        | JWT firmados por Authorization Server; permisos por scope y credencial interna exclusiva de Pagos.                                         |
| Resistir fallos         | Circuitos BFF, timeouts y respuesta monetaria UNKNOWN conservando identidad; recuperacion Kafka sin duplicar el cargo.                     |
| Demostrar escalabilidad | Dos instancias de cada servicio bancario y distribucion HTTP observable por LoadBalancer.                                                  |

## 3. Cinco procesos criticos

1. **Clientes y perfiles:** Customer registra un cliente, conserva sus datos y actualiza su perfil mediante APIs protegidas.
2. **Ciclo de la cuenta:** Core valida el cliente por HTTP, abre una cuenta a cero, permite mantenimiento y cierra una cuenta vacia.
3. **Operaciones monetarias sincronicas:** BFF Web llama a Pagos; Pagos usa account.operations hacia Core; PostgreSQL confirma saldos y resultado en una sola transaccion.
4. **Retiro ATM asincronico:** ATM publica WithdrawalRequested y responde 202/PENDING; Pagos coordina el debito Core, publica el evento original persistido y confirma el offset; ATM consulta el estado por HTTP.
5. **Procesamiento historico:** dailyTransactionsJob, monthlyInterestJob y annualStatementsJob leen los CSV originales, escriben tablas historicas y generan el informe anual.

## 4. Arquitectura

El reactor contiene diez modulos Java 17 mas el POM padre, con Spring Boot 3.3.12 y Spring Cloud 2023.0.5. Compose utiliza PostgreSQL 16 y Apache Kafka 3.9.1. El alcance reutiliza los componentes existentes, sin incorporar Gateway, Kubernetes, Saga ni tecnologias adicionales.

Los BFF son aplicaciones independientes. Las llamadas HTTP resuelven nombres de servicio mediante Eureka y Spring Cloud LoadBalancer. LoadBalancer y Resilience4j son bibliotecas dentro de las aplicaciones; Resilience4j se ejecuta en los BFF. Pagos tiene su propio cliente HTTP balanceado y su gestor OAuth2 de client_credentials.

Core es el propietario de cuentas, saldos operativos, operaciones y resultados persistidos de retiros. Pagos carece de datasource y de acceso JDBC: coordina las solicitudes. Customer es propietario de clientes y perfiles. Compose comparte una instancia PostgreSQL entre Core y Customer.

La apertura consulta Customer antes de insertar la cuenta; no hay FK entre las tablas de ambos servicios. La validacion remota no tiene una transaccion distribuida; actualmente no existe endpoint para eliminar clientes. La asignacion de cuenta evita identificadores ya utilizados tanto en cuentas operativas como en resultados historicos.

## 5. Microservicios

| Servicio             | Puerto interno | Responsabilidad                                                                             | Persistencia                                                                                     | Referencias al codigo                                                                                                                                                                                                                                   |
| -------------------- | -------------: | ------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| core-banking-service |           8081 | Cuentas, saldos, contabilidad atomica, consultas historicas, resultados de retiros y Batch. | cuentas, operaciones_cuenta, withdrawal_transactions, banking_events, tablas historicas y Batch. | [AccountOperationService](proyecto/core-banking-service/src/main/java/cl/duoc/bancoxyz/core/AccountOperationService.java), [AccountManagementService](proyecto/core-banking-service/src/main/java/cl/duoc/bancoxyz/core/AccountManagementService.java). |
| payment-service      |           8085 | Pagos, depositos, transferencias, consulta de resultados y consumo Kafka optativo.          | Consulta/escribe indirectamente mediante Core.                                                   | [CoreOperationClient](proyecto/payment-service/src/main/java/cl/duoc/bancoxyz/payment/CoreOperationClient.java), [WithdrawalRequestedConsumer](proyecto/payment-service/src/main/java/cl/duoc/bancoxyz/payment/WithdrawalRequestedConsumer.java).       |
| customer-service     |           8086 | Cinco operaciones de Clientes y perfiles.                                                   | clientes.                                                                                        | [CustomerService](proyecto/customer-service/src/main/java/cl/duoc/bancoxyz/customer/CustomerService.java).                                                                                                                                              |

Core conserva el nombre tecnico core-banking-service para reducir el impacto sobre Eureka, contratos y BFF. En Compose, 8081/8085/8086 se exponen solamente en la red interna.

El modulo shared-events contiene los contratos de eventos y [TopicNames](proyecto/shared-events/src/main/java/cl/duoc/bancoxyz/events/TopicNames.java); se comparte como biblioteca, sin un proceso desplegado propio.

## 6. BFF

| Canal  | Puerto interno | Rutas principales y contrato                                                                                                                                          | Referencias al codigo                                                                                                        |
| ------ | -------------: | --------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------- |
| Web    |           8082 | /api/bff/web: clientes y perfil, apertura/mantenimiento/cierre de cuenta, pagos/depositos/transferencias/resultados, cuenta completa y estado anual.                  | [WebServicesController](proyecto/bff-web-service/src/main/java/cl/duoc/bancoxyz/web/WebServicesController.java).             |
| Mobile |           8083 | /api/bff/mobile: cuenta de cuatro campos, ultimos cinco movimientos, cliente de tres campos y operacion de cuatro campos. Las rutas nuevas de Clientes/Pagos son GET. | [MobileServicesController](proyecto/bff-mobile-service/src/main/java/cl/duoc/bancoxyz/mobile/MobileServicesController.java). |
| ATM    |           8084 | /api/bff/atm/accounts: saldo, POST /{id}/withdraw y GET /transactions/{transactionId}.                                                                                | [AtmBffController](proyecto/bff-atm-service/src/main/java/cl/duoc/bancoxyz/atm/AtmBffController.java).                       |

Los BFF carecen de acceso JDBC. Web y Mobile propagan el Bearer validado hacia Core, Customer y Pagos. ATM lo propaga en las consultas Core. Core valida el cliente en Customer con el Bearer del canal antes de insertar una cuenta. Pagos obtiene otro token, propio de payment-client/account.operations, y usa RestClient @LoadBalanced para las operaciones Core. Las APIs publicas de Pagos/Clientes/Core admiten los scopes definidos abajo aunque un BFF concreto exponga un subconjunto de funciones.

Los contratos REST de los servicios destino son:

| Servicio     | Rutas y operaciones                                                                                                                                                    |
| ------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Customer     | `/api/customers`: POST registro; GET/PUT `/{id}`; GET/PUT `/{id}/profile`.                                                                                             |
| Payment      | `/api/payments`: POST pago; POST `/api/payments/deposits` y `/api/payments/transfers`; GET `/api/payments/operations/{operationId}`.                                   |
| Core publico | `/api/core/accounts`: POST apertura; PUT `/{id}`; POST `/{id}/close`; GET cuenta, estado anual, movimientos, saldo y estado de retiro segun la matriz OAuth2.          |
| Core interno | `/api/core/internal/operations`: POST `/debit`, `/credit`, `/transfer` y GET `/{operationId}`. `/api/core/internal/withdrawals`: POST retiro y GET `/{transactionId}`. |

Los IDs reales, UUID, campos JSON y respuestas de estos contratos se detallan en los [Pasos 17–25](#eft-paso-17); los permisos se definen en la [matriz OAuth2/JWT](#eft-seguridad).

## 7. Spring Batch

Las tres entradas originales estan en `proyecto/core-banking-service/src/main/resources/input`; cada CSV contiene 1000 registros mas encabezado. Los clientes y cuentas de demostracion se crean por API y permanecen separados de estos historicos.

| Job / Step                                      | CSV original                | Ejecucion                                                             | Read / Write / Skip iniciales | Resultado persistido                                               |
| ----------------------------------------------- | --------------------------- | --------------------------------------------------------------------- | ----------------------------- | ------------------------------------------------------------------ |
| dailyTransactionsJob / dailyTransactionsStep    | input/transacciones.csv     | Paralela, executor compartido de tres hilos.                          | 1000 / 387 / 613              | 387 transacciones_procesadas; resumen por ejecucion.               |
| monthlyInterestJob / monthlyInterestStep        | input/intereses.csv         | Secuencial; ultimo registro valido en orden CSV prevalece por cuenta. | 1000 / 263 / 737              | 50 intereses_mensuales; 263 escrituras incluyen cuentas repetidas. |
| annualStatementsJob / annualMovementsStep       | input/cuentas_anuales.csv   | Paralela, tres hilos.                                                 | 1000 / 289 / 711              | 289 movimientos_anuales.                                           |
| annualStatementsJob / annualStatementReportStep | Movimientos ya persistidos. | Tasklet de informe.                                                   | 0 / 0 / 0                     | output/estados_cuenta_anuales.csv.                                 |

Chunk 5, retry de errores transitorios JDBC hasta 3 intentos y backoff 100 ms. La politica omite registros invalidos y errores de parseo. Las tasas son ahorro 0.01/prestamo 0.02. La tasa administrativa de una cuenta API no aplica intereses automaticamente a su saldo operativo.

El ajuste de orden mensual conserva el reader, writer y UPSERT existentes en [MonthlyInterestJobConfig](proyecto/core-banking-service/src/main/java/cl/duoc/bancoxyz/config/MonthlyInterestJobConfig.java).

[batch-init.sh](proyecto/docker/batch-init.sh) ejecuta los tres Jobs en orden diario, mensual y anual; cada uno usa un identificador `ejecucion=<epoch><601|602|603>,java.lang.Long,true`, puerto 0, Eureka deshabilitado y espera maxima de 120 segundos. Conserva los logs completos en el contenedor, termina la JVM de cada Job despues de COMPLETED y finaliza con BATCH INIT COMPLETED y codigo 0.

Una reejecucion escribe nuevamente resultados historicos y metadatos; no modifica cuentas.saldo_operativo. Los conteos de skip de Daily pueden aumentar cuando existen transacciones ya procesadas. El mensual conserva sus seis columnas entre ejecuciones; Daily/Annual conservan el paralelismo. Batch no crea clientes ni cuentas operativas.

## 8. PostgreSQL

| Propietario         | Tablas / regla                                                                                                                                  |
| ------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------- |
| Core operativo      | cuentas: estado ACTIVA/CERRADA y saldo_operativo no negativo; cliente_id asignado al abrir por API.                                             |
| Contabilidad Core   | operaciones_cuenta: UUID unico, tipo, monto, origen/destino y estado terminal con saldo resultante.                                             |
| Retiros Core        | withdrawal_transactions y banking_events: solicitud y evento de resultado original dentro de la misma transaccion del debito.                   |
| Compatibilidad Core | processed_events permanece en el esquema; el listener Core anterior esta deshabilitado y el flujo actual no lo utiliza como registro monetario. |
| Batch               | transacciones_procesadas, resumen_transacciones_diarias, intereses_mensuales, movimientos_anuales y BATCH_*.                                    |
| Customer            | clientes: ID identity, datos personales, perfil e indice unico lower(correo).                                                                   |

Los propietarios y restricciones se definen en el [esquema Core](proyecto/core-banking-service/src/main/resources/schema.sql) y el [esquema Customer](proyecto/customer-service/src/main/resources/schema.sql).

AccountOperationService usa @Transactional y SELECT FOR UPDATE en orden ascendente para cuentas de una transferencia. Debito, credito, resultado y ambos saldos se confirman o revierten conjuntamente. ON CONFLICT del UUID serializa reenvios concurrentes; mismos datos devuelven el resultado original, datos diferentes responden 409. Cuentas cerradas o con fondos insuficientes no ejecutan dinero. [OperationalWithdrawalService](proyecto/core-banking-service/src/main/java/cl/duoc/bancoxyz/core/OperationalWithdrawalService.java) agrega retiro y dos eventos a esa misma transaccion: un fallo de persistencia posterior al debito revierte todo. El resultado original persistido permanece recuperable aunque otras operaciones modifiquen luego el saldo vigente.

BankingService consulta primero cuentas; si el identificador solo existe en intereses_mensuales devuelve el resultado historico. Esa compatibilidad de lectura no habilita operaciones monetarias sobre registros CSV: la contabilidad opera sobre cuentas. Los nuevos identificadores de cuenta quedan por encima de los historicos existentes.

## 9. Kafka

| Topic                     | Productor               | Destinatario / uso                                                            |
| ------------------------- | ----------------------- | ----------------------------------------------------------------------------- |
| bank.withdrawal.requested | BFF ATM; key=accountId. | Payment Service, grupo core-banking-withdrawal-group, concurrency 3.          |
| bank.withdrawal.completed | Payment Service.        | Resultado publicable y trazable; sin otro consumidor de negocio implementado. |
| bank.withdrawal.rejected  | Payment Service.        | Resultado rechazado persistido; sin otro consumidor de negocio implementado.  |

Cada topic tiene tres particiones y factor de replicacion 1. Contratos en shared-events: eventId, transactionId, accountId, amount y occurredAt; el resultado exitoso agrega finalBalance y el rechazo reason. Apache Kafka 3.9.1 usa KRaft en el Compose academico.

El listener anterior de Core declara autoStartup="false" literalmente. Pagos declara payment.kafka.withdrawals.enabled=false por defecto, auto.offset.reset=none y enable-auto-commit=false; usa MANUAL_IMMEDIATE, commits sincronicos y CommonContainerStoppingErrorHandler para detenerse ante error. No basta con levantar Pagos para consumir. Se requieren posiciones validas en las tres particiones y activacion explicita de una instancia autorizada, documentadas en los [Pasos 22](#eft-paso-22) y [23](#eft-paso-23).

Si PostgreSQL confirma y falla la publicacion Kafka, Pagos no reconoce la solicitud y detiene el listener. Al recuperarlo, Kafka reentrega y Core devuelve el mismo evento original sin otro debito; Pagos vuelve a publicar y luego confirma la entrada. Si Kafka confirma el resultado pero falla el ACK de entrada, puede repetirse el mismo eventId de resultado. Se garantiza idempotencia monetaria mientras persistan UUID/resultados; no se promete entrega unica de mensajes, ni existe outbox/publicador periodico o transaccion PostgreSQL-Kafka. Retencion perdida, offsets fuera del rango o conflictos requieren revision manual.

Los escenarios de commit/respuesta/publicacion/ACK y recuperacion se comprueban en la [evidencia 05](evidencias/EFT/05_kafka_recuperacion.txt) y la [referencia vigente 10](evidencias/EFT/10_integracion_aislada.txt). ATM consulta el estado persistido en Core por HTTP.

ATM espera confirmacion del productor antes del 202. Su POST genera un UUID nuevo cada vez: no repetirlo para probar idempotencia ni ante timeout. Un 503/NOT_PUBLISHED exige conservar el transactionId; un timeout puede ocurrir despues de la aceptacion del broker. Se consulta/revisa esa misma identidad.

<a id="eft-seguridad"></a>

## 10. OAuth2/JWT

Authorization Server :9000 emite JWT RS256 con RSA 2048 mediante POST /oauth2/token, grant client_credentials y client_secret_basic. JWK publico en /oauth2/jwks; vigencia 30 minutos. Los clientes y la clave privada son de memoria; reiniciar Auth obliga a renovar tokens.

| Cliente        | Scope              | Uso                                                                                    |
| -------------- | ------------------ | -------------------------------------------------------------------------------------- |
| web-client     | web                | Canal Web y APIs publicas permitidas.                                                  |
| mobile-client  | mobile             | Canal Mobile y APIs publicas permitidas.                                               |
| atm-client     | atm                | Canal ATM, saldo y estado de retiro Core.                                              |
| payment-client | account.operations | Operaciones/retiros internos Core; registrado solo si hay secreto interno configurado. |

| API / accion                                                    | web      | mobile      | atm      | account.operations |
| --------------------------------------------------------------- | -------- | ----------- | -------- | ------------------ |
| BFF de cada canal                                               | Solo Web | Solo Mobile | Solo ATM | No                 |
| Core: abrir, mantener, cerrar y GET cuenta                      | Si       | Si          | No       | No                 |
| Core: estado anual                                              | Si       | No          | No       | No                 |
| Core: ultimos movimientos                                       | No       | Si          | No       | No                 |
| Core: saldo y estado de retiro                                  | No       | No          | Si       | No                 |
| Core: /api/config/origin                                        | Si       | Si          | Si       | No                 |
| Core: POST operaciones/retiros internos y GET resultado interno | No       | No          | No       | Si                 |
| Payment: POST pago/deposito/transferencia, GET resultado        | Si       | Si          | No       | No                 |
| Customer: cinco operaciones                                     | Si       | Si          | No       | No                 |
| /actuator/health                                                | Publico  | Publico     | Publico  | Publico            |

Los Resource Servers validan firma, issuer y vigencia y convierten los scopes en autoridades `SCOPE_*`. Sin token o firma/formato invalido: 401; token valido con scope insuficiente: 403. Scopes separan canales, no representan autorizacion por titular individual. Bajo Compose el issuer es http://authorization-server:9000; la URL host usada para pedir tokens puede ser distinta.

## 11. Resilience4j

Circuitos coreWeb/coreMobile/coreAtm protegen consultas Core; customerWeb/customerMobile y paymentWeb/paymentMobile separan los otros servicios. Configuracion central: ventana 5, minimo 5, umbral 50 %, OPEN 5 s y dos llamadas HALF_OPEN; Clientes/Pagos tienen TimeLimiter 5 s. Los errores funcionales Web/Mobile 400/404/409 y errores de autorizacion conservan su estado, sin tratarlos como indisponibilidad. El adaptador ATM conserva su fallback general 503.

Una respuesta perdida despues del commit monetario puede producir UNKNOWN/503 en Pagos con el mismo UUID; un error de autenticacion interna hacia Core puede producir 502/UNKNOWN. Esos resultados conservan la identidad y no representan un rechazo bancario definitivo. Web conserva UNKNOWN al fallar su llamada. Se consulta el resultado persistido o se reenvian exactamente los mismos datos, sin repetir automaticamente un POST con otra identidad. Los estados internos de los circuitos se comprueban en los tests del [Paso 28](#eft-paso-28); un 503 por si solo no prueba OPEN.

## 12. Eureka y Config Server

Config Server :8888 sirve el repositorio native [proyecto/config-repo](proyecto/config-repo), montado de solo lectura en Compose, con configuracion global y YAML por servicio. [application.yml](proyecto/config-repo/application.yml) define registro Eureka, IP preferida e instance-id unico. Las aplicaciones importan Config Server y conservan respaldo local mediante optional:configserver. El perfil validacion agrega health detallado, renovacion de lease y registro/refresh de cinco segundos, cache LoadBalancer 5 s y access logs Tomcat sin encabezados Authorization. Los valores efectivos de init/listener tambien pueden venir de environment/CLI: una respuesta Config muestra sus fuentes, pero no demuestra por si sola el estado del proceso.

Eureka :8761 registra Core, Pagos, Clientes y los tres BFF. Los clientes HTTP @LoadBalanced resuelven los nombres logicos; readiness incluye salud y registro UP. Auth y Config no son BFF ni servicios de contabilidad.

## 13. Docker Compose

[proyecto/docker-compose.yml](proyecto/docker-compose.yml) declara doce servicios: PostgreSQL, Kafka, Config, Eureka, Auth, batch-init, tres bancarios y tres BFF, con dos volumenes persistentes identificados por PROJECT para PostgreSQL/Kafka. [docker/Dockerfile.service](proyecto/docker/Dockerfile.service) compila el reactor con JDK 17 y copia el JAR del MODULE a una imagen JRE 17 con curl; se construyen diez imagenes propias. skipTests dentro del build de imagen presupone la validacion Maven previa en host.

Core permanente tiene Jobs Batch e inicializacion SQL/metadatos desactivados; batch-init inicializa Core y metadatos sobre una base nueva. Customer requiere inicializacion explicita: CUSTOMER_SQL_INIT_MODE=never en el Compose principal y always en [docker-compose.validacion.yml](proyecto/docker-compose.validacion.yml). El complemento de validacion permite dos replicas sin puertos fijos para los servicios bancarios y conserva Pagos Kafka desactivado.

El manual usa ambos archivos y un nombre de proyecto unico. Ejecuta infraestructura y BFF con compose run --no-deps --use-aliases y puertos loopback propios: **run no publica los ports declarados salvo --service-ports**. Core/Pagos/Clientes se ejecutan con up --no-deps; no tienen puertos host. Esta combinacion evita los puertos fijos de los entornos anteriores sin generar otro override. El Compose de integracion anterior fija imagenes historicas y no se usa para esta instalacion nueva.

Los contenedores one-off creados con run se conservan y se verifican por ID/etiqueta de proyecto; compose ps/exec normales no identifican esas instancias como las replicas habituales.

## 14. Escalabilidad horizontal

Dos replicas Core, dos Pagos y dos Clientes comparten sus propietarios PostgreSQL y se registran con IDs distintos en Eureka. Los BFF distribuyen HTTP mediante LoadBalancer. La [evidencia 09](evidencias/EFT/09_empaquetado_configuracion.txt) registro 48 GET por servicio, 24/24 entre sus dos instancias. Ese reparto es un resultado observado, no una garantia para cada ejecucion. Los access logs centrales permiten medir los accesos de ambas instancias en el [Paso 27](#eft-paso-27).

La demostracion Kafka usa una instancia adicional de Pagos activada expresamente, luego detenida tras lag cero antes de escalar las replicas HTTP normales. Las dos replicas normales siguen con Kafka desactivado. Esto evita habilitar consumidores involuntarios al usar up --scale. Replicas de servicios no eliminan los puntos unicos de PostgreSQL, Kafka, Auth, Config ni Eureka.

## 15. Diagrama de arquitectura Archify

[Abrir arquitectura EFT](documentacion/EFT/arquitectura_eft.html). [Indice de evidencias](evidencias/EFT/indice_evidencias.md).

Se usa Archify. Las lineas continuas representan REST/JDBC/configuracion; las discontinuas violetas identifican eventos Kafka y las rojas OAuth2/JWT por HTTP sincronico. LoadBalancer/Resilience4j se representan como bibliotecas, y las areas PostgreSQL separan la propiedad logica de datos operativos, clientes e historicos.

El HTML es autonomo: grafo y SVG estan integrados. Las areas PostgreSQL representan propietarios y tablas dentro de una instancia compartida. Los cruces resueltos por puentes no representan conexiones adicionales.

<a id="eft-ejecucion"></a>

## 16. Ejecucion

Ejecutar en una unica terminal Bash de WSL2 Ubuntu, por bloques y en orden; no iniciar dos reproducciones ni editar evidencias durante el respaldo/vaciado. Cada criterio fallido obliga a detenerse y conservar el diagnostico. No repetir altas ni operaciones para compensar una respuesta incierta. La metodologia es: respaldar toda la coleccion, verificar la copia, vaciar el contenido de evidencias/EFT conservando su directorio, iniciar PostgreSQL y Kafka nuevos, regenerar directamente en esa misma carpeta y comprobar el inventario final.

La ejecucion reemplazara su contenido solamente despues del respaldo verificado y de la confirmacion del Paso 00. Si Docker/Compose no admite los flags usados, los puertos no estan disponibles, faltan recursos o alguna guarda no se cumple, se detiene: no modificar Compose ni offsets de otros brokers.

<a id="eft-paso-00"></a>

### Paso 00 — Respaldar, verificar, vaciar y preparar la captura

**Objetivo:** Respaldar integramente la coleccion de pruebas, comprobar su integridad y vaciar exclusivamente la carpeta antes de preparar la nueva ejecucion.

**Directorio de trabajo:** `/home/francisco/proyecto/BACKEND III/EFT/EFT_Francisco_Henriquez/proyecto` (`cd "$PROY"` despues del Paso 00).

Estos comandos se documentan para la ejecucion. El respaldo queda directamente en "$ROOT/evidencias", como carpeta hermana de EFT, con nombre respaldo-evidencias-EFT-FECHA-IDENTIFICADOR; su manifiesto SHA256 queda en ese mismo directorio con sufijo .manifest.json. Incluyen los once TXT, el unico Markdown indice_evidencias.md y cualquier otra entrada existente, incluidos archivos ocultos.

**Comandos:**

```bash
set +x
set -euo pipefail
export ROOT='/home/francisco/proyecto/BACKEND III/EFT/EFT_Francisco_Henriquez'
export PROY="$ROOT/proyecto"
cd "$PROY"
export PROJECT="etf_s9"
export COMPOSE_COMPATIBILITY=false
export EVID="$ROOT/evidencias/EFT"
export BACKUP="$ROOT/evidencias/respaldo-evidencias-EFT-$(date +%Y%m%d_%H%M%S)-$(python3 -c 'import uuid; print(uuid.uuid4().hex[:8])')"
export BACKUP_MANIFEST="${BACKUP}.manifest.json"
EFT_TXT=(00_maven_referencia.txt 02_operaciones_atomicas.txt 03_seguridad_oauth2.txt 04_payment_service.txt 05_kafka_recuperacion.txt 06_customer_service.txt 07_gestion_cuentas.txt 08_bff_regresion.txt 09_empaquetado_configuracion.txt 10_integracion_aislada.txt 10_correccion_batch_mensual.txt)
EFT_DOCS=(indice_evidencias.md)
python3 - "$ROOT" "$EVID" "$BACKUP" "$BACKUP_MANIFEST" <<'PY'
import pathlib,sys
root,active,backup,manifest=map(pathlib.Path,sys.argv[1:])
expected=pathlib.Path('/home/francisco/proyecto/BACKEND III/EFT/EFT_Francisco_Henriquez/evidencias/EFT')
assert root==expected.parent.parent and root.resolve()==root
assert active==expected and active.resolve()==expected and active.is_dir() and not active.is_symlink()
assert backup.parent==expected.parent and backup.parent.resolve()==expected.parent
assert backup!=active and active not in backup.parents and backup not in active.parents
assert backup.name.startswith('respaldo-evidencias-EFT-') and manifest==pathlib.Path(str(backup)+'.manifest.json')
assert not backup.exists() and not backup.is_symlink()
assert not manifest.exists() and not manifest.is_symlink()
PY
eft_inventory() {
  python3 - "$1" <<'PY'
import hashlib,json,pathlib,sys
base=pathlib.Path(sys.argv[1]); assert base.is_dir() and not base.is_symlink()
entries={}
for p in sorted(base.rglob('*')):
    name=str(p.relative_to(base))
    assert not p.is_symlink(), 'Enlace simbolico: '+name
    if p.is_dir(): entries[name]={'tipo':'directorio'}
    else:
        assert p.is_file(), 'Tipo especial: '+name
        entries[name]={'tipo':'archivo','bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()}
print(json.dumps(entries,sort_keys=True,indent=2))
PY
}
umask 077
eft_inventory "$EVID" | tee "$BACKUP_MANIFEST"
cp -a -- "$EVID" "$BACKUP"
diff -qr -- "$EVID" "$BACKUP"
diff -u "$BACKUP_MANIFEST" <(eft_inventory "$EVID")
diff -u "$BACKUP_MANIFEST" <(eft_inventory "$BACKUP")
python3 - "$BACKUP_MANIFEST" "${EFT_TXT[@]}" "${EFT_DOCS[@]}" <<'PY'
import json,sys
entries=json.load(open(sys.argv[1])); required=sys.argv[2:]
assert len(required)==12 and len(set(required))==12
for name in required:
    assert entries[name]['tipo']=='archivo' and entries[name]['bytes']>0, name
print('Respaldo verificado: once TXT y un Markdown incluidos; inventario completo:',len(entries),'entradas.')
PY
export BACKUP_VERIFIED_SHA256=$(sha256sum "$BACKUP_MANIFEST" | cut -d ' ' -f 1)
```

Comprobar el inventario y las comparaciones anteriores. Copiar el siguiente bloque por separado y responder en la terminal; una respuesta distinta detiene la secuencia. Todavia no se ha vaciado la carpeta ni se ha utilizado Docker.

```bash
read -r -p 'Escriba VACIAR EVIDENCIAS EFT para vaciar solo la carpeta respaldada: ' CLEAN_CONFIRM </dev/tty
test "$CLEAN_CONFIRM" = 'VACIAR EVIDENCIAS EFT'
```

Solo despues de la confirmacion, volver a comprobar el respaldo y vaciar. Esta limpieza no elimina el directorio EFT, no sigue enlaces ni interviene en bases de datos, Docker o volumenes.

```bash
test "$CLEAN_CONFIRM" = 'VACIAR EVIDENCIAS EFT'
test "$(sha256sum "$BACKUP_MANIFEST" | cut -d ' ' -f 1)" = "$BACKUP_VERIFIED_SHA256"
diff -qr -- "$EVID" "$BACKUP"
diff -u "$BACKUP_MANIFEST" <(eft_inventory "$EVID")
diff -u "$BACKUP_MANIFEST" <(eft_inventory "$BACKUP")
python3 - "$EVID" "$BACKUP" <<'PY'
import pathlib,shutil,sys
active,backup=map(pathlib.Path,sys.argv[1:])
expected=pathlib.Path('/home/francisco/proyecto/BACKEND III/EFT/EFT_Francisco_Henriquez/evidencias/EFT')
assert active==expected and active.resolve()==expected and active.is_dir() and not active.is_symlink()
assert backup.is_dir() and not backup.is_symlink() and backup.resolve()==backup
assert backup.parent==expected.parent and backup.name.startswith('respaldo-evidencias-EFT-')
assert backup!=active and active not in backup.parents and backup not in active.parents
for child in list(active.iterdir()):
    assert not child.is_symlink()
    if child.is_dir(): shutil.rmtree(child)
    else: child.unlink()
assert active.is_dir() and not any(active.iterdir()), 'La carpeta no quedo vacia'
print('Directorio original conservado y completamente vacio:',active)
PY
chmod 700 "$EVID"
export RUN_STARTED=$(date +%s)
DC=(docker compose -p "$PROJECT" -f "$PROY/docker-compose.yml" -f "$PROY/docker-compose.validacion.yml")
export CFG_PORT=38888 EUREKA_PORT=38761 AUTH_PORT=39000 WEB_PORT=38082 MOBILE_PORT=38083 ATM_PORT=38084
export CFG_URL="http://127.0.0.1:$CFG_PORT" EUREKA_URL_HOST="http://127.0.0.1:$EUREKA_PORT"
export AUTH_URL="http://127.0.0.1:$AUTH_PORT" WEB_URL="http://127.0.0.1:$WEB_PORT"
export MOBILE_URL="http://127.0.0.1:$MOBILE_PORT" ATM_URL="http://127.0.0.1:$ATM_PORT"
printf 'Inicio=%s\nProyecto=%s\nDirectorio=%s\nEvidencias=%s\nRespaldo=%s\nManifiesto SHA256=%s\n' "$(date -Iseconds)" "$PROJECT" "$PROY" "$EVID" "$BACKUP" "$BACKUP_VERIFIED_SHA256" | tee "$EVID/preparacion.txt"
printf 'Respaldo integro verificado; directorio original comprobado vacio antes de esta captura.\n' | tee -a "$EVID/preparacion.txt"

owned() {
  test "$(docker inspect --format '{{index .Config.Labels "com.docker.compose.project"}}' "$1")" = "$PROJECT"
}
pg() {
  owned "$PG_ID"
  docker exec -i "$PG_ID" psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d banco_xyz "$@"
}
kafka() {
  owned "$KAFKA_ID"
  local tool="$1"; shift
  docker exec -i "$KAFKA_ID" "/opt/kafka/bin/$tool.sh" --bootstrap-server kafka:9092 "$@"
}
api() {
  local method="$1" url="$2" token="$3" data="$4" expected="$5" file="$6" response
  local args=(-sS --max-time 20 -X "$method" -w $'\n%{http_code}')
  if [ -n "$token" ]; then args+=(-H "Authorization: Bearer $token"); fi
  if [ -n "$data" ]; then args+=(-H 'Content-Type: application/json' --data "$data"); fi
  response=$(curl "${args[@]}" "$url")
  HTTP_CODE=${response##*$'\n'}
  HTTP_JSON=${response%$'\n'*}
  printf '%s %s HTTP=%s\n%s\n' "$method" "$url" "$HTTP_CODE" "$HTTP_JSON" | tee -a "$EVID/$file"
  test "$HTTP_CODE" = "$expected"
}
wait_health() {
  local id="$1" state attempt
  owned "$id"
  for attempt in {1..120}; do
    state=$(docker inspect --format '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{end}}' "$id")
    if [ "$state" = 'running healthy' ]; then printf 'healthy %s\n' "$id"; return 0; fi
    if [[ "$state" == exited* ]]; then printf 'ERROR %s %s\n' "$id" "$state"; return 1; fi
    sleep 2
  done
  printf 'ERROR timeout %s\n' "$id"; return 1
}
report_cases() {
  python3 - "$RUN_STARTED" "$@" <<'PY'
import fnmatch,hashlib,pathlib,sys,xml.etree.ElementTree as ET
started=int(sys.argv[1]); expected=int(sys.argv[2]); selectors=sys.argv[3:]
keys=set(); failures=errors=skips=0
for path in sorted(pathlib.Path('.').glob('*/target/surefire-reports/TEST-*.xml')):
    if path.stat().st_mtime < started: continue
    suite=ET.parse(path).getroot(); cls=suite.attrib['name']; short=cls.rsplit('.',1)[-1]
    for case in suite.findall('testcase'):
        name=case.attrib['name']
        matches=not selectors and not short.endswith('IT')
        for selector in selectors:
            pattern,_,method=selector.partition('#')
            matches |= (fnmatch.fnmatch(short,pattern) or fnmatch.fnmatch(cls,pattern)) and (not method or fnmatch.fnmatch(name,method))
        if not matches: continue
        key=(cls,name)
        assert key not in keys, ('Caso duplicado',key)
        keys.add(key)
        failed=case.find('failure') is not None; error=case.find('error') is not None; skip=case.find('skipped') is not None
        failures+=failed; errors+=error; skips+=skip
        print(cls,name,'PASS' if not (failed or error or skip) else 'FAIL')
print('Tests run:',len(keys),'Failures:',failures,'Errors:',errors,'Skipped:',skips)
assert (len(keys),failures,errors,skips)==(expected,0,0,0)
print('RESULTADO: PASS; casos distintos de la seleccion, no adicionales al reactor.')
PY
}
```

**Resultado esperado:** Respaldo completo en la carpeta hermana bajo "$ROOT/evidencias", comparado por contenido y SHA256; directorio original comprobado vacio antes de crear preparacion.txt; variables y funciones disponibles.

**Evidencia:** preparacion.txt, exclusivamente dentro de `$EVID`.

<a id="eft-paso-01"></a>

### Paso 01 — Verificar herramientas, capacidad, CSV y recursos

**Objetivo:** Comprobar Java 17, Wrapper, Docker activo, Compose compatible, entradas originales y aislamiento disponible.

**Directorio de trabajo:** `/home/francisco/proyecto/BACKEND III/EFT/EFT_Francisco_Henriquez/proyecto` (`cd "$PROY"` despues del Paso 00).

Si algun puerto esta ocupado por otro proyecto, cambiar solamente las seis variables *_PORT a puertos libres y recalcular las seis *_URL del Paso 00, antes del arranque. No detener otro proyecto. Conservar el inventario inicial. Compose run utilizara esos puertos, no los publicados por el YAML de validacion.

**Comandos:**

```bash
{
  pwd
  uname -a
  java -version
  ./mvnw --version
  python3 --version
  curl --version
  docker version
  docker compose version
  free -h
  df -h "$PROY" /tmp
  rg '<module>' pom.xml
  wc -l core-banking-service/src/main/resources/input/*.csv
  sha256sum core-banking-service/src/main/resources/input/*.csv
  docker ps -a --format '{{.ID}} {{.Names}} {{.Status}} {{.Ports}}'
  docker volume ls --format '{{.Name}}'
  docker network ls --format '{{.Name}}'
  ss -ltn
} 2>&1 | tee -a "$EVID/preparacion.txt"
check_project_resources() {
  test "$PROJECT" = 'etf_s9'
  local kind names name template
  for kind in container image volume network; do
    case "$kind" in
      container)
        names=$(docker ps -a --format '{{.Names}}')
        template='{{index .Config.Labels "com.docker.compose.project"}}' ;;
      image)
        names=$(docker image ls --format '{{.Repository}}:{{.Tag}}')
        template='{{index .Config.Labels "com.docker.compose.project"}}' ;;
      *)
        names=$(docker "$kind" ls --format '{{.Name}}')
        template='{{index .Labels "com.docker.compose.project"}}' ;;
    esac
    for name in $names; do
      case "$name" in
        "$PROJECT-"*|"$PROJECT"_*) test "$(docker "$kind" inspect --format "$template" "$name")" = "$PROJECT" ;;
      esac
    done
  done
}
check_project_resources
```

**Resultado esperado:** Java 17; Wrapper Maven operativo; servidor Docker responde; Compose v2 admite !reset/!override (2.24.4 o posterior), run --use-aliases y up --wait. Diez modulos Maven mas el POM padre; tres CSV de 1001 lineas cada uno.

**Evidencia:** preparacion.txt, exclusivamente dentro de `$EVID`.

**Criterio de avance:** Sin conflictos de puertos/recursos; memoria y disco suficientes para mantener este stack y los anteriores. Recomendada RAM disponible de al menos 4 GiB y disco libre de 8 GiB; medir y ampliar recursos si hace falta.

<a id="eft-paso-02"></a>

### Paso 02 — Ingresar secretos de la instalacion nueva

**Objetivo:** Configurar PostgreSQL y los cuatro clientes sin escribir secretos en documentos o evidencias.

**Directorio de trabajo:** `/home/francisco/proyecto/BACKEND III/EFT/EFT_Francisco_Henriquez/proyecto` (`cd "$PROY"` despues del Paso 00).

**Copiar cada bloque de read por separado y completar su entrada antes del siguiente.** Nunca pegar varios read juntos: pueden consumir comandos del buffer.

**Comandos:**

```bash
export POSTGRES_USER=banco_user
read -r -s -p 'Password PostgreSQL nuevo: ' POSTGRES_PASSWORD
printf '\n'
```

**Resultado esperado:** Password capturado solo en memoria.

**Evidencia:** preparacion.txt (solo confirmacion no sensible), exclusivamente dentro de `$EVID`.

**Criterio de avance:** Ingresar los otros cuatro secretos por separado.

Continuacion del Paso 02, en la misma terminal; cada bloque se copia individualmente:

```bash
read -r -s -p 'Secreto Web nuevo: ' OAUTH2_WEB_SECRET
printf '\n'
```

```bash
read -r -s -p 'Secreto Mobile nuevo: ' OAUTH2_MOBILE_SECRET
printf '\n'
```

```bash
read -r -s -p 'Secreto ATM nuevo: ' OAUTH2_ATM_SECRET
printf '\n'
```

```bash
read -r -s -p 'Secreto interno Pagos nuevo: ' OAUTH2_PAYMENT_SECRET
printf '\n'
```

Finalmente exportar, sin imprimir valores:

```bash
test -n "$POSTGRES_PASSWORD"
test -n "$OAUTH2_WEB_SECRET"
test -n "$OAUTH2_MOBILE_SECRET"
test -n "$OAUTH2_ATM_SECRET"
test -n "$OAUTH2_PAYMENT_SECRET"
export POSTGRES_PASSWORD OAUTH2_WEB_SECRET OAUTH2_MOBILE_SECRET OAUTH2_ATM_SECRET OAUTH2_PAYMENT_SECRET
printf 'PostgreSQL y cuatro secretos OAuth2 configurados en memoria.\n' | tee -a "$EVID/preparacion.txt"
```

Resultado y criterio final del Paso 02: cinco valores no vacios exportados. No usar `docker inspect` completo, `env`, `set`, `docker compose config` sin filtro ni `curl -v` para generar evidencias.

<a id="eft-paso-03"></a>

### Paso 03 — Establecer referencia Maven vigente

**Objetivo:** Ejecutar primero las pruebas existentes; no comenzar despliegue si falla la referencia.

**Directorio de trabajo:** `/home/francisco/proyecto/BACKEND III/EFT/EFT_Francisco_Henriquez/proyecto` (`cd "$PROY"` despues del Paso 00).

**Comandos:**

```bash
{
  printf 'COMANDO: ./mvnw test\n'
  ./mvnw test 2>&1 | sed -E 's/\x1B\[[0-9;]*[mK]//g' | rg '^\[(INFO|ERROR|WARNING)\]'
  report_cases 337
} | tee "$EVID/00_maven_referencia.txt"
```

**Resultado esperado:** BUILD SUCCESS; 337 casos distintos, cero fallos/errores/omitidos.

**Evidencia:** 00_maven_referencia.txt, exclusivamente dentro de `$EVID`.

**Criterio de avance:** Maven y reporte de 337 correctos; Docker debe estar disponible para Testcontainers.

<a id="eft-paso-04"></a>

### Paso 04 — Verificar integracion real Core y Clientes

**Objetivo:** Comprobar OAuth2 real y asociacion de clientes usando la clase existente.

**Directorio de trabajo:** `/home/francisco/proyecto/BACKEND III/EFT/EFT_Francisco_Henriquez/proyecto` (`cd "$PROY"` despues del Paso 00).

**Comandos:**

```bash
{
  ./mvnw -pl core-banking-service -am -Dtest=CoreCustomerOAuth2IT -Dsurefire.failIfNoSpecifiedTests=false test 2>&1 | rg '^\[(INFO|ERROR|WARNING)\]'
  report_cases 4 CoreCustomerOAuth2IT
} | tee "$EVID/core_clientes_it.txt"
```

**Resultado esperado:** 4 casos PASS con procesos y PostgreSQL aislados de los tests.

**Evidencia:** core_clientes_it.txt, exclusivamente dentro de `$EVID`.

**Criterio de avance:** BUILD SUCCESS y reporte 4/0/0/0.

<a id="eft-paso-05"></a>

### Paso 05 — Verificar los BFF con OAuth2 real

**Objetivo:** Ejecutar las dos clases de integracion explicitas de la version final.

**Directorio de trabajo:** `/home/francisco/proyecto/BACKEND III/EFT/EFT_Francisco_Henriquez/proyecto` (`cd "$PROY"` despues del Paso 00).

**Comandos:**

```bash
{
  JAVA_TOOL_OPTIONS='-Xms64m -Xmx256m' ./mvnw -pl bff-web-service -am -Dtest=BffApplicationsOAuth2IT,AccountsBffOAuth2IT -Dsurefire.failIfNoSpecifiedTests=false test 2>&1 | rg '^\[(INFO|ERROR|WARNING)\]'
  report_cases 7 BffApplicationsOAuth2IT AccountsBffOAuth2IT
} | tee "$EVID/bff_oauth_it.txt"
```

**Resultado esperado:** 6 casos BffApplicationsOAuth2IT y 1 AccountsBffOAuth2IT; 7 PASS. UNKNOWN, resiliencia, permisos y contratos reales se comprueban en recursos de test.

**Evidencia:** bff_oauth_it.txt, exclusivamente dentro de `$EVID`.

**Criterio de avance:** BUILD SUCCESS; 337 + 4 + 7 = 348 distintos.

<a id="eft-paso-06"></a>

### Paso 06 — Capturar suites de atomicidad, seguridad, Pagos, Kafka, Clientes y BFF

**Objetivo:** Regenerar evidencias equivalentes desde los XML actuales sin volcar system-out ni sumar casos repetidos.

**Directorio de trabajo:** `/home/francisco/proyecto/BACKEND III/EFT/EFT_Francisco_Henriquez/proyecto` (`cd "$PROY"` despues del Paso 00).

Los comandos de pruebas especificas de estas evidencias se documentan tambien en el Paso 28. El reporter imprime solo clase/metodo/estado, evita logs con credenciales y rechaza XML anteriores al inicio de esta reproduccion.

**Comandos:**

```bash
report_cases 56 AccountClosingConcurrencyIntegrationTest AccountOperationServiceIntegrationTest InternalOperationControllerIntegrationTest OperationalAccountsSchemaIntegrationTest OperationalBatchIsolationIntegrationTest OperationalWithdrawalIntegrationTest | tee "$EVID/02_operaciones_atomicas.txt"
report_cases 81 AtmSecurityTest AuthorizationServerTest CoreCustomerOAuth2IT CoreHealthSecurityIntegrationTest CoreSecurityTest CustomerOAuth2IntegrationTest CustomerSecurityTest MobileSecurityTest MobileServicesSecurityTest BffApplicationsOAuth2IT#signedTokensEnforceChannelSeparationAndRejectMissingOrInvalidSignatures WebSecurityTest WebServicesSecurityTest | tee "$EVID/03_seguridad_oauth2.txt"
report_cases 70 CoreOperationClientTest CoreWithdrawalClientTest PaymentApplicationsIntegrationTest PaymentControllerTest WithdrawalKafkaIntegrationTest WithdrawalRequestedConsumerTest | tee "$EVID/04_payment_service.txt"
report_cases 39 AccountClosingConcurrencyIntegrationTest AccountManagementIntegrationTest CoreCustomerOAuth2IT CustomerAccountHttpIntegrationTest OperationalAccountsSchemaIntegrationTest AccountsBffOAuth2IT | tee "$EVID/07_gestion_cuentas.txt"
report_cases 44 CustomerServiceTest CustomerSecurityTest CustomerIntegrationTest CustomerOAuth2IntegrationTest | tee "$EVID/06_customer_service.txt"
report_cases 49 WithdrawalKafkaIntegrationTest WithdrawalRequestedConsumerTest CoreWithdrawalClientTest OperationalWithdrawalIntegrationTest | tee "$EVID/05_kafka_recuperacion.txt"
report_cases 78 "cl.duoc.bancoxyz.web.*" "cl.duoc.bancoxyz.mobile.*" "cl.duoc.bancoxyz.atm.*" | tee "$EVID/08_bff_regresion.txt"
```

**Resultado esperado:** Seleccionados: 56 atomicidad, 81 seguridad, 70 Pagos, 39 cuentas y 44 Clientes; Kafka 49 y BFF 78 en sus selecciones actuales. Todos incluidos en los 348, con metodos PASS.

**Evidencia:** 02_operaciones_atomicas.txt, 03_seguridad_oauth2.txt, 04_payment_service.txt, 05_kafka_recuperacion.txt, 06_customer_service.txt, 07_gestion_cuentas.txt y 08_bff_regresion.txt, exclusivamente dentro de `$EVID`.

**Criterio de avance:** Todas las selecciones reportan cero errores y cantidades esperadas; pasos 03–05 necesarios.

<a id="eft-paso-07"></a>

### Paso 07 — Construir imagenes actuales y auditar Compose

**Objetivo:** Compilar los JAR ya probados y construir etiquetas propias que incluyan el Job mensual corregido.

**Directorio de trabajo:** `/home/francisco/proyecto/BACKEND III/EFT/EFT_Francisco_Henriquez/proyecto` (`cd "$PROY"` despues del Paso 00).

**Comandos:**

```bash
{
  ./mvnw -DskipTests package 2>&1 | rg '^\[(INFO|ERROR|WARNING)\]'
  "${DC[@]}" config --format json | python3 -c '
import json,sys
d=json.load(sys.stdin)
assert d["name"]==sys.argv[1]=="etf_s9"
assert len(d["services"])==12
for name,s in d["services"].items():
    assert "container_name" not in s
    if name in ("core-banking-service","payment-service","customer-service"): assert not s.get("ports")
    print(name,"MODULE="+str(s.get("build",{}).get("args",{}).get("MODULE","infraestructura")))
for kind in ("networks","volumes"):
    for name,v in d[kind].items():
        assert not v.get("external") and v["name"].startswith(sys.argv[1]+"_")
        print(kind,name,v["name"])
' "$PROJECT"
  "${DC[@]}" build
  for service in config-server eureka-server authorization-server batch-init core-banking-service payment-service customer-service bff-web-service bff-mobile-service bff-atm-service; do
    docker image inspect --format '{{.Id}} {{.RepoTags}}' "$PROJECT-$service:latest"
  done
  rg -n 'taskExecutor' core-banking-service/src/main/java/cl/duoc/bancoxyz/config/{DailyTransactionsJobConfig,AnnualStatementsJobConfig}.java
  if rg -n 'taskExecutor' core-banking-service/src/main/java/cl/duoc/bancoxyz/config/MonthlyInterestJobConfig.java; then
    printf 'ERROR: mensual concurrente\n'; exit 1
  fi
} 2>&1 | tee "$EVID/09_empaquetado_configuracion.txt"
```

**Resultado esperado:** Diez imagenes propias etiquetadas etf_s9-servicio:latest, PostgreSQL 16 y Kafka 3.9.1 externos al build; redes/volumenes propios y mensual sin executor. skipTests aqui solo empaqueta despues de las pruebas.

**Evidencia:** 09_empaquetado_configuracion.txt, exclusivamente dentro de `$EVID`.

**Criterio de avance:** Build correcto, sin reutilizar imagenes de otros proyectos ni el lanzador temporal de la correccion mensual; mantener el ID de imagen construido para revisar los contenedores.

<a id="eft-paso-08"></a>

### Paso 08 — Iniciar PostgreSQL y Kafka nuevos

**Objetivo:** Crear persistencia nueva y verificar que no contiene datos previos.

**Directorio de trabajo:** `/home/francisco/proyecto/BACKEND III/EFT/EFT_Francisco_Henriquez/proyecto` (`cd "$PROY"` despues del Paso 00).

**Comandos:**

```bash
read -r -p 'Escriba REINICIAR etf_s9 para eliminar solo sus contenedores, red y volumenes: ' RESET_CONFIRM </dev/tty
test "$RESET_CONFIRM" = 'REINICIAR etf_s9'
```

```bash
test "$RESET_CONFIRM" = 'REINICIAR etf_s9'
check_project_resources
"${DC[@]}" down -v --remove-orphans 2>&1 | tee "$EVID/base_nueva.txt"
test -z "$(docker ps -aq --filter "label=com.docker.compose.project=$PROJECT")"
test -z "$(docker volume ls -q --filter "label=com.docker.compose.project=$PROJECT")"
test -z "$(docker network ls -q --filter "label=com.docker.compose.project=$PROJECT")"
docker ps -a --format '{{.Names}}' | python3 -c 'import sys; assert not any(name.strip().startswith((sys.argv[1]+"-",sys.argv[1]+"_")) for name in sys.stdin)' "$PROJECT"
docker volume ls --format '{{.Name}}' | python3 -c 'import sys; assert not any(name.strip().startswith(sys.argv[1]+"_") for name in sys.stdin)' "$PROJECT"
docker network ls --format '{{.Name}}' | python3 -c 'import sys; assert not any(name.strip().startswith(sys.argv[1]+"_") for name in sys.stdin)' "$PROJECT"
python3 - "$CFG_PORT" "$EUREKA_PORT" "$AUTH_PORT" "$WEB_PORT" "$MOBILE_PORT" "$ATM_PORT" <<'PY'
import socket,sys
for value in sys.argv[1:]:
    with socket.socket() as sock:
        sock.bind(('0.0.0.0',int(value)))
        print('Puerto disponible:',value)
PY
PG_ID=$("${DC[@]}" run --no-deps -d --use-aliases --name "$PROJECT-postgres" postgres)
KAFKA_ID=$("${DC[@]}" run --no-deps -d --use-aliases --name "$PROJECT-kafka" kafka)
export PG_ID KAFKA_ID
{
  wait_health "$PG_ID"
  wait_health "$KAFKA_ID"
  docker inspect --format '{{.Name}} {{range .Mounts}}{{.Name}} {{end}}{{range .NetworkSettings.Networks}}{{.NetworkID}} {{end}}' "$PG_ID" "$KAFKA_ID"
  pg -At -c "SELECT count(*) FROM information_schema.tables WHERE table_schema='public';"
  kafka kafka-topics --list
  kafka kafka-consumer-groups --list
} | tee -a "$EVID/base_nueva.txt"
test "$(pg -At -c "SELECT count(*) FROM information_schema.tables WHERE table_schema='public';")" = 0
kafka kafka-topics --list | python3 -c 'import sys; topics=[l.strip() for l in sys.stdin if l.strip() and not l.startswith("__")]; assert not topics, topics; print("Sin topics de usuario previos.")'
test -z "$(kafka kafka-consumer-groups --list)"
```

**Resultado esperado:** PostgreSQL public sin tablas; broker sin topics de usuario ni grupos. Volumenes etf_s9_postgres_data/etf_s9_kafka_data y red etf_s9_default. No se publica PostgreSQL ni Kafka hacia host.

**Evidencia:** base_nueva.txt, exclusivamente dentro de `$EVID`.

**Criterio de avance:** Todos los recursos pertenecen a PROJECT, los dos healthy y datos vacios. Si no estan vacios, detenerse.

<a id="eft-paso-09"></a>

### Paso 09 — Iniciar Config Server, Eureka y Authorization Server

**Objetivo:** Levantar infraestructura HTTP con alias internos y puertos host propios.

**Directorio de trabajo:** `/home/francisco/proyecto/BACKEND III/EFT/EFT_Francisco_Henriquez/proyecto` (`cd "$PROY"` despues del Paso 00).

**Comandos:**

```bash
CFG_ID=$("${DC[@]}" run --no-deps -d --use-aliases --name "$PROJECT-config-server" --publish "127.0.0.1:$CFG_PORT:8888" config-server)
wait_health "$CFG_ID"
EUREKA_ID=$("${DC[@]}" run --no-deps -d --use-aliases --name "$PROJECT-eureka-server" --publish "127.0.0.1:$EUREKA_PORT:8761" eureka-server)
AUTH_ID=$("${DC[@]}" run --no-deps -d --use-aliases --name "$PROJECT-authorization-server" --publish "127.0.0.1:$AUTH_PORT:9000" authorization-server)
export CFG_ID EUREKA_ID AUTH_ID
{
  wait_health "$EUREKA_ID"
  wait_health "$AUTH_ID"
  for url in "$CFG_URL" "$EUREKA_URL_HOST" "$AUTH_URL"; do
    curl -fsS "$url/actuator/health"
    printf '\n'
  done
  curl -fsS "$AUTH_URL/oauth2/jwks" | python3 -c 'import json,sys; d=json.load(sys.stdin); assert d["keys"] and all("d" not in k for k in d["keys"]); print("JWK RSA publico disponible")'
} | tee -a "$EVID/09_empaquetado_configuracion.txt"
```

**Resultado esperado:** Tres servicios healthy; health UP, claves publicas disponibles. Issuer de tokens permanece authorization-server:9000 dentro de la red, independiente de AUTH_PORT host.

**Evidencia:** 09_empaquetado_configuracion.txt, dentro de `$EVID`.

**Criterio de avance:** Salud comprobada y alias DNS propios; no usar up sobre estas cinco instancias run, que intentaria crear otros contenedores con los puertos declarados en YAML.

<a id="eft-paso-10"></a>

### Paso 10 — Ejecutar los tres Jobs con batch-init

**Objetivo:** Inicializar esquema Core y Batch desde CSV en la base nueva.

**Directorio de trabajo:** `/home/francisco/proyecto/BACKEND III/EFT/EFT_Francisco_Henriquez/proyecto` (`cd "$PROY"` despues del Paso 00).

El script existente fija el orden daily -> monthly -> annual. SPRING_SQL_INIT_MODE=always y SPRING_BATCH_JDBC_INITIALIZE_SCHEMA=always provienen del complemento de validacion; Core permanente usara never. El contenedor terminado conserva el informe; no usar --rm.

**Comandos:**

```bash
BATCH_ID=$("${DC[@]}" run --no-deps -d --name "$PROJECT-batch-init" batch-init)
export BATCH_ID
batch_done=0
for attempt in {1..150}; do
  batch_state=$(docker inspect --format '{{.State.Status}} {{.State.ExitCode}}' "$BATCH_ID")
  if [ "$batch_state" = 'exited 0' ]; then batch_done=1; break; fi
  if [[ "$batch_state" == exited* ]]; then docker logs "$BATCH_ID"; exit 1; fi
  sleep 2
done
test "$batch_done" = 1
{
  owned "$BATCH_ID"
  docker logs "$BATCH_ID"
  pg -c 'SELECT i.job_name,e.job_execution_id,e.status,p.parameter_name,p.parameter_value FROM batch_job_instance i JOIN batch_job_execution e USING(job_instance_id) LEFT JOIN batch_job_execution_params p USING(job_execution_id) ORDER BY e.job_execution_id;'
  pg -c 'SELECT step_name,status,read_count,write_count,read_skip_count,process_skip_count,write_skip_count FROM batch_step_execution ORDER BY step_execution_id;'
  pg -c "SELECT 'daily' tabla,count(*) FROM transacciones_procesadas UNION ALL SELECT 'monthly',count(*) FROM intereses_mensuales UNION ALL SELECT 'annual',count(*) FROM movimientos_anuales UNION ALL SELECT 'cuentas',count(*) FROM cuentas UNION ALL SELECT 'operaciones',count(*) FROM operaciones_cuenta;"
} | tee "$EVID/batch_inicial.txt"
docker logs "$BATCH_ID" 2>&1 | rg 'BATCH INIT COMPLETED'
for job in dailyTransactionsJob monthlyInterestJob annualStatementsJob; do
  docker logs "$BATCH_ID" 2>&1 | rg "$job.*COMPLETED"
done
docker cp "$BATCH_ID:/app/output/estados_cuenta_anuales.csv" "$EVID/estados_cuenta_anuales.csv"
head -5 "$EVID/estados_cuenta_anuales.csv"
```

**Resultado esperado:** Tres JobExecution COMPLETED y cuatro Steps COMPLETED; historicos 387/50/289, cuentas=0 y operaciones=0; informe CSV real. Logs completos de cada Job en /tmp/s6_batch_daily.log, monthly.log y annual.log del contenedor conservado.

**Evidencia:** batch_inicial.txt y estados_cuenta_anuales.csv, exclusivamente dentro de `$EVID`.

**Criterio de avance:** Exited 0 y resultados coincidentes. Ante fallo, consultar docker cp de esos logs exclusivamente al EVID propio; no relanzar automaticamente el inicializador.

<a id="eft-paso-11"></a>

### Paso 11 — Iniciar Clientes y verificar su esquema

**Objetivo:** Habilitar el esquema clientes en el entorno nuevo mediante el Compose de validacion.

**Directorio de trabajo:** `/home/francisco/proyecto/BACKEND III/EFT/EFT_Francisco_Henriquez/proyecto` (`cd "$PROY"` despues del Paso 00).

**Comandos:**

```bash
"${DC[@]}" up -d --no-deps --no-build --pull never --wait --wait-timeout 240 customer-service
CUSTOMER_ID=$("${DC[@]}" ps -q customer-service)
export CUSTOMER_ID
{
  wait_health "$CUSTOMER_ID"
  docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$CUSTOMER_ID" | rg '^CUSTOMER_SQL_INIT_MODE=always$'
  pg -c "SELECT table_name FROM information_schema.tables WHERE table_schema='public' ORDER BY table_name;"
  pg -c 'SELECT count(*) FROM clientes;'
} | tee -a "$EVID/06_customer_service.txt"
test "$(pg -At -c 'SELECT count(*) FROM clientes;')" = 0
```

**Resultado esperado:** clientes creada y vacia; Customer healthy. Solo este esquema pertenece a Customer; los CSV no proporcionan clientes.

**Evidencia:** 06_customer_service.txt, exclusivamente dentro de `$EVID`.

**Criterio de avance:** CUSTOMER_SQL_INIT_MODE=always efectivo.

<a id="eft-paso-12"></a>

### Paso 12 — Iniciar Core y Pagos sin reejecutar dependencias

**Objetivo:** Levantar APIs operativas actuales con los consumidores desactivados.

**Directorio de trabajo:** `/home/francisco/proyecto/BACKEND III/EFT/EFT_Francisco_Henriquez/proyecto` (`cd "$PROY"` despues del Paso 00).

**Comandos:**

```bash
"${DC[@]}" up -d --no-deps --no-build --pull never --wait --wait-timeout 240 core-banking-service payment-service
CORE_ID=$("${DC[@]}" ps -q core-banking-service)
PAYMENT_ID=$("${DC[@]}" ps -q payment-service)
export CORE_ID PAYMENT_ID
{
  wait_health "$CORE_ID"
  wait_health "$PAYMENT_ID"
  docker inspect --format '{{.Name}} {{.Image}}' "$CORE_ID" "$PAYMENT_ID" "$CUSTOMER_ID" "$BATCH_ID"
  docker exec "$CORE_ID" sha256sum /app/app.jar
  docker cp "$CORE_ID:/app/app.jar" "/tmp/$PROJECT-core.jar"
  python3 - "/tmp/$PROJECT-core.jar" <<'PY'
import hashlib,pathlib,sys,zipfile
p='cl/duoc/bancoxyz/config/MonthlyInterestJobConfig.class'
host=pathlib.Path('core-banking-service/target/classes')/p
with zipfile.ZipFile(sys.argv[1]) as jar:
    compiled=jar.read('BOOT-INF/classes/'+p)
assert compiled == host.read_bytes(), 'Clase mensual distinta del empaquetado actual'
assert b'taskExecutor' not in compiled
print('MonthlyInterestJobConfig actual, sin taskExecutor; SHA256='+hashlib.sha256(compiled).hexdigest())
PY
  docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$CORE_ID" | rg '^(SPRING_BATCH_JOB_ENABLED|SPRING_SQL_INIT_MODE|SPRING_BATCH_JDBC_INITIALIZE_SCHEMA|SPRING_KAFKA_LISTENER_AUTO_STARTUP)='
  docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$PAYMENT_ID" | rg '^(PAYMENT_KAFKA_WITHDRAWALS_ENABLED|SPRING_KAFKA_LISTENER_AUTO_STARTUP)='
  rg -n 'autoStartup = "false"' core-banking-service/src/main/java/cl/duoc/bancoxyz/core/kafka/WithdrawalRequestedConsumer.java
  kafka kafka-consumer-groups --list
} | tee -a "$EVID/09_empaquetado_configuracion.txt"
```

**Resultado esperado:** Core/Pagos healthy; JAR y clase mensual actuales; Core Batch e init desactivados; ambos listeners inactivos y grupo monetario ausente.

**Evidencia:** 09_empaquetado_configuracion.txt, exclusivamente dentro de `$EVID`.

**Criterio de avance:** Tres Jobs aun son los del Paso 10; no aparece consumidor de retiros Core ni Pagos. Nunca usar up sin --no-deps para reinicios o escalamiento.

<a id="eft-paso-13"></a>

### Paso 13 — Iniciar los tres BFF y esperar Eureka

**Objetivo:** Habilitar canales con puertos propios y esperar los seis registros bancarios/BFF.

**Directorio de trabajo:** `/home/francisco/proyecto/BACKEND III/EFT/EFT_Francisco_Henriquez/proyecto` (`cd "$PROY"` despues del Paso 00).

**Comandos:**

```bash
WEB_ID=$("${DC[@]}" run --no-deps -d --use-aliases --name "$PROJECT-bff-web-service" --publish "127.0.0.1:$WEB_PORT:8082" bff-web-service)
MOBILE_ID=$("${DC[@]}" run --no-deps -d --use-aliases --name "$PROJECT-bff-mobile-service" --publish "127.0.0.1:$MOBILE_PORT:8083" bff-mobile-service)
ATM_ID=$("${DC[@]}" run --no-deps -d --use-aliases --name "$PROJECT-bff-atm-service" --publish "127.0.0.1:$ATM_PORT:8084" bff-atm-service)
export WEB_ID MOBILE_ID ATM_ID
{
  for id in "$WEB_ID" "$MOBILE_ID" "$ATM_ID"; do wait_health "$id"; done
  for url in "$WEB_URL" "$MOBILE_URL" "$ATM_URL"; do curl -fsS "$url/actuator/health"; printf '\n'; done
} | tee -a "$EVID/09_empaquetado_configuracion.txt"
wait_registry() {
  local replicas="$1" attempt body
  for attempt in {1..90}; do
    body=$(curl -fsS -H 'Accept: application/json' "$EUREKA_URL_HOST/eureka/apps")
    if printf '%s' "$body" | python3 -c '
import json,sys
r=int(sys.argv[1]); d=json.load(sys.stdin)["applications"]["application"]
if isinstance(d,dict): d=[d]
apps={a["name"]:a["instance"] if isinstance(a["instance"],list) else [a["instance"]] for a in d}
for name in ("CORE-BANKING-SERVICE","PAYMENT-SERVICE","CUSTOMER-SERVICE","BFF-WEB-SERVICE","BFF-MOBILE-SERVICE","BFF-ATM-SERVICE"):
    rows=apps.get(name,[]); want=1 if name.startswith("BFF-") else r
    assert len(rows)==want and all(i["status"]=="UP" for i in rows)
    print(name,[(i["instanceId"],i["status"]) for i in rows])
' "$replicas" 2>/dev/null; then return 0; fi
    sleep 2
  done
  printf 'ERROR Eureka incompleto\n'; return 1
}
wait_registry 1 | tee -a "$EVID/09_empaquetado_configuracion.txt"
```

**Resultado esperado:** Tres BFF healthy y seis servicios UP con una instancia cada uno en Eureka.

**Evidencia:** 09_empaquetado_configuracion.txt, exclusivamente dentro de `$EVID`.

**Criterio de avance:** Registry completo; esperar caches antes de consultas. Ante 503 de descubrimiento, repetir solo GET y conservar diagnostico.

<a id="eft-paso-14"></a>

### Paso 14 — Comprobar Config Server y propiedades publicas

**Objetivo:** Identificar las fuentes centrales y parametros reales sin divulgar secretos.

**Directorio de trabajo:** `/home/francisco/proyecto/BACKEND III/EFT/EFT_Francisco_Henriquez/proyecto` (`cd "$PROY"` despues del Paso 00).

**Comandos:**

```bash
{
  for service in core-banking-service payment-service customer-service bff-web-service bff-mobile-service bff-atm-service; do
    curl -fsS "$CFG_URL/$service/validacion" | python3 -c '
import json,sys
d=json.load(sys.stdin); assert d["name"]==sys.argv[1]
allowed={"server.port","server.tomcat.accesslog.enabled","server.tomcat.accesslog.directory","server.tomcat.accesslog.prefix","server.tomcat.accesslog.pattern","payment.kafka.withdrawals.enabled","spring.sql.init.mode","eureka.instance.prefer-ip-address","spring.cloud.loadbalancer.cache.ttl"}
for s in d["propertySources"]:
    print(s["name"],{k:v for k,v in s["source"].items() if k in allowed or k.startswith("resilience4j.circuitbreaker.instances.")})
assert any("application-validacion.yml" in s["name"] for s in d["propertySources"])
' "$service"
  done
} | tee -a "$EVID/09_empaquetado_configuracion.txt"
```

**Resultado esperado:** Perfil validacion y fuentes del repositorio native; accesslog central, cache/registro 5 s y circuitos documentados. La respuesta Config indica valores de fuente, no demuestra por si sola overrides efectivos del proceso.

**Evidencia:** 09_empaquetado_configuracion.txt, exclusivamente dentro de `$EVID`.

**Criterio de avance:** Cada servicio recibe sus fuentes; contrastar perfiles de contenedores con SPRING_PROFILES_ACTIVE=validacion y registros/access logs en pasos 12/26.

<a id="eft-paso-15"></a>

### Paso 15 — Obtener cuatro tokens OAuth2

**Objetivo:** Solicitar los clientes reales, conservando los JWT exclusivamente en memoria.

**Directorio de trabajo:** `/home/francisco/proyecto/BACKEND III/EFT/EFT_Francisco_Henriquez/proyecto` (`cd "$PROY"` despues del Paso 00).

**Comandos:**

```bash
token() {
  local client="$1" scope="$2" secret="$3" variable="$4" response code body value
  response=$(curl -sS --max-time 15 --user "$client:$secret" --data-urlencode grant_type=client_credentials --data-urlencode "scope=$scope" -w $'\n%{http_code}' "$AUTH_URL/oauth2/token")
  code=${response##*$'\n'}; body=${response%$'\n'*}
  test "$code" = 200
  value=$(printf '%s' "$body" | python3 -c 'import json,sys; d=json.load(sys.stdin); assert d["scope"]==sys.argv[1] and d["token_type"].lower()=="bearer"; print(d["access_token"])' "$scope")
  printf -v "$variable" '%s' "$value"
  printf '%s' "$body" | python3 -c 'import json,sys; d=json.load(sys.stdin); print(sys.argv[1],{k:d[k] for k in ("token_type","scope","expires_in")})' "$client" | tee -a "$EVID/03_seguridad_oauth2.txt"
}
token web-client web "$OAUTH2_WEB_SECRET" WEB_TOKEN
token mobile-client mobile "$OAUTH2_MOBILE_SECRET" MOBILE_TOKEN
token atm-client atm "$OAUTH2_ATM_SECRET" ATM_TOKEN
token payment-client account.operations "$OAUTH2_PAYMENT_SECRET" PAYMENT_TOKEN
```

**Resultado esperado:** Cuatro HTTP 200, Bearer y scopes web/mobile/atm/account.operations. No se guarda access_token.

**Evidencia:** 03_seguridad_oauth2.txt, exclusivamente dentro de `$EVID`.

**Criterio de avance:** Las cuatro variables de token definidas. Si transcurren 30 minutos, repetir solamente estas cuatro llamadas, no los POST bancarios ni el arranque.

<a id="eft-paso-16"></a>

### Paso 16 — Verificar autorizacion e identidad interna

**Objetivo:** Comprobar 401, 403 y operaciones internas protegidas, sin realizar dinero.

**Directorio de trabajo:** `/home/francisco/proyecto/BACKEND III/EFT/EFT_Francisco_Henriquez/proyecto` (`cd "$PROY"` despues del Paso 00).

**Comandos:**

```bash
api GET "$WEB_URL/api/bff/web/accounts/101" '' '' 401 03_seguridad_oauth2.txt
api GET "$WEB_URL/api/bff/web/accounts/101" "$ATM_TOKEN" '' 403 03_seguridad_oauth2.txt
api GET "$WEB_URL/api/bff/web/accounts/101" 'invalid-token' '' 401 03_seguridad_oauth2.txt
api GET "$WEB_URL/api/bff/web/accounts/101" "$WEB_TOKEN" '' 200 03_seguridad_oauth2.txt
api GET "$MOBILE_URL/api/bff/mobile/accounts/101" "$MOBILE_TOKEN" '' 200 03_seguridad_oauth2.txt
core_http() {
  local path="$1" token="$2" expected="$3" response
  owned "$WEB_ID"
  response=$({
    printf 'url = "http://core-banking-service:8081%s"\n' "$path"
    if [ -n "$token" ]; then printf 'header = "Authorization: Bearer %s"\n' "$token"; fi
  } | docker exec -i "$WEB_ID" curl -sS --max-time 15 --config - -w $'\n%{http_code}')
  local code=${response##*$'\n'} body=${response%$'\n'*}
  printf 'Core GET %s HTTP=%s\n%s\n' "$path" "$code" "$body" | tee -a "$EVID/03_seguridad_oauth2.txt"
  test "$code" = "$expected"
}
TEST_UUID=$(python3 -c 'import uuid; print(uuid.uuid4())')
core_http "/api/core/internal/operations/$TEST_UUID" "$WEB_TOKEN" 403
core_http "/api/core/internal/operations/$TEST_UUID" "$PAYMENT_TOKEN" 404
core_http "/api/core/internal/operations/$TEST_UUID" '' 401
core_http '/api/config/origin' "$WEB_TOKEN" 200
```

**Resultado esperado:** BFF sin JWT 401, scope cruzado 403, valido 200. Core interno con Web 403; payment-client llega al controlador y recibe 404 por UUID inexistente, sin escritura. Origen confirma Config.

**Evidencia:** 03_seguridad_oauth2.txt, exclusivamente dentro de `$EVID`.

**Criterio de avance:** Matriz consistente. La propagacion Bearer y la firma real tambien estan cubiertas en los IT y tests de los pasos 04–06.

<a id="eft-paso-17"></a>

### Paso 17 — Registrar, consultar y mantener Clientes por Web

**Objetivo:** Ejecutar las cinco funciones y conservar el ID generado.

**Directorio de trabajo:** `/home/francisco/proyecto/BACKEND III/EFT/EFT_Francisco_Henriquez/proyecto` (`cd "$PROY"` despues del Paso 00).

**Comandos:**

```bash
CUSTOMER_EMAIL="${PROJECT}@example.invalid"
CUSTOMER_JSON=$(python3 -c 'import json,sys; print(json.dumps({"firstName":"Cliente","lastName":"Academico","email":sys.argv[1],"phone":"+56912345678","profile":"ESTANDAR"}))' "$CUSTOMER_EMAIL")
api POST "$WEB_URL/api/bff/web/customers" "$WEB_TOKEN" "$CUSTOMER_JSON" 201 06_customer_service.txt
CUSTOMER=$(printf '%s' "$HTTP_JSON" | python3 -c 'import json,sys; d=json.load(sys.stdin); assert d["customerId"]>0; print(d["customerId"])')
api GET "$WEB_URL/api/bff/web/customers/$CUSTOMER" "$WEB_TOKEN" '' 200 06_customer_service.txt
UPDATE_CUSTOMER_JSON=$(python3 -c 'import json,sys; print(json.dumps({"firstName":"Cliente Actualizado","lastName":"Academico","email":sys.argv[1],"phone":"+56912345678"}))' "$CUSTOMER_EMAIL")
api PUT "$WEB_URL/api/bff/web/customers/$CUSTOMER" "$WEB_TOKEN" "$UPDATE_CUSTOMER_JSON" 200 06_customer_service.txt
api GET "$WEB_URL/api/bff/web/customers/$CUSTOMER/profile" "$WEB_TOKEN" '' 200 06_customer_service.txt
api PUT "$WEB_URL/api/bff/web/customers/$CUSTOMER/profile" "$WEB_TOKEN" '{"profile":"PREFERENTE"}' 200 06_customer_service.txt
api GET "$MOBILE_URL/api/bff/mobile/customers/$CUSTOMER" "$MOBILE_TOKEN" '' 200 06_customer_service.txt
export CUSTOMER
```

**Resultado esperado:** Registro 201 y cinco funciones 200/201; cliente PREFERENTE; Mobile reduce el contrato. CUSTOMER procede de la respuesta, no de un ID asumido.

**Evidencia:** 06_customer_service.txt, exclusivamente dentro de `$EVID`.

**Criterio de avance:** Cliente real nuevo identificado y perfil correcto; conservar CUSTOMER para aperturas.

<a id="eft-paso-18"></a>

### Paso 18 — Abrir tres cuentas nuevas a saldo cero

**Objetivo:** Crear origen, destino y una cuenta separada para cierre, asociadas al cliente real.

**Directorio de trabajo:** `/home/francisco/proyecto/BACKEND III/EFT/EFT_Francisco_Henriquez/proyecto` (`cd "$PROY"` despues del Paso 00).

**Comandos:**

```bash
ACCOUNT_IDS=()
for name in 'Origen EFT' 'Destino EFT' 'Cierre EFT'; do
  body=$(python3 -c 'import json,sys; print(json.dumps({"customerId":int(sys.argv[1]),"accountName":sys.argv[2],"accountType":"ahorro","interestRate":0.01}))' "$CUSTOMER" "$name")
  api POST "$WEB_URL/api/bff/web/accounts" "$WEB_TOKEN" "$body" 201 07_gestion_cuentas.txt
  id=$(printf '%s' "$HTTP_JSON" | python3 -c 'import json,sys; d=json.load(sys.stdin); assert d["initialBalance"]==d["finalBalance"]==0 and d["state"]=="ACTIVA"; print(d["accountId"])')
  ACCOUNT_IDS+=("$id")
done
export SOURCE=${ACCOUNT_IDS[0]} TARGET=${ACCOUNT_IDS[1]} EMPTY=${ACCOUNT_IDS[2]}
printf 'Cliente=%s Origen=%s Destino=%s Cierre=%s\n' "$CUSTOMER" "$SOURCE" "$TARGET" "$EMPTY" | tee -a "$EVID/07_gestion_cuentas.txt"
api GET "$WEB_URL/api/bff/web/accounts/$SOURCE" "$WEB_TOKEN" '' 200 07_gestion_cuentas.txt
api GET "$MOBILE_URL/api/bff/mobile/accounts/$SOURCE" "$MOBILE_TOKEN" '' 200 07_gestion_cuentas.txt
api GET "$ATM_URL/api/bff/atm/accounts/$SOURCE/balance" "$ATM_TOKEN" '' 200 07_gestion_cuentas.txt
```

**Resultado esperado:** Tres cuentas ACTIVA, initialBalance=finalBalance=0; ID generado sin colision con historicos. Web/Mobile/ATM consultan la misma cuenta operativa.

**Evidencia:** 07_gestion_cuentas.txt, exclusivamente dentro de `$EVID`.

**Criterio de avance:** Tres IDs distintos y saldo cero; el procedimiento no carga saldos desde CSV ni SQL.

<a id="eft-paso-19"></a>

### Paso 19 — Mantener cuenta y cerrar la cuenta vacia

**Objetivo:** Verificar que la administracion no cambia saldos ni permite cerrar una cuenta financiada.

**Directorio de trabajo:** `/home/francisco/proyecto/BACKEND III/EFT/EFT_Francisco_Henriquez/proyecto` (`cd "$PROY"` despues del Paso 00).

**Comandos:**

```bash
api PUT "$WEB_URL/api/bff/web/accounts/$SOURCE" "$WEB_TOKEN" '{"accountName":"Origen Actualizado","accountType":"ahorro","interestRate":0.02}' 200 07_gestion_cuentas.txt
api POST "$WEB_URL/api/bff/web/accounts/$EMPTY/close" "$WEB_TOKEN" '' 200 07_gestion_cuentas.txt
printf '%s' "$HTTP_JSON" | python3 -c 'import json,sys; d=json.load(sys.stdin); assert d["state"]=="CERRADA" and d["finalBalance"]==0'
api GET "$ATM_URL/api/bff/atm/accounts/$EMPTY/balance" "$ATM_TOKEN" '' 200 07_gestion_cuentas.txt
```

**Resultado esperado:** Nombre/tasa actualizados sin cambio monetario; solo EMPTY se cierra con cero.

**Evidencia:** 07_gestion_cuentas.txt, exclusivamente dentro de `$EVID`.

**Criterio de avance:** EMPTY CERRADA y SOURCE/TARGET ACTIVA. No intentar cerrar SOURCE despues del deposito.

<a id="eft-paso-20"></a>

### Paso 20 — Depositar, pagar, transferir y consultar resultado

**Objetivo:** Mover dinero exclusivamente a traves de BFF/Pagos/Core con UUID por operacion.

**Directorio de trabajo:** `/home/francisco/proyecto/BACKEND III/EFT/EFT_Francisco_Henriquez/proyecto` (`cd "$PROY"` despues del Paso 00).

**Comandos:**

```bash
export DEPOSIT_ID=$(python3 -c 'import uuid; print(uuid.uuid4())')
export PAY_ID=$(python3 -c 'import uuid; print(uuid.uuid4())')
export TRANSFER_ID=$(python3 -c 'import uuid; print(uuid.uuid4())')
DEPOSIT_JSON=$(python3 -c 'import json,sys; print(json.dumps({"operationId":sys.argv[1],"accountId":int(sys.argv[2]),"amount":100000}))' "$DEPOSIT_ID" "$SOURCE")
PAY_JSON=$(python3 -c 'import json,sys; print(json.dumps({"operationId":sys.argv[1],"accountId":int(sys.argv[2]),"amount":10000}))' "$PAY_ID" "$SOURCE")
TRANSFER_JSON=$(python3 -c 'import json,sys; print(json.dumps({"operationId":sys.argv[1],"sourceAccountId":int(sys.argv[2]),"targetAccountId":int(sys.argv[3]),"amount":20000}))' "$TRANSFER_ID" "$SOURCE" "$TARGET")
api POST "$WEB_URL/api/bff/web/payments/deposits" "$WEB_TOKEN" "$DEPOSIT_JSON" 200 04_payment_service.txt
api POST "$WEB_URL/api/bff/web/payments" "$WEB_TOKEN" "$PAY_JSON" 200 04_payment_service.txt
api POST "$WEB_URL/api/bff/web/payments/transfers" "$WEB_TOKEN" "$TRANSFER_JSON" 200 04_payment_service.txt
api GET "$WEB_URL/api/bff/web/payments/operations/$TRANSFER_ID" "$WEB_TOKEN" '' 200 04_payment_service.txt
printf '%s' "$HTTP_JSON" | python3 -c 'import json,sys; d=json.load(sys.stdin); assert d["status"]=="COMPLETED" and d["sourceBalance"]==70000 and d["targetBalance"]==20000'
api GET "$MOBILE_URL/api/bff/mobile/payments/operations/$TRANSFER_ID" "$MOBILE_TOKEN" '' 200 08_bff_regresion.txt
api GET "$ATM_URL/api/bff/atm/accounts/$SOURCE/balance" "$ATM_TOKEN" '' 200 04_payment_service.txt
api GET "$ATM_URL/api/bff/atm/accounts/$TARGET/balance" "$ATM_TOKEN" '' 200 04_payment_service.txt
```

**Resultado esperado:** Tres COMPLETED: deposito 100000, pago 10000, transferencia 20000; saldos SOURCE=70000, TARGET=20000, EMPTY=0.

**Evidencia:** 04_payment_service.txt y 08_bff_regresion.txt, exclusivamente dentro de `$EVID`.

**Criterio de avance:** Estados definitivos y saldos correctos. Si aparece UNKNOWN, conservar UUID/datos y consultar ese resultado antes de cualquier reenvio.

<a id="eft-paso-21"></a>

### Paso 21 — Reenviar UUID y verificar efecto unico

**Objetivo:** Repetir exactamente el deposito original y verificar atomicidad/idempotencia despues de operaciones posteriores.

**Directorio de trabajo:** `/home/francisco/proyecto/BACKEND III/EFT/EFT_Francisco_Henriquez/proyecto` (`cd "$PROY"` despues del Paso 00).

**Comandos:**

```bash
api POST "$WEB_URL/api/bff/web/payments/deposits" "$WEB_TOKEN" "$DEPOSIT_JSON" 200 02_operaciones_atomicas.txt
printf '%s' "$HTTP_JSON" | python3 -c 'import json,sys; d=json.load(sys.stdin); assert d["status"]=="COMPLETED" and d["targetBalance"]==100000'
api GET "$ATM_URL/api/bff/atm/accounts/$SOURCE/balance" "$ATM_TOKEN" '' 200 02_operaciones_atomicas.txt
printf '%s' "$HTTP_JSON" | python3 -c 'import json,sys; assert json.load(sys.stdin)["balance"]==70000'
{
  pg -c 'SELECT operacion_id,tipo,estado,monto,saldo_origen,saldo_destino FROM operaciones_cuenta ORDER BY creada_en;'
  pg -c 'SELECT cuenta_id,estado,saldo_operativo FROM cuentas ORDER BY cuenta_id;'
} | tee -a "$EVID/02_operaciones_atomicas.txt"
test "$(pg -At -c 'SELECT count(*) FROM operaciones_cuenta;')" = 3
```

**Resultado esperado:** Devuelve el resultado original del deposito, targetBalance=100000; el saldo vigente sigue en 70000 y solo hay tres operaciones.

**Evidencia:** 02_operaciones_atomicas.txt, exclusivamente dentro de `$EVID`.

**Criterio de avance:** Ningun segundo credito ni dato distinto asociado al mismo UUID. Los tests cubren conflicto, concurrencia y rollback; no provocarlos en recursos anteriores.

<a id="eft-paso-22"></a>

### Paso 22 — Auditar topics y preparar offsets nuevos

**Objetivo:** Comprobar cada particion y establecer posiciones iniciales antes de activar Pagos.

**Directorio de trabajo:** `/home/francisco/proyecto/BACKEND III/EFT/EFT_Francisco_Henriquez/proyecto` (`cd "$PROY"` despues del Paso 00).

Este procedimiento solo se aplica al KAFKA_ID recien creado en el Paso 08, verificado por etiqueta PROJECT, volumen propio y broker vacio. El mismo grupo textual en otro broker pertenece a otro entorno y no debe tocarse. El alcance es exclusivamente este topic nuevo; las particiones verificadas son 0, 1 y 2.

**Comandos:**

```bash
export GROUP=core-banking-withdrawal-group TOPIC=bank.withdrawal.requested
{
  owned "$KAFKA_ID"
  kafka kafka-topics --list
  for topic in bank.withdrawal.requested bank.withdrawal.completed bank.withdrawal.rejected; do
    kafka kafka-topics --describe --topic "$topic"
  done
  kafka kafka-consumer-groups --list
  kafka kafka-get-offsets --topic "$TOPIC" --time -2
  kafka kafka-get-offsets --topic "$TOPIC" --time -1
} | tee -a "$EVID/05_kafka_recuperacion.txt"
test -z "$(kafka kafka-consumer-groups --list)"
starts=$(kafka kafka-get-offsets --topic "$TOPIC" --time -2)
ends=$(kafka kafka-get-offsets --topic "$TOPIC" --time -1)
printf '%s\n%s\n' "$starts" "$ends" | python3 -c '
import sys
rows=[line.strip().split(":") for line in sys.stdin if line.strip()]
assert len(rows)==6
for trio in (rows[:3],rows[3:]):
    assert {int(r[1]) for r in trio}=={0,1,2}
    assert all(r[0]=="bank.withdrawal.requested" and int(r[2])==0 for r in trio)
print("Broker propio sin mensajes: inicio=fin=0 en particiones 0,1,2; grupo ausente.")'
kafka kafka-consumer-groups --reset-offsets --group "$GROUP" --topic "$TOPIC" --to-offset 0 --dry-run | tee -a "$EVID/05_kafka_recuperacion.txt"
```

**Resultado esperado:** Tres topics, 3 particiones/replicacion 1, grupo ausente/inactivo, extremos 0/0 en cada particion; dry-run muestra p0/p1/p2 ->0.

**Evidencia:** 05_kafka_recuperacion.txt, exclusivamente dentro de `$EVID`.

**Criterio de avance:** Comprobar las tres filas del dry-run antes del bloque siguiente. Si algun extremo no es cero o aparece otro consumidor, detenerse sin modificar offsets.

Continuacion del Paso 22, despues de revisar el dry-run y sin consumers activos:

```bash
owned "$KAFKA_ID"
test -z "$(kafka kafka-consumer-groups --list)"
kafka kafka-consumer-groups --reset-offsets --group "$GROUP" --topic "$TOPIC" --to-offset 0 --execute | tee -a "$EVID/05_kafka_recuperacion.txt"
kafka kafka-consumer-groups --describe --group "$GROUP" | tee -a "$EVID/05_kafka_recuperacion.txt"
kafka kafka-consumer-groups --describe --group "$GROUP" --members --verbose | tee -a "$EVID/05_kafka_recuperacion.txt"
```

Resultado y criterio final: CURRENT-OFFSET=LOG-END-OFFSET=0 y LAG=0 para p0/p1/p2, sin miembros. El comando modifica offsets exclusivamente en el broker nuevo; no equivale a modificar datos PostgreSQL. Si se interrumpe aqui, inspeccionar estas posiciones antes de continuar; no repetir un reset sobre un broker que ya tenga solicitudes.

<a id="eft-paso-23"></a>

### Paso 23 — Activar una instancia controlada de Pagos

**Objetivo:** Arrancar un unico proceso consumidor en la red nueva, conservando las replicas normales desactivadas.

**Directorio de trabajo:** `/home/francisco/proyecto/BACKEND III/EFT/EFT_Francisco_Henriquez/proyecto` (`cd "$PROY"` despues del Paso 00).

compose run admite -e para sustituir el valor false hardcodeado del servicio, sin editar YAML ni Config Server. Esta instancia tambien se registra en Eureka: durante esta fase habra dos instancias Pagos (la normal inactiva y la de Kafka). No usar wait_registry 1/2 hasta detener la instancia optativa en el Paso 26.

**Comandos:**

```bash
PAYMENT_KAFKA_ID=$("${DC[@]}" run --no-deps -d --name "$PROJECT-payment-kafka" -e PAYMENT_KAFKA_WITHDRAWALS_ENABLED=true -e SPRING_KAFKA_LISTENER_AUTO_STARTUP=true -e SPRING_KAFKA_CONSUMER_AUTO_OFFSET_RESET=none payment-service)
export PAYMENT_KAFKA_ID
{
  for id in "$KAFKA_ID" "$PAYMENT_KAFKA_ID" "$CORE_ID" "$PAYMENT_ID"; do owned "$id"; done
  wait_health "$PAYMENT_KAFKA_ID"
  docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$PAYMENT_KAFKA_ID" | rg '^(PAYMENT_KAFKA_WITHDRAWALS_ENABLED|SPRING_KAFKA_LISTENER_AUTO_STARTUP|SPRING_KAFKA_CONSUMER_AUTO_OFFSET_RESET)='
  docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$CORE_ID" | rg -x 'SPRING_KAFKA_LISTENER_AUTO_STARTUP=false'
  for flag in PAYMENT_KAFKA_WITHDRAWALS_ENABLED SPRING_KAFKA_LISTENER_AUTO_STARTUP; do
    docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$PAYMENT_ID" | rg -x "$flag=false"
  done
  python3 - "$PROJECT" "$KAFKA_ID" "$PAYMENT_KAFKA_ID" "$CORE_ID" "$PAYMENT_ID" "$GROUP" "$TOPIC" <<'PY'
import json,re,subprocess,sys,time
project,broker,payment,core,normal,group,topic=sys.argv[1:]
deadline=time.monotonic()+120
def run(args):
    remaining=deadline-time.monotonic()
    if remaining<=0: raise TimeoutError('Tiempo maximo de 120 s agotado')
    return subprocess.run(args,check=True,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=min(5,remaining)).stdout
def inspect(container,template):
    return run(['docker','inspect','--format',template,container]).strip()
ips={}
for container in (broker,payment,core,normal):
    assert inspect(container,'{{index .Config.Labels "com.docker.compose.project"}}')==project
    networks=json.loads(inspect(container,'{{json .NetworkSettings.Networks}}'))
    assert set(networks)=={project+'_default'}
    ips[container]=networks[project+'_default']['IPAddress']
assert len(set(ips.values()))==4 and all(ips.values())
def describe(*options):
    assert inspect(broker,'{{index .Config.Labels "com.docker.compose.project"}}')==project
    return run(['docker','exec','-i',broker,'/opt/kafka/bin/kafka-consumer-groups.sh','--bootstrap-server','kafka:9092','--describe','--group',group,*options])
members_text=offsets_text=''; ready=False; attempt=0
while time.monotonic()<deadline:
    attempt+=1
    try:
        members_text=describe('--members','--verbose')
        offsets_text=describe('--offsets')
        members=[s.split() for s in members_text.splitlines() if s.startswith(group+' ')]
        offsets=[s.split() for s in offsets_text.splitlines() if s.startswith(group+' ')]
        assert len(members)==len(offsets)==3, 'Faltan tres miembros o tres filas de offsets'
        assignments={}
        for row in members:
            assert len(row)>=6 and row[2]=='/'+ips[payment] and row[4]=='1', 'HOST o asignacion no corresponde a Pagos'
            match=re.fullmatch(r'(?:'+re.escape(topic)+r')?\(([012])\)', ''.join(row[5:]))
            assert match, 'Asignacion inicial inesperada'
            assignments[row[1]]=int(match[1])
        assert len(assignments)==3 and set(assignments.values())=={0,1,2}
        assert {int(row[2]) for row in offsets}=={0,1,2}
        for row in offsets:
            assert len(row)==9 and row[1]==topic and row[7]=='/'+ips[payment]
            assert assignments[row[6]]==int(row[2]), 'Miembro y particion no coinciden'
            assert tuple(map(int,row[3:6]))==(0,0,0), 'Offset inicial, extremo o lag no es cero'
        assert all(row[2]!='/'+ips[core] and row[2]!='/'+ips[normal] for row in members)
        ready=True; break
    except (AssertionError,ValueError,KeyError,subprocess.SubprocessError,TimeoutError) as exc:
        print('Espera Kafka, intento',attempt,':',exc,flush=True)
        remaining=deadline-time.monotonic()
        if remaining>0: time.sleep(min(2,remaining))
print(members_text,offsets_text,sep='\n')
assert ready, 'No se confirmo el listener en 120 s; detenerse sin enviar el retiro ni resetear offsets'
print('Listener verificado en broker propio:',broker,'Pagos='+ips[payment],'Core='+ips[core],'; tres miembros, p0/p1/p2, offsets 0/0, lag 0; Core sin miembro activo.')
PY
} | tee -a "$EVID/05_kafka_recuperacion.txt"
```

**Resultado esperado:** Despues de health, la espera del grupo dura como maximo 120 s, con cada consulta limitada a 5 s o al tiempo restante. Una instancia consumidora de Pagos aporta exactamente tres miembros distintos, p0/p1/p2 asignadas una vez y offsets iniciales numericos 0/0/lag 0; todos los HOST coinciden con PAYMENT_KAFKA_ID y ninguno con Core/Pagos normal. Solo se inspecciona el broker identificado por KAFKA_ID, etiqueta PROJECT y red nueva.

**Evidencia:** 05_kafka_recuperacion.txt, exclusivamente dentro de `$EVID`.

**Criterio de avance:** El bloque debe terminar con la comprobacion real del listener; timeout, miembro inesperado, offset ausente o NoOffsetForPartition obligan a detenerse antes del Paso 24. /actuator/health UP por si solo no demuestra que el listener este activo. Esta espera solo consulta metadatos: no publica, confirma ni resetea offsets.

<a id="eft-paso-24"></a>

### Paso 24 — Solicitar retiro ATM y esperar resultado

**Objetivo:** Verificar 202/PENDING, procesamiento Kafka/Pagos, debito Core y resultado terminal.

**Directorio de trabajo:** `/home/francisco/proyecto/BACKEND III/EFT/EFT_Francisco_Henriquez/proyecto` (`cd "$PROY"` despues del Paso 00).

**Comandos:**

```bash
api GET "$ATM_URL/api/bff/atm/accounts/$SOURCE/balance" "$ATM_TOKEN" '' 200 05_kafka_recuperacion.txt
printf '%s' "$HTTP_JSON" | python3 -c 'import json,sys; assert json.load(sys.stdin)["balance"]==70000'
api POST "$ATM_URL/api/bff/atm/accounts/$SOURCE/withdraw" "$ATM_TOKEN" '{"amount":10000}' 202 05_kafka_recuperacion.txt
TX=$(printf '%s' "$HTTP_JSON" | python3 -c 'import json,sys,uuid; d=json.load(sys.stdin); assert d["status"]=="PENDING"; print(uuid.UUID(d["transactionId"]))')
export TX
FINAL=''
for attempt in {1..60}; do
  response=$(curl -sS --max-time 15 -H "Authorization: Bearer $ATM_TOKEN" -w $'\n%{http_code}' "$ATM_URL/api/bff/atm/accounts/transactions/$TX")
  code=${response##*$'\n'}; body=${response%$'\n'*}
  printf 'Poll %s HTTP=%s %s\n' "$attempt" "$code" "$body" | tee -a "$EVID/05_kafka_recuperacion.txt"
  if [ "$code" = 200 ]; then
    FINAL=$(printf '%s' "$body" | python3 -c 'import json,sys; print(json.load(sys.stdin)["status"])')
    if [ "$FINAL" = COMPLETED ] || [ "$FINAL" = REJECTED ]; then break; fi
  elif [ "$code" != 503 ]; then printf 'ERROR conservar TX=%s\n' "$TX"; exit 1; fi
  sleep 2
done
test "$FINAL" = COMPLETED
api GET "$ATM_URL/api/bff/atm/accounts/$SOURCE/balance" "$ATM_TOKEN" '' 200 05_kafka_recuperacion.txt
printf '%s' "$HTTP_JSON" | python3 -c 'import json,sys; assert json.load(sys.stdin)["balance"]==60000'
{
  docker logs "$ATM_ID" 2>&1 | rg -F "$TX"
  docker logs "$PAYMENT_KAFKA_ID" 2>&1 | rg -F "$TX"
  pg -c "SELECT transaction_id,status,final_balance,rejection_reason FROM withdrawal_transactions WHERE transaction_id='$TX';"
  pg -c "SELECT event_id,transaction_id,event_type FROM banking_events WHERE transaction_id='$TX' ORDER BY occurred_at;"
  kafka kafka-consumer-groups --describe --group "$GROUP"
} | tee -a "$EVID/05_kafka_recuperacion.txt"
```

**Resultado esperado:** 202/PENDING -> COMPLETED y saldo 60000. Dos eventos persistidos para TX: WithdrawalRequested/WithdrawalCompleted; registro monetario con el mismo TX; resultado Kafka confirmado antes del offset.

**Evidencia:** 05_kafka_recuperacion.txt, exclusivamente dentro de `$EVID`.

**Criterio de avance:** Estado definitivo, saldo y lag de la particion consumida en cero. Ante 401 renovar token y retomar el mismo TX; ante timeout conservar TX y revisar el listener, sin otro POST ATM.

<a id="eft-paso-25"></a>

### Paso 25 — Republicar la solicitud exacta y probar idempotencia Kafka

**Objetivo:** Reentregar el evento original sin crear otra transaccion ni descontar nuevamente.

**Directorio de trabajo:** `/home/francisco/proyecto/BACKEND III/EFT/EFT_Francisco_Henriquez/proyecto` (`cd "$PROY"` despues del Paso 00).

Los escenarios de PostgreSQL confirmado/publicacion fallida y ACK fallido se reproducen con WithdrawalKafkaIntegrationTest, Paso 28; no se detiene un broker existente. La traza Kafka de este paso se completa con la lectura limitada siguiente. El estado HTTP terminal no sustituye esa comprobacion de publicacion.

**Comandos:**

```bash
EVENT_JSON=$(pg -At -c "SELECT payload::text FROM banking_events WHERE transaction_id='$TX' AND event_type='WithdrawalRequested';")
printf '%s' "$EVENT_JSON" | python3 -c 'import json,sys,uuid; d=json.load(sys.stdin); assert str(uuid.UUID(d["transactionId"]))==sys.argv[1] and d["accountId"]==int(sys.argv[2]); assert d["amount"]==10000' "$TX" "$SOURCE"
printf '%s\t%s\n' "$SOURCE" "$EVENT_JSON" | kafka kafka-console-producer --topic "$TOPIC" --property parse.key=true --property $'key.separator=\t'
lag_zero=0
for attempt in {1..60}; do
  group_output=$(kafka kafka-consumer-groups --describe --group "$GROUP")
  if printf '%s\n' "$group_output" | python3 -c '
import sys
rows=[s.split() for s in sys.stdin if s.startswith("core-banking-withdrawal-group ")]
assert len(rows)==3 and all(r[3]==r[4] and r[5]=="0" for r in rows)
' 2>/dev/null; then lag_zero=1; break; fi
  sleep 2
done
test "$lag_zero" = 1
{
  printf '%s\n' "$group_output"
  pg -c "SELECT count(*) operaciones FROM operaciones_cuenta WHERE operacion_id='$TX'; SELECT count(*) retiros FROM withdrawal_transactions WHERE transaction_id='$TX'; SELECT event_type,count(*) FROM banking_events WHERE transaction_id='$TX' GROUP BY event_type; SELECT cuenta_id,saldo_operativo FROM cuentas ORDER BY cuenta_id;"
} | tee -a "$EVID/05_kafka_recuperacion.txt"
test "$(pg -At -c "SELECT count(*) FROM operaciones_cuenta WHERE operacion_id='$TX';")" = 1
test "$(pg -At -c "SELECT saldo_operativo FROM cuentas WHERE cuenta_id=$SOURCE;")" = 60000.00
```

**Resultado esperado:** Una operacion, un retiro y dos eventos PostgreSQL para TX; SOURCE conserva 60000. Kafka puede contener dos entregas del resultado con el mismo eventId.

**Evidencia:** 05_kafka_recuperacion.txt, exclusivamente dentro de `$EVID`.

**Criterio de avance:** Offsets igual a fin y mismo saldo. El productor CLI sin type headers es compatible porque Pagos declara spring.json.use.type.headers=false y default.type=WithdrawalRequestedEvent.

Leer resultados del broker nuevo por particion, sin participar en el grupo monetario:

```bash
for topic in bank.withdrawal.completed bank.withdrawal.rejected; do
  ends=$(kafka kafka-get-offsets --topic "$topic" --time -1)
  while IFS=: read -r topic_name partition end; do
    if [ "$end" -gt 0 ]; then
      kafka kafka-console-consumer --topic "$topic_name" --partition "$partition" --offset earliest --max-messages "$end" --timeout-ms 15000 --property print.partition=true --property print.offset=true --property print.key=true | tee -a "$EVID/05_kafka_recuperacion.txt"
    fi
  done <<< "$ends"
done
```

Resultado: dos resultados COMPLETED para el mismo TX despues de la reentrega, con el mismo eventId/amount/finalBalance que el payload PostgreSQL; no exigir unicidad de entrega. Esta lectura con asignacion explicita por particion no confirma offsets del grupo de Pagos.

<a id="eft-paso-26"></a>

### Paso 26 — Preparar y verificar dos replicas HTTP por servicio

**Objetivo:** Detener solo el consumidor optativo propio despues de lag cero y escalar los servicios normales.

**Directorio de trabajo:** `/home/francisco/proyecto/BACKEND III/EFT/EFT_Francisco_Henriquez/proyecto` (`cd "$PROY"` despues del Paso 00).

docker stop afecta exclusivamente el ID verificado de esta reproduccion. No se detienen contenedores anteriores. El grupo puede tardar en liberar miembros; esperar y comprobar antes de declarar inactivo.

**Comandos:**

```bash
owned "$PAYMENT_KAFKA_ID"
docker stop "$PAYMENT_KAFKA_ID"
"${DC[@]}" up -d --no-deps --no-build --pull never --wait --wait-timeout 240 --scale core-banking-service=2 --scale payment-service=2 --scale customer-service=2 core-banking-service payment-service customer-service
{
  wait_registry 2
  for service in core-banking-service payment-service customer-service; do
    ids=$("${DC[@]}" ps -q "$service")
    test "$(printf '%s\n' "$ids" | wc -l)" = 2
    while IFS= read -r id; do wait_health "$id"; done <<< "$ids"
  done
  for id in $("${DC[@]}" ps -q payment-service); do
    docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$id" | rg '^PAYMENT_KAFKA_WITHDRAWALS_ENABLED=false$'
  done
  kafka kafka-consumer-groups --describe --group "$GROUP" --members --verbose
} | tee -a "$EVID/09_empaquetado_configuracion.txt"
```

**Resultado esperado:** Eureka: dos Core/dos Pagos/dos Clientes, tres BFF; seis servicios bancarios healthy. Grupo Kafka conserva offsets sin miembros activos. La instancia optativa queda detenida y conservada, no eliminada.

**Evidencia:** 09_empaquetado_configuracion.txt, exclusivamente dentro de `$EVID`.

**Criterio de avance:** Eureka deja de mostrar la instancia Pagos detenida y registra exactamente dos UP normales; no reejecucion Batch.

<a id="eft-paso-27"></a>

### Paso 27 — Demostrar distribucion LoadBalancer

**Objetivo:** Observar accesos reales de 48 GET por servicio en sus dos instancias.

**Directorio de trabajo:** `/home/francisco/proyecto/BACKEND III/EFT/EFT_Francisco_Henriquez/proyecto` (`cd "$PROY"` despues del Paso 00).

**Comandos:**

```bash
access_count() {
  local id="$1" service="$2" path="$3"
  owned "$id"
  docker exec "$id" cat "/tmp/eft9-access/central-$service.log" | python3 -c 'import sys; p=sys.argv[1]; print(sum(("\"GET "+p+" HTTP/") in l and l.rstrip().endswith(" 200") for l in sys.stdin))' "$path"
}
declare -A BEFORE
for service in core-banking-service customer-service payment-service; do
  case "$service" in
    core-banking-service) public="/api/bff/web/accounts/$SOURCE"; internal="/api/core/accounts/$SOURCE" ;;
    customer-service) public="/api/bff/web/customers/$CUSTOMER"; internal="/api/customers/$CUSTOMER" ;;
    payment-service) public="/api/bff/web/payments/operations/$DEPOSIT_ID"; internal="/api/payments/operations/$DEPOSIT_ID" ;;
  esac
  ids=$("${DC[@]}" ps -q "$service")
  for id in $ids; do BEFORE[$id]=$(access_count "$id" "$service" "$internal"); done
  for attempt in {1..48}; do api GET "$WEB_URL$public" "$WEB_TOKEN" '' 200 09_empaquetado_configuracion.txt; done
  sum=0; both=0
  for flush_attempt in {1..20}; do
    sum=0; both=0
    for id in $ids; do
      after=$(access_count "$id" "$service" "$internal")
      delta=$((after-BEFORE[$id]))
      sum=$((sum+delta))
      if [ "$delta" -gt 0 ]; then both=$((both+1)); fi
      printf '%s instancia=%s accesos=%s\n' "$service" "$id" "$delta" | tee -a "$EVID/09_empaquetado_configuracion.txt"
    done
    if [ "$sum" = 48 ] && [ "$both" = 2 ]; then break; fi
    sleep 2
  done
  test "$sum" = 48
  test "$both" = 2
done
```

**Resultado esperado:** 48 HTTP 200 por servicio, ambas instancias reciben consultas y la suma por servicio es 48. Reparto historico observado 24/24; registrar el reparto actual sin imponerlo como garantia.

**Evidencia:** 09_empaquetado_configuracion.txt, exclusivamente dentro de `$EVID`.

**Criterio de avance:** Sin otras consultas concurrentes a esas rutas durante la medicion; logs con patron sin Authorization y resultados correctos en los seis IDs.

<a id="eft-paso-28"></a>

### Paso 28 — Verificar resiliencia, UNKNOWN, recuperacion Kafka y Batch por tests

**Objetivo:** Reproducir fallos en infraestructura exclusiva de los tests, sin detener servicios del stack anterior.

**Directorio de trabajo:** `/home/francisco/proyecto/BACKEND III/EFT/EFT_Francisco_Henriquez/proyecto` (`cd "$PROY"` despues del Paso 00).

WithdrawalKafkaIntegrationTest prueba PostgreSQL confirmado con publicacion fallida, perdida de respuesta, ACK de entrada fallido, conflicto y recuperacion con el mismo evento. El equivalente actual de la correccion mensual ejecuta dos Jobs y compara seis columnas de las 50 filas contra CSV.

**Comandos:**

```bash
{
  ./mvnw -pl bff-web-service,bff-mobile-service,bff-atm-service -am -Dtest=WebResilience4jTest,MobileResilience4jTest,AtmResilience4jTest,WebServicesResilienceTest,MobileServicesResilienceTest -Dsurefire.failIfNoSpecifiedTests=false test 2>&1 | rg '^\[(INFO|ERROR|WARNING)\]'
  report_cases 16 WebResilience4jTest MobileResilience4jTest AtmResilience4jTest WebServicesResilienceTest MobileServicesResilienceTest
} | tee -a "$EVID/08_bff_regresion.txt"
{
  ./mvnw -pl payment-service -am -Dtest=PaymentApplicationsIntegrationTest,WithdrawalKafkaIntegrationTest,CoreWithdrawalClientTest,WithdrawalRequestedConsumerTest -Dsurefire.failIfNoSpecifiedTests=false test 2>&1 | rg '^\[(INFO|ERROR|WARNING)\]'
  report_cases 40 PaymentApplicationsIntegrationTest WithdrawalKafkaIntegrationTest CoreWithdrawalClientTest WithdrawalRequestedConsumerTest
} | tee -a "$EVID/05_kafka_recuperacion.txt"
{
  ./mvnw -pl core-banking-service -am -Dtest=DailyTransactionsJobIntegrationTest,MonthlyInterestJobIntegrationTest,AnnualMovementsStepIntegrationTest,MonthlyInterestDeterminismIntegrationTest,OperationalBatchIsolationIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test 2>&1 | rg '^\[(INFO|ERROR|WARNING)\]'
  report_cases 5 DailyTransactionsJobIntegrationTest MonthlyInterestJobIntegrationTest AnnualMovementsStepIntegrationTest MonthlyInterestDeterminismIntegrationTest OperationalBatchIsolationIntegrationTest
} | tee "$EVID/10_correccion_batch_mensual.txt"
```

**Resultado esperado:** 16 resiliencia (Web/Mobile/ATM estados internos, short-circuit, errores funcionales y UNKNOWN); 40 Pagos/Kafka (incluidos 10 Kafka reales); 5 Batch/aislamiento/determinismo. Son reejecuciones de casos ya incluidos en 337.

**Evidencia:** 08_bff_regresion.txt, 05_kafka_recuperacion.txt y 10_correccion_batch_mensual.txt, exclusivamente dentro de `$EVID`.

**Criterio de avance:** Cero fallos y BUILD SUCCESS en los tres comandos. La prueba PaymentApplicationsIntegrationTest.lostResponseAfterCommitIsUnknownAndSameIdentifierRecoversWithoutChargingAgain y BffApplicationsOAuth2IT del Paso 05 cubren UNKNOWN con UUID conservado.

<a id="eft-paso-29"></a>

### Paso 29 — Consultar historia Web y Mobile procedente de CSV

**Objetivo:** Seleccionar una cuenta historica real sin inventar movimientos para cuentas API.

**Directorio de trabajo:** `/home/francisco/proyecto/BACKEND III/EFT/EFT_Francisco_Henriquez/proyecto` (`cd "$PROY"` despues del Paso 00).

**Comandos:**

```bash
HIST=$(pg -At -c 'SELECT m.cuenta_id FROM movimientos_anuales m JOIN intereses_mensuales i USING(cuenta_id) GROUP BY m.cuenta_id ORDER BY m.cuenta_id LIMIT 1;')
[[ "$HIST" =~ ^[0-9]+$ ]]
{
  pg -c "SELECT cuenta_id,nombre,tipo,saldo_inicial,tasa,saldo_final FROM intereses_mensuales WHERE cuenta_id=$HIST;"
  pg -c "SELECT cuenta_id,fecha,transaccion,monto,descripcion FROM movimientos_anuales WHERE cuenta_id=$HIST ORDER BY fecha DESC,movimiento_id DESC LIMIT 5;"
  printf 'Cuenta historica seleccionada=%s\n' "$HIST"
} | tee -a "$EVID/08_bff_regresion.txt"
api GET "$WEB_URL/api/bff/web/accounts/$HIST/annual-statement" "$WEB_TOKEN" '' 200 08_bff_regresion.txt
api GET "$MOBILE_URL/api/bff/mobile/accounts/$HIST/transactions" "$MOBILE_TOKEN" '' 200 08_bff_regresion.txt
printf '%s' "$HTTP_JSON" | python3 -c 'import json,sys; d=json.load(sys.stdin); assert len(d)==5; assert [i["date"] for i in d]==sorted((i["date"] for i in d),reverse=True)'
api GET "$WEB_URL/api/bff/web/accounts/$SOURCE/annual-statement" "$WEB_TOKEN" '' 200 08_bff_regresion.txt
printf '%s' "$HTTP_JSON" | python3 -c 'import json,sys; assert json.load(sys.stdin)["movementCount"]==0'
api GET "$MOBILE_URL/api/bff/mobile/accounts/$SOURCE/transactions" "$MOBILE_TOKEN" '' 200 08_bff_regresion.txt
printf '%s' "$HTTP_JSON" | python3 -c 'import json,sys; assert json.load(sys.stdin)==[]'
```

**Resultado esperado:** Estado anual y ultimos cinco movimientos proceden de movimientos_anuales del CSV. SOURCE operativo devuelve estado anual vacio/0 y lista vacia: no se crean historicos por deposito o retiro.

**Evidencia:** 08_bff_regresion.txt, exclusivamente dentro de `$EVID`.

**Criterio de avance:** Consultas API coherentes con PostgreSQL y separacion operativa/historica mantenida.

<a id="eft-paso-30"></a>

### Paso 30 — Reejecutar el mensual y comparar historia/saldos

**Objetivo:** Demostrar determinismo con imagen corregida y ausencia de impacto sobre dinero posterior.

**Directorio de trabajo:** `/home/francisco/proyecto/BACKEND III/EFT/EFT_Francisco_Henriquez/proyecto` (`cd "$PROY"` despues del Paso 00).

El unico lanzador estable existente ejecuta los tres Jobs juntos. Por eso la segunda corrida es explicita, con ambos init=never y nombre nuevo; no se presenta como reejecucion exclusivamente mensual. Daily ya tiene datos y puede escribir 0/omitir 1000; su tabla permanece en 387. El SHA capturado de psql es del formato actual y no se compara con otro formato historico.

**Comandos:**

```bash
MONTHLY_BEFORE=$(pg -At -c 'SELECT cuenta_id,nombre,tipo,saldo_inicial,tasa,saldo_final FROM intereses_mensuales ORDER BY cuenta_id;')
BALANCES_BEFORE=$(pg -At -c 'SELECT cuenta_id,estado,saldo_operativo FROM cuentas ORDER BY cuenta_id;')
JOBS_BEFORE=$(pg -At -c 'SELECT count(*) FROM batch_job_execution;')
REPEAT_BATCH_ID=$("${DC[@]}" run --no-deps -d --name "$PROJECT-batch-repeat" -e SPRING_SQL_INIT_MODE=never -e SPRING_BATCH_JDBC_INITIALIZE_SCHEMA=never batch-init)
done_repeat=0
for attempt in {1..150}; do
  state=$(docker inspect --format '{{.State.Status}} {{.State.ExitCode}}' "$REPEAT_BATCH_ID")
  if [ "$state" = 'exited 0' ]; then done_repeat=1; break; fi
  if [[ "$state" == exited* ]]; then docker logs "$REPEAT_BATCH_ID"; exit 1; fi
  sleep 2
done
test "$done_repeat" = 1
MONTHLY_AFTER=$(pg -At -c 'SELECT cuenta_id,nombre,tipo,saldo_inicial,tasa,saldo_final FROM intereses_mensuales ORDER BY cuenta_id;')
BALANCES_AFTER=$(pg -At -c 'SELECT cuenta_id,estado,saldo_operativo FROM cuentas ORDER BY cuenta_id;')
test "$MONTHLY_BEFORE" = "$MONTHLY_AFTER"
test "$BALANCES_BEFORE" = "$BALANCES_AFTER"
test "$(pg -At -c 'SELECT count(*) FROM batch_job_execution;')" = "$((JOBS_BEFORE+3))"
{
  docker logs "$REPEAT_BATCH_ID"
  printf 'Mensual 50 filas/seis columnas identicas; saldos operativos conservados.\n'
  printf '%s\n' "$MONTHLY_AFTER" | sha256sum
  pg -c 'SELECT step_name,status,read_count,write_count,read_skip_count+process_skip_count+write_skip_count skips FROM batch_step_execution ORDER BY step_execution_id;'
} | tee -a "$EVID/10_correccion_batch_mensual.txt"
```

**Resultado esperado:** Segunda ejecucion intencional de los tres Jobs; dos mensuales 1000/263/737 con 50 filas identicas y saldo operativo 60000/20000/0 intacto; ahora seis Jobs COMPLETED.

**Evidencia:** 10_correccion_batch_mensual.txt, exclusivamente dentro de `$EVID`.

**Criterio de avance:** Comparacion literal de las seis columnas y todas las cuentas; no reinicializacion de metadatos. El oracle completo CSV se verifica con MonthlyInterestDeterminismIntegrationTest del Paso 28.

<a id="eft-paso-31"></a>

### Paso 31 — Verificar integridad final PostgreSQL/Kafka/Eureka

**Objetivo:** Comprobar estados, saldos, relaciones y ausencia de cargos duplicados con consultas de lectura.

**Directorio de trabajo:** `/home/francisco/proyecto/BACKEND III/EFT/EFT_Francisco_Henriquez/proyecto` (`cd "$PROY"` despues del Paso 00).

Las PK impiden duplicar filas; la prueba del mismo UUID mas saldos/resultados demuestra efecto monetario unico. La repeticion de mensajes Kafka no aumenta filas PostgreSQL. Todas las SQL son SELECT; no alterar saldos por SQL.

**Comandos:**

```bash
{
  pg -c 'SELECT i.job_name,e.status,count(*) FROM batch_job_instance i JOIN batch_job_execution e USING(job_instance_id) GROUP BY i.job_name,e.status ORDER BY i.job_name;'
  pg -c "SELECT 'daily' tabla,count(*) FROM transacciones_procesadas UNION ALL SELECT 'monthly',count(*) FROM intereses_mensuales UNION ALL SELECT 'annual',count(*) FROM movimientos_anuales UNION ALL SELECT 'clientes',count(*) FROM clientes UNION ALL SELECT 'cuentas',count(*) FROM cuentas UNION ALL SELECT 'operaciones',count(*) FROM operaciones_cuenta UNION ALL SELECT 'retiros',count(*) FROM withdrawal_transactions UNION ALL SELECT 'eventos',count(*) FROM banking_events;"
  pg -c 'SELECT cuenta_id,cliente_id,estado,saldo_operativo FROM cuentas ORDER BY cuenta_id;'
  pg -c 'SELECT estado,count(*) FROM operaciones_cuenta GROUP BY estado; SELECT status,count(*) FROM withdrawal_transactions GROUP BY status;'
  pg -c "SELECT o.operacion_id FROM operaciones_cuenta o JOIN withdrawal_transactions w ON w.transaction_id=o.operacion_id WHERE o.tipo<>'DEBIT' OR o.cuenta_origen_id<>w.account_id OR o.monto<>w.amount OR o.estado<>w.status;"
  pg -c 'SELECT operacion_id,count(*) FROM operaciones_cuenta GROUP BY operacion_id HAVING count(*)>1; SELECT transaction_id,count(*) FROM withdrawal_transactions GROUP BY transaction_id HAVING count(*)>1; SELECT cuenta_id,count(*) FROM intereses_mensuales GROUP BY cuenta_id HAVING count(*)>1;'
  pg -c "SELECT transaction_id FROM banking_events GROUP BY transaction_id HAVING count(*) FILTER(WHERE event_type='WithdrawalRequested')<>1 OR count(*) FILTER(WHERE event_type IN ('WithdrawalCompleted','WithdrawalRejected'))<>1;"
  pg -c "SELECT c.cuenta_id FROM cuentas c LEFT JOIN clientes p ON p.cliente_id=c.cliente_id WHERE p.cliente_id IS NULL; SELECT cuenta_id FROM cuentas WHERE saldo_operativo<0 OR (estado='CERRADA' AND saldo_operativo<>0);"
  kafka kafka-consumer-groups --describe --group "$GROUP"
  kafka kafka-consumer-groups --describe --group "$GROUP" --members --verbose
  wait_registry 2
  docker ps -a --filter "label=com.docker.compose.project=$PROJECT" --format '{{.Names}} {{.Status}} {{.Ports}}'
} | tee "$EVID/10_integracion_aislada.txt"
test "$(pg -At -c 'SELECT count(*) FROM operaciones_cuenta;')" = 4
test "$(pg -At -c 'SELECT count(*) FROM withdrawal_transactions;')" = 1
test "$(pg -At -c 'SELECT count(*) FROM banking_events;')" = 2
test "$(pg -At -c "SELECT count(*) FROM batch_job_execution WHERE status<>'COMPLETED';")" = 0
```

**Resultado esperado:** Seis Jobs COMPLETED (dos de cada nombre), historicos 387/50/289, un cliente, tres cuentas (dos ACTIVA/una CERRADA), cuatro operaciones COMPLETED, un retiro y dos eventos; saldos 60000/20000/0. Consultas de inconsistencias/duplicados devuelven cero filas. Kafka lag cero sin consumidores activos despues del Paso 26; Eureka dos replicas.

**Evidencia:** 10_integracion_aislada.txt, exclusivamente dentro de `$EVID`.

**Criterio de avance:** Todos los conteos y saldos propios coinciden. No esperar los 14/8/16 del entorno historico: corresponden a otra secuencia de operaciones.

<a id="eft-paso-32"></a>

### Paso 32 — Cerrar evidencias y verificar 348 distintos

**Objetivo:** Restituir exclusivamente indice_evidencias.md, revisar los once TXT regenerados y seis anexos reales, y comprobar el inventario y el estado conservado de contenedores.

**Directorio de trabajo:** `/home/francisco/proyecto/BACKEND III/EFT/EFT_Francisco_Henriquez/proyecto` (`cd "$PROY"` despues del Paso 00).

Todas las evidencias nuevas estan directamente en "$ROOT/evidencias/EFT". Primero comprobar nuevamente el respaldo hermano ubicado en "$ROOT/evidencias" y su manifiesto, y restituir solo indice_evidencias.md

**Comandos:**

```bash
test "$(sha256sum "$BACKUP_MANIFEST" | cut -d ' ' -f 1)" = "$BACKUP_VERIFIED_SHA256"
diff -u "$BACKUP_MANIFEST" <(eft_inventory "$BACKUP")
python3 - "$EVID" "$BACKUP" "$BACKUP_MANIFEST" "${EFT_DOCS[@]}" <<'PY' | tee -a "$EVID/10_integracion_aislada.txt"
import hashlib,json,pathlib,shutil,sys
active,backup,manifest=map(pathlib.Path,sys.argv[1:4]); names=sys.argv[4:]
expected=pathlib.Path('/home/francisco/proyecto/BACKEND III/EFT/EFT_Francisco_Henriquez/evidencias/EFT')
assert active==expected and active.resolve()==expected and active.is_dir() and not active.is_symlink()
assert backup.resolve()==backup and backup.is_dir() and backup.parent==expected.parent and not backup.is_symlink()
assert backup.name.startswith('respaldo-evidencias-EFT-')
assert backup!=active and active not in backup.parents and backup not in active.parents
assert manifest==pathlib.Path(str(backup)+'.manifest.json') and manifest.parent==expected.parent and not manifest.is_symlink()
assert names==['indice_evidencias.md']
entries=json.loads(manifest.read_text())
for name in names:
    source=backup/name; target=active/name
    assert source.is_file() and not source.is_symlink()
    assert not target.exists() and not target.is_symlink(), 'Documento ya presente: '+name
    assert hashlib.sha256(source.read_bytes()).hexdigest()==entries[name]['sha256']
for name in names:
    source=backup/name; target=active/name
    shutil.copy2(source,target)
    digest=hashlib.sha256(target.read_bytes()).hexdigest()
    assert digest==entries[name]['sha256']
    print('Documento restituido desde respaldo verificado:',name,'SHA256='+digest)
PY
```

Continuacion del Paso 32, despues de revisar el indice y los criterios de los pasos anteriores:

```bash
{
  report_cases 348 '*'
  docker ps -a --filter "label=com.docker.compose.project=$PROJECT" --format '{{.Names}} {{.Status}} {{.Ports}}'
  for id in $(docker ps -q --filter "label=com.docker.compose.project=$PROJECT"); do wait_health "$id"; done
  printf 'Recursos conservados para revision: %s\n' "$PROJECT"
} | tee -a "$EVID/10_integracion_aislada.txt"
python3 - "$EVID" "$RUN_STARTED" "$BACKUP_MANIFEST" "${EFT_TXT[@]}" <<'PY' | tee -a "$EVID/10_integracion_aislada.txt"
import csv,datetime,hashlib,json,pathlib,re,sys
p=pathlib.Path(sys.argv[1]); started=int(sys.argv[2]); saved=json.loads(pathlib.Path(sys.argv[3]).read_text()); names=sys.argv[4:]
expected=pathlib.Path('/home/francisco/proyecto/BACKEND III/EFT/EFT_Francisco_Henriquez/evidencias/EFT')
assert p==expected and p.resolve()==expected and p.is_dir() and not p.is_symlink()
extras=['preparacion.txt','base_nueva.txt','batch_inicial.txt','core_clientes_it.txt','bff_oauth_it.txt','estados_cuenta_anuales.csv']
docs=['indice_evidencias.md']
wanted=set(names+extras+docs); observed={f.name for f in p.iterdir()}
assert len(names)==11 and len(wanted)==18
assert observed==wanted, {'faltantes':sorted(wanted-observed),'extras':sorted(observed-wanted)}
texts={}
for name in sorted(wanted):
    f=p/name; st=f.stat()
    assert f.is_file() and not f.is_symlink() and st.st_size>0, name
    if name not in docs: assert st.st_mtime>=started, 'Fecha anterior a esta ejecucion: '+name
    texts[name]=f.read_text()
    assert not re.search(r'eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+',texts[name]), 'JWT completo en '+name
    if name!='10_integracion_aislada.txt':
        print(name,'bytes='+str(st.st_size),'fecha='+datetime.datetime.fromtimestamp(st.st_mtime,datetime.timezone.utc).isoformat(),'SHA256='+hashlib.sha256(f.read_bytes()).hexdigest())
counts={'00_maven_referencia.txt':337,'02_operaciones_atomicas.txt':56,'03_seguridad_oauth2.txt':81,'04_payment_service.txt':70,'05_kafka_recuperacion.txt':49,'06_customer_service.txt':44,'07_gestion_cuentas.txt':39,'08_bff_regresion.txt':78,'10_integracion_aislada.txt':348,'10_correccion_batch_mensual.txt':5,'core_clientes_it.txt':4,'bff_oauth_it.txt':7}
for name,count in counts.items():
    assert f'Tests run: {count} Failures: 0 Errors: 0 Skipped: 0' in texts[name], 'Reporte esperado ausente: '+name
assert 'BUILD SUCCESS' in texts['00_maven_referencia.txt'] and 'BUILD SUCCESS' in texts['09_empaquetado_configuracion.txt']
assert 'Listener verificado en broker propio:' in texts['05_kafka_recuperacion.txt']
assert 'HTTP=202' in texts['05_kafka_recuperacion.txt'] and 'COMPLETED' in texts['05_kafka_recuperacion.txt']
assert 'BATCH INIT COMPLETED' in texts['batch_inicial.txt']
assert 'Mensual 50 filas/seis columnas identicas; saldos operativos conservados.' in texts['10_correccion_batch_mensual.txt']
rows=list(csv.DictReader(texts['estados_cuenta_anuales.csv'].splitlines()))
assert rows and set(rows[0])=={'cuenta_id','total_movimientos','total_depositos','total_cargos','saldo_neto'}
assert sum(int(row['total_movimientos']) for row in rows)==289
for name in docs:
    digest=hashlib.sha256((p/name).read_bytes()).hexdigest(); original=saved[name]['sha256']
    if digest!=original: assert (p/name).stat().st_mtime>=started, 'Indice modificado antes de la ejecucion'
    print('Documento:',name,'SHA256 respaldo='+original,'SHA256 actual='+digest)
assert all(']('+name+')' in texts['indice_evidencias.md'] for name in names+extras), 'Revisar enlaces del indice a los archivos actuales'
print('Inventario comprobado: once TXT regenerados, seis anexos y un Markdown; 18 archivos sin subdirectorios.')
print('La fecha original del indice restituido es valida; no representa resultados nuevos.')
print('La huella del propio 10_integracion_aislada.txt se calcula en terminal despues de cerrar esta captura.')
PY
sha256sum "$EVID"/*
unset WEB_TOKEN MOBILE_TOKEN ATM_TOKEN PAYMENT_TOKEN
unset OAUTH2_WEB_SECRET OAUTH2_MOBILE_SECRET OAUTH2_ATM_SECRET OAUTH2_PAYMENT_SECRET POSTGRES_PASSWORD
```

**Resultado esperado:** Solo despues de ejecutar las comprobaciones: 337 reactor + 4 Core/Clientes + 7 BFF = 348 distintos; once TXT regenerados, seis anexos y un Markdown (18 archivos), sin subcarpetas, no vacios y sin JWT completos. TXT/anexos con fecha de esta ejecucion e indice restituido/revisado. SHA256 de los otros 17 archivos en la evidencia 10 y huellas finales de los 18 en terminal: la evidencia 10 no puede contener su propia huella final porque escribirla alteraria el archivo. Servicios normales healthy, dos batch-init terminados y consumidor optativo detenido/conservado.

**Evidencia:** 10_integracion_aislada.txt, exclusivamente dentro de `$EVID`.

**Criterio de avance:** Revisar las salidas reales y cualquier FAIL previo antes de declarar satisfactoria la reproduccion. No ejecutar down -v, prune ni eliminar contenedores, volumenes o evidencias.

Nota: La siguiente comprobación es para la entrega final. Ejecutar únicamente después de generar el archivo ZIP, reemplazando la ruta de ejemplo por su ubicación real.

```bash
python3 - '/home/francisco/proyecto/BACKEND III/EFT/EFT_Francisco_Henriquez.zip' <<'PY'
import pathlib,sys,zipfile
archive=pathlib.Path(sys.argv[1]); assert archive.is_file(), 'Indicar el ZIP real ya creado'
with zipfile.ZipFile(archive) as package:
    excluded=[name for name in package.namelist() if any(part.startswith('respaldo-evidencias-EFT-') for part in pathlib.PurePosixPath(name).parts)]
assert not excluded, 'Respaldo o manifiesto incluido en el ZIP: '+repr(excluded)
print('ZIP comprobado: sin carpetas de respaldo EFT ni sus manifiestos.')
PY
```

<a id="eft-matriz-evidencias"></a>

### Matriz Paso / Evidencia

La columna Evidencia enlaza el **archivo oficial existente**. En la ejecucion local, el Paso 00 respaldo las evidencias y vacio la carpeta; los bloques siguientes regeneraron esos mismos nombres directamente en `$EVID`, con resultados del codigo final y procedencia propia. Los TXT se componen de reportes de casos y comprobaciones manuales actuales; no son copias byte a byte de auditorias historicas, timestamps, imagenes o inventarios anteriores.

| Paso                                 | Evidencia                                                                           | Objetivo                                  | Comando / bloque                                                                | Resultado comprobable                                                          | Dependencia                  |
| ------------------------------------ | ----------------------------------------------------------------------------------- | ----------------------------------------- | ------------------------------------------------------------------------------- | ------------------------------------------------------------------------------ | ---------------------------- |
| [03](#eft-paso-03)                   | [00_maven_referencia.txt](evidencias/EFT/00_maven_referencia.txt)                   | Referencia de Maven                       | `./mvnw test; report_cases 337`                                                 | 337 actuales; original historico 92                                            | 00–02                        |
| [06, 21](#eft-paso-06)               | [02_operaciones_atomicas.txt](evidencias/EFT/02_operaciones_atomicas.txt)           | Atomicidad, concurrencia y efecto unico   | `report_cases 56; reenvio DEPOSIT_JSON y SELECT`                                | 56 seleccionados; saldo 70000 tras reenvio; tres operaciones antes de Kafka    | 03–05, 20                    |
| [06, 15–16](#eft-paso-06)            | [03_seguridad_oauth2.txt](evidencias/EFT/03_seguridad_oauth2.txt)                   | JWT, scopes y proteccion interna          | `report_cases 81; token y core_http`                                            | 81 seleccionados; 401/403/200; interno protegido                               | 03–05, 09–14                 |
| [06, 20](#eft-paso-06)               | [04_payment_service.txt](evidencias/EFT/04_payment_service.txt)                     | Coordinacion HTTP de dinero               | `report_cases 70; tres POST Web y GET resultado`                                | 70 seleccionados; COMPLETED y 70000/20000                                      | 03–05, 17–19                 |
| [06, 22–25, 28](#eft-paso-06)        | [05_kafka_recuperacion.txt](evidencias/EFT/05_kafka_recuperacion.txt)               | Retiro, offsets, reentrega y recuperacion | `report_cases 49; CLI Kafka; WithdrawalKafkaIntegrationTest`                    | 10 Kafka reales dentro de 49; reentrega sin otro cargo; resultado original     | 03–05, 20–21, 22 antes de 23 |
| [06, 11, 17](#eft-paso-06)           | [06_customer_service.txt](evidencias/EFT/06_customer_service.txt)                   | Esquema, cinco funciones y perfiles       | `report_cases 44; init always y APIs Web`                                       | 44 casos Clientes; ID real y PREFERENTE                                        | 03–05, 08–10, 15             |
| [06, 18–19](#eft-paso-06)            | [07_gestion_cuentas.txt](evidencias/EFT/07_gestion_cuentas.txt)                     | Cuenta asociada, mantenimiento y cierre   | `report_cases 39; tres aperturas y cierre EMPTY`                                | 39 seleccionados; tres cuentas nuevas y cierre a cero                          | 03–05, 17                    |
| [05–06, 20, 28–29](#eft-paso-05)     | [08_bff_regresion.txt](evidencias/EFT/08_bff_regresion.txt)                         | DTO, Bearer, circuitos y UNKNOWN          | `Dos IT del Paso 05; report_cases 78; 16 resiliencia y GET historicos`          | 7 IT actuales; 78 seleccionados BFF; historia real y errores preservados       | 03–04, 13–15, 20             |
| [07, 09, 12–14, 26–27](#eft-paso-07) | [09_empaquetado_configuracion.txt](evidencias/EFT/09_empaquetado_configuracion.txt) | Imagenes, Config/Eureka, salud y reparto  | `build; config filtrado; up --scale y 48 GET por servicio`                      | Dos replicas por bancario; suma 48 y trafico en ambas; historico 24/24         | 03–06, 08–13, 20–25          |
| [31–32](#eft-paso-31)                | [10_integracion_aislada.txt](evidencias/EFT/10_integracion_aislada.txt)             | Cierre del stack y casos distintos        | `SELECT integridad, grupo, Eureka; report_cases 348`                            | 348 distintos; 387/50/289; cuatro operaciones y retiro unico de esta secuencia | 00–30                        |
| [28, 30](#eft-paso-28)               | [10_correccion_batch_mensual.txt](evidencias/EFT/10_correccion_batch_mensual.txt)   | Ultimo valido y aislamiento operativo     | `MonthlyInterestDeterminismIntegrationTest; batch-init repetido con init never` | Dos mensuales 1000/263/737, 50 filas/seis columnas identicas, saldos intactos  | 03, 07–12, 20–27             |

Los comandos globales de los Pasos 03–05 son la procedencia Maven de las selecciones del Paso 06. El Paso 28 permite ejecutar las clases especificas de Kafka, resiliencia y Batch. Los casos siguen contandose por clase y nombre de metodo; un reenvio HTTP, una captura Docker o una segunda ejecucion de un test no es un caso nuevo.

Los anexos `preparacion.txt`, `base_nueva.txt`, `batch_inicial.txt`, `core_clientes_it.txt`, `bff_oauth_it.txt` y `estados_cuenta_anuales.csv` se generaron directamente en `$EVID` y forman parte del inventario final, junto con los once TXT y el indice restituido: 18 archivos. La carpeta contiene once TXT, seis anexos y un Markdown (18 archivos). Los contenedores creados con run se verifican mediante sus IDs y etiquetas y `docker ps --filter label=...`; `compose ps` se usa para los tres servicios normales y sus replicas.

## 17. Evidencias

[Indice de evidencias EFT](evidencias/EFT/indice_evidencias.md) y [matriz de reproduccion](#eft-matriz-evidencias). La ejecucion local respaldo y vacio completamente evidencias/EFT, conservando el directorio, y regenero alli los once TXT y seis anexos. Al cierre se restituyo exclusivamente indice_evidencias.md desde la copia verificada. El inventario final es de 18 archivos: once TXT, seis anexos y un indice Markdown. PROJECT no determina ninguna ruta de evidencias.

## 18. Resultados de pruebas

| Ejecucion local verificada                              | Casos satisfactorios | Referencia                                                                                  |
| -------------------------------------------------------- | -------------------: | ------------------------------------------------------------------------------------------- |
| Reactor ./mvnw test                                      |                  337 | 00_maven_referencia.txt                                                               |
| CoreCustomerOAuth2IT explicito                           |                    4 | core_clientes_it.txt                                                                          |
| BffApplicationsOAuth2IT y AccountsBffOAuth2IT explicitos |                    7 | bff_oauth_it.txt                                                                              |
| Total de casos distintos                                 |              **348** | Cero fallos, errores y omitidos; selecciones anteriores incluidas, sin sumar reejecuciones. |
