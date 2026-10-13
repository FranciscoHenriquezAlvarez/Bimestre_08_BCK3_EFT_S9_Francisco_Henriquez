package cl.duoc.bancoxyz.core;

import static cl.duoc.bancoxyz.core.AccountOperationService.OperationType.CREDIT;
import static cl.duoc.bancoxyz.core.AccountOperationService.OperationType.DEBIT;
import static org.junit.jupiter.api.Assertions.*;

import cl.duoc.bancoxyz.core.AccountOperationService.OperationRequest;
import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class OperationalBatchIsolationIntegrationTest extends OperationalAccountsIntegrationSupport {
    @Test
    void allThreeJobsAndApplicationRestartsPreserveOperationalData() throws Exception {
        // Los identificadores coinciden con el CSV mensual para verificar la separacion de saldos.
        account(101, "100.00");
        account(102, "200.00");
        jdbc.update("UPDATE cuentas SET estado='CERRADA' WHERE cuenta_id=102");
        UUID id = UUID.randomUUID();
        var request = new OperationRequest(id, DEBIT, 101, null, new BigDecimal("10"));
        var original = operations.execute(request);
        operations.execute(new OperationRequest(UUID.randomUUID(), CREDIT, null, 101, new BigDecimal("50")));
        var accountsBefore = jdbc.queryForList("SELECT * FROM cuentas ORDER BY cuenta_id");
        var operationsBefore = jdbc.queryForList("SELECT * FROM operaciones_cuenta ORDER BY operacion_id");
        Path directory = Files.createDirectories(Path.of("target", "eft-batch-isolation", UUID.randomUUID().toString())
                .toAbsolutePath());

        runJobsInIsolatedProcess(directory, id, 1);
        assertEquals(387, jdbc.queryForObject("SELECT COUNT(*) FROM transacciones_procesadas", Integer.class));
        assertEquals(50, jdbc.queryForObject("SELECT COUNT(*) FROM intereses_mensuales", Integer.class));
        assertEquals(289, jdbc.queryForObject("SELECT COUNT(*) FROM movimientos_anuales", Integer.class));
        List<String> reportBefore = Files.readAllLines(directory.resolve("output/estados_cuenta_anuales.csv"));
        assertEquals("cuenta_id,total_movimientos,total_depositos,total_cargos,saldo_neto", reportBefore.get(0));
        assertTrue(reportBefore.size() > 1);

        // Cambia solo el resultado aislado para comprobar que el Job lo recalcula al reejecutarse.
        jdbc.update("UPDATE intereses_mensuales SET saldo_final=-1 WHERE cuenta_id=101");

        runJobsInIsolatedProcess(directory, id, 2);

        assertEquals(accountsBefore, jdbc.queryForList("SELECT * FROM cuentas ORDER BY cuenta_id"));
        assertEquals(operationsBefore, jdbc.queryForList("SELECT * FROM operaciones_cuenta ORDER BY operacion_id"));
        assertEquals(original, operations.execute(request));
        assertEquals(new BigDecimal("140.00"), balance(101));
        assertEquals(387, jdbc.queryForObject("SELECT COUNT(*) FROM transacciones_procesadas", Integer.class));
        assertEquals(289, jdbc.queryForObject("SELECT COUNT(*) FROM movimientos_anuales", Integer.class));
        assertEquals(50, jdbc.queryForObject("SELECT COUNT(*) FROM intereses_mensuales", Integer.class));
        assertTrue(jdbc.queryForObject("SELECT saldo_final FROM intereses_mensuales WHERE cuenta_id=101",
                BigDecimal.class).signum() > 0);
        assertEquals(reportBefore, Files.readAllLines(directory.resolve("output/estados_cuenta_anuales.csv")));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM resumen_transacciones_diarias", Integer.class));
    }

    private void runJobsInIsolatedProcess(Path directory, UUID operationId, int run) throws Exception {
        String classpath = Arrays.stream(System.getProperty("surefire.test.class.path").split(File.pathSeparator))
                .map(entry -> Path.of(entry).toAbsolutePath().toString())
                .reduce((left, right) -> left + File.pathSeparator + right).orElseThrow();
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath, BatchIsolationRunner.class.getName(),
                "--spring.profiles.active=test", "--spring.cloud.config.enabled=false",
                "--eureka.client.enabled=false", "--server.port=0", "--logging.level.root=WARN"));
        Path log = directory.resolve("ejecucion-" + run + ".log");
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("SPRING_DATASOURCE_URL", POSTGRES.getJdbcUrl());
        builder.environment().put("SPRING_DATASOURCE_USERNAME", POSTGRES.getUsername());
        builder.environment().put("SPRING_DATASOURCE_PASSWORD", POSTGRES.getPassword());
        builder.environment().put("EFT_TEST_OPERATION_ID", operationId.toString());
        Process process = builder.start();
        try {
            assertTrue(process.waitFor(90, TimeUnit.SECONDS), "El proceso aislado no termino: " + log);
            assertEquals(0, process.exitValue(), () -> "Fallo del proceso aislado: " + readLog(log));
            String output = Files.readString(log);
            for (String step : List.of("dailyTransactionsStep", "monthlyInterestStep", "annualMovementsStep",
                    "annualStatementReportStep")) {
                assertTrue(output.contains(" " + step + " COMPLETED"), "Falta el Step: " + step);
            }
            assertTrue(output.contains("EFT_RESTART resultado original conservado"));
            output.lines().filter(line -> line.startsWith("EFT_")).forEach(System.out::println);
            System.out.println("EFT_CSV informe anual generado en directorio aislado; ejecucion=" + run);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private String readLog(Path log) {
        try {
            return Files.readString(log);
        } catch (Exception error) {
            return error.getMessage();
        }
    }
}
