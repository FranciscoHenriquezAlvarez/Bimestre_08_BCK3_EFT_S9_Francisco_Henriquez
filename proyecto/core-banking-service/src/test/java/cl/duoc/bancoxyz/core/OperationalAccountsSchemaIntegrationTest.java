package cl.duoc.bancoxyz.core;

import static cl.duoc.bancoxyz.core.AccountOperationService.OperationType.DEBIT;
import static org.junit.jupiter.api.Assertions.assertEquals;

import cl.duoc.bancoxyz.core.AccountOperationService.OperationRequest;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class OperationalAccountsSchemaIntegrationTest extends OperationalAccountsIntegrationSupport {
    @Test
    void repeatedSchemaInitializationNeverCopiesOrResetsBalances() {
        jdbc.update("""
                INSERT INTO intereses_mensuales (cuenta_id, nombre, tipo, saldo_inicial, tasa, saldo_final)
                VALUES (1, 'Cuenta historica de prueba', 'ahorro', 100000, 0.01, 100000)
                """);
        account(1, "42.00");
        var result = operations.execute(new OperationRequest(UUID.randomUUID(), DEBIT, 1, null,
                new BigDecimal("2")));
        jdbc.update("UPDATE cuentas SET estado='CERRADA' WHERE cuenta_id=1");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(jdbc.getDataSource());
        assertEquals(new BigDecimal("40.00"), balance(1));
        assertEquals("CERRADA", jdbc.queryForObject("SELECT estado FROM cuentas WHERE cuenta_id=1", String.class));
        assertEquals(result, operations.operation(result.operationId()));
        assertEquals(new BigDecimal("100000.00"),
                jdbc.queryForObject("SELECT saldo_final FROM intereses_mensuales WHERE cuenta_id=1", BigDecimal.class));
        assertEquals(1, operationCount());
    }
}
