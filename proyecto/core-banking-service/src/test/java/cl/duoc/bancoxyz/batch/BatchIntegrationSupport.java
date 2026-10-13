package cl.duoc.bancoxyz.batch;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
abstract class BatchIntegrationSupport {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("banco_xyz_test")
            .withUsername("test_user")
            .withPassword("test_password");

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected JobLauncher jobLauncher;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeEach
    void cleanBusinessTables() {
        jdbcTemplate.update("DELETE FROM resumen_transacciones_diarias");
        jdbcTemplate.update("DELETE FROM transacciones_procesadas");
        jdbcTemplate.update("DELETE FROM intereses_mensuales");
        jdbcTemplate.update("DELETE FROM movimientos_anuales");
    }

}
