package cl.duoc.bancoxyz.core;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
abstract class OperationalAccountsIntegrationSupport {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("eft_cuentas_test")
            .withUsername("eft_test")
            .withPassword("eft_test_password");

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected AccountOperationService operations;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeEach
    void cleanIsolatedDatabase() {
        // Esta conexion pertenece exclusivamente al contenedor creado para la prueba.
        for (String table : new String[] {"operaciones_cuenta", "cuentas", "banking_events",
                "processed_events", "withdrawal_transactions", "resumen_transacciones_diarias",
                "transacciones_procesadas", "intereses_mensuales", "movimientos_anuales"}) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    protected Connection isolatedConnection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    protected void account(int id, String balance) {
        jdbc.update("""
                INSERT INTO cuentas (cuenta_id, nombre, tipo, saldo_inicial, tasa, saldo_operativo)
                VALUES (?, 'Cuenta de prueba', 'ahorro', ?, 0.01, ?)
                """, id, new BigDecimal(balance), new BigDecimal(balance));
    }

    protected BigDecimal balance(int id) {
        return jdbc.queryForObject("SELECT saldo_operativo FROM cuentas WHERE cuenta_id=?", BigDecimal.class, id);
    }

    protected int operationCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM operaciones_cuenta", Integer.class);
    }
}
