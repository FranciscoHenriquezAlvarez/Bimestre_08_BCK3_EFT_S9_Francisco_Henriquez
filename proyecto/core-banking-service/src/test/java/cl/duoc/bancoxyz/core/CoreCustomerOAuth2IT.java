package cl.duoc.bancoxyz.core;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// Se ejecuta despues del reactor para reutilizar las clases reales de Clientes sin acoplar sus modulos.
@Testcontainers
class CoreCustomerOAuth2IT {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private static final Map<String, String> SECRETS = Map.of("web", UUID.randomUUID().toString(),
            "mobile", UUID.randomUUID().toString(), "atm", UUID.randomUUID().toString(), "payment", UUID.randomUUID().toString());
    private static final Map<String, String> TOKENS = new HashMap<>();
    @Container
    static final PostgreSQLContainer<?> CORE_DB = database("eft_accounts_oauth");
    @Container
    static final PostgreSQLContainer<?> CUSTOMER_DB = database("eft_customers_oauth");
    private static AccountTestApplicationProcess authorization;
    private static AccountTestApplicationProcess customers;
    private static AccountTestApplicationProcess core;
    private static int authPort;
    private static int customerPort;
    private static int corePort;

    @BeforeAll
    static void startOnlyIsolatedApplications() throws Exception {
        authPort = AccountTestApplicationProcess.freePort();
        customerPort = AccountTestApplicationProcess.freePort();
        corePort = AccountTestApplicationProcess.freePort();
        Map<String, String> authEnvironment = new HashMap<>();
        authEnvironment.put("OAUTH2_ISSUER", "http://localhost:" + authPort);
        SECRETS.forEach((name, secret) -> authEnvironment.put("OAUTH2_" + name.toUpperCase(Locale.ROOT) + "_SECRET", secret));
        authorization = new AccountTestApplicationProcess("authorization-server", "cl.duoc.bancoxyz.authorization.AuthorizationServerApplication",
                AccountTestApplicationProcess.moduleClasspath("authorization-server", "cl.duoc.bancoxyz.authorization.AuthorizationServerTest"),
                authPort, authEnvironment, List.of(), List.copyOf(SECRETS.values()));
        authorization.awaitHealth(authPort);
        startCustomers();
        startCore();
        for (String client : SECRETS.keySet()) TOKENS.put(client, token(client));
    }

    @AfterAll
    static void stopOnlyTestApplications() throws Exception {
        try {
            if (core != null) core.close();
        } finally {
            try {
                if (customers != null) customers.close();
            } finally {
                if (authorization != null) authorization.close();
            }
        }
    }

    @Test
    void realWebAndMobileTokensOpenMaintainAndCloseUsingActualCustomers() throws Exception {
        for (String channel : List.of("web", "mobile")) {
            int customerId = registerCustomer(TOKENS.get(channel));
            JsonNode opened = openAccount(customerId, TOKENS.get(channel));
            int id = opened.path("accountId").asInt();
            assertThat(opened.path("customerId").asInt()).isEqualTo(customerId);
            assertThat(opened.path("state").asText()).isEqualTo("ACTIVA");
            assertThat(opened.path("initialBalance").decimalValue()).isEqualByComparingTo("0");
            UUID creditId = UUID.randomUUID();
            assertThat(operation("credit", creditId, id, 10).path("status").asText()).isEqualTo("COMPLETED");
            var maintained = call(corePort, "PUT", "/api/core/accounts/" + id,
                    Map.of("accountName", "Cuenta actualizada", "accountType", "ahorro", "interestRate", 0.02), TOKENS.get(channel));
            assertThat(maintained.statusCode()).isEqualTo(200);
            JsonNode updated = JSON.readTree(maintained.body());
            assertThat(updated.path("finalBalance").decimalValue()).isEqualByComparingTo("10");
            assertThat(updated.path("createdAt")).isEqualTo(opened.path("createdAt"));
            assertThat(call(corePort, "POST", "/api/core/accounts/" + id + "/close", null, TOKENS.get(channel)).statusCode()).isEqualTo(409);
            assertThat(operation("debit", UUID.randomUUID(), id, 10).path("status").asText()).isEqualTo("COMPLETED");
            var closed = call(corePort, "POST", "/api/core/accounts/" + id + "/close", null, TOKENS.get(channel));
            assertThat(closed.statusCode()).isEqualTo(200);
            assertThat(JSON.readTree(closed.body()).path("state").asText()).isEqualTo("CERRADA");
            assertThat(operation("credit", UUID.randomUUID(), id, 1).path("status").asText()).isEqualTo("REJECTED");
            assertThat(operation("credit", creditId, id, 10).path("status").asText()).isEqualTo("COMPLETED");
            var balance = call(corePort, "GET", "/api/core/accounts/" + id + "/balance", null, TOKENS.get("atm"));
            assertThat(balance.statusCode()).isEqualTo(200);
            assertThat(JSON.readTree(balance.body()).path("balance").decimalValue()).isEqualByComparingTo("0");
            assertThat(call(customerPort, "GET", "/api/customers/" + customerId, null, TOKENS.get(channel)).statusCode()).isEqualTo(200);
        }
        System.out.println("EFT07 OAuth2 real: Core valida Clientes por HTTP y LoadBalancer con JWT Web/Mobile; apertura 201, mantenimiento/cierre 200, cierre con saldo 409, contabilidad idempotente y ATM 200");
    }

    @Test
    void realChannelPermissionsInvalidSignaturesAndAbsentCustomersPreventWrites() throws Exception {
        int customerId = registerCustomer(TOKENS.get("web"));
        JsonNode opened = openAccount(customerId, TOKENS.get("web"));
        int id = opened.path("accountId").asInt();
        int before = accountCount();
        for (String channel : List.of("atm", "payment")) {
            assertAdministrativeDenied(customerId, id, TOKENS.get(channel), 403);
        }
        String web = TOKENS.get("web");
        int signature = web.lastIndexOf('.') + 1;
        String tampered = web.substring(0, signature) + (web.charAt(signature) == 'A' ? 'B' : 'A') + web.substring(signature + 1);
        for (String invalid : List.of("", "invalid-test-token", tampered)) {
            assertAdministrativeDenied(customerId, id, invalid, 401);
        }
        assertThat(call(corePort, "POST", "/api/core/accounts", opening(Integer.MAX_VALUE), TOKENS.get("web")).statusCode()).isEqualTo(404);
        assertThat(call(corePort, "POST", "/api/core/accounts", opening(0), TOKENS.get("mobile")).statusCode()).isEqualTo(400);
        assertThat(accountCount()).isEqualTo(before);
        assertThat(call(corePort, "GET", "/api/core/accounts/" + (id + 1), null, TOKENS.get("web")).statusCode()).isEqualTo(404);
        assertThat(JSON.readTree(call(corePort, "GET", "/api/core/accounts/" + id, null, TOKENS.get("web")).body()).path("finalBalance").decimalValue()).isEqualByComparingTo("0");
        System.out.println("EFT07 OAuth2 real: ATM/Pagos 403; ausencia, formato y firma invalida 401; cliente inexistente 404 e identificador invalido 400; sin apertura parcial");
    }

    @Test
    void applicationRestartPreservesAccountsBalancesLedgerAndSequence() throws Exception {
        int customerId = registerCustomer(TOKENS.get("mobile"));
        JsonNode opened = openAccount(customerId, TOKENS.get("mobile"));
        int id = opened.path("accountId").asInt();
        UUID credit = UUID.randomUUID();
        JsonNode original = operation("credit", credit, id, 15);
        core.close();
        core = null;
        startCore();
        var query = call(corePort, "GET", "/api/core/accounts/" + id, null, TOKENS.get("mobile"));
        assertThat(query.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(query.body()).path("finalBalance").decimalValue()).isEqualByComparingTo("15");
        assertThat(operation("credit", credit, id, 15)).isEqualTo(original);
        assertThat(openAccount(customerId, TOKENS.get("web")).path("accountId").asInt()).isGreaterThan(id);
        var maintenance = call(corePort, "PUT", "/api/core/accounts/" + id,
                Map.of("accountName", "Cuenta tras reinicio", "accountType", "ahorro", "interestRate", 0.01), TOKENS.get("web"));
        assertThat(maintenance.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(maintenance.body()).path("customerId").asInt()).isEqualTo(customerId);
        assertThat(JSON.readTree(maintenance.body()).path("createdAt")).isEqualTo(opened.path("createdAt"));
        System.out.println("EFT07 reinicio real de Core: preserva saldo, cliente, fecha de creacion, resultado monetario y secuencia en PostgreSQL aislado");
    }

    @Test
    void unavailableCustomerApplicationRejectsOpeningAndRecoversWithoutPartialData() throws Exception {
        int customerId = registerCustomer(TOKENS.get("web"));
        int before = accountCount();
        customers.close();
        customers = null;
        try {
            assertThat(call(corePort, "POST", "/api/core/accounts", opening(customerId), TOKENS.get("web")).statusCode()).isEqualTo(503);
            assertThat(accountCount()).isEqualTo(before);
        } finally {
            startCustomers();
        }
        assertThat(openAccount(customerId, TOKENS.get("mobile")).path("customerId").asInt()).isEqualTo(customerId);
        assertThat(accountCount()).isEqualTo(before + 1);
        System.out.println("EFT07 Clientes real detenido: apertura 503 sin escrituras; reinicio conserva cliente y permite apertura 201");
    }

    private static void startCustomers() throws Exception {
        customers = new AccountTestApplicationProcess("customer-service", "cl.duoc.bancoxyz.customer.CustomerApplication",
                AccountTestApplicationProcess.moduleClasspath("customer-service", "cl.duoc.bancoxyz.customer.CustomerServiceTest"),
                customerPort, databaseEnvironment(CUSTOMER_DB), List.of("--spring.sql.init.mode=always"), List.of(CUSTOMER_DB.getPassword()));
        customers.awaitHealth(customerPort);
    }

    private static int accountCount() throws Exception {
        try (var connection = DriverManager.getConnection(CORE_DB.getJdbcUrl(), CORE_DB.getUsername(), CORE_DB.getPassword());
                var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT COUNT(*) FROM cuentas")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private static void startCore() throws Exception {
        core = new AccountTestApplicationProcess("core-banking-service", "cl.duoc.bancoxyz.BankBatchApplication",
                AccountTestApplicationProcess.productionClasses(System.getProperty("java.class.path")), corePort,
                databaseEnvironment(CORE_DB), List.of("--spring.sql.init.mode=always", "--spring.batch.job.enabled=false",
                        "--spring.kafka.admin.auto-create=false", "--spring.kafka.listener.auto-startup=false", "--bank.config-origin=Prueba aislada",
                        "--spring.cloud.discovery.client.simple.instances.customer-service[0].uri=http://localhost:" + customerPort),
                List.of(CORE_DB.getPassword()));
        core.awaitHealth(corePort);
    }

    private static Map<String, String> databaseEnvironment(PostgreSQLContainer<?> database) {
        return Map.of("SPRING_DATASOURCE_URL", database.getJdbcUrl(), "SPRING_DATASOURCE_USERNAME", database.getUsername(),
                "SPRING_DATASOURCE_PASSWORD", database.getPassword(), "OAUTH2_ISSUER", "http://localhost:" + authPort,
                "OAUTH2_JWK_SET_URI", "http://localhost:" + authPort + "/oauth2/jwks");
    }

    private static PostgreSQLContainer<?> database(String name) {
        return new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName(name).withUsername("eft_test").withPassword(UUID.randomUUID().toString());
    }

    private static String token(String client) throws Exception {
        String basic = Base64.getEncoder().encodeToString((client + "-client:" + SECRETS.get(client)).getBytes(StandardCharsets.UTF_8));
        String scope = client.equals("payment") ? "account.operations" : client;
        var response = HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + authPort + "/oauth2/token"))
                .timeout(Duration.ofSeconds(10)).header("Authorization", "Basic " + basic)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials&scope=" + scope)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return JSON.readTree(response.body()).path("access_token").asText();
    }

    private static int registerCustomer(String bearer) throws Exception {
        var response = call(customerPort, "POST", "/api/customers", Map.of("firstName", "Nombre", "lastName", "Prueba",
                "email", "cliente-" + UUID.randomUUID() + "@example.test", "profile", "ESTANDAR"), bearer);
        assertThat(response.statusCode()).isEqualTo(201);
        return JSON.readTree(response.body()).path("customerId").asInt();
    }

    private static Map<String, Object> opening(int customerId) {
        return Map.of("customerId", customerId, "accountName", "Cuenta de prueba", "accountType", "ahorro", "interestRate", 0.01);
    }

    private static JsonNode openAccount(int customerId, String token) throws Exception {
        var response = call(corePort, "POST", "/api/core/accounts", opening(customerId), token);
        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode opened = JSON.readTree(response.body());
        assertThat(response.headers().firstValue("Location").orElseThrow()).isEqualTo("/api/core/accounts/" + opened.path("accountId").asInt());
        return opened;
    }

    private static JsonNode operation(String action, UUID operationId, int accountId, int amount) throws Exception {
        var response = call(corePort, "POST", "/api/core/internal/operations/" + action,
                Map.of("operationId", operationId.toString(), "accountId", accountId, "amount", amount), TOKENS.get("payment"));
        assertThat(response.statusCode()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private static void assertAdministrativeDenied(int customerId, int id, String token, int expected) throws Exception {
        assertThat(call(corePort, "POST", "/api/core/accounts", opening(customerId), token).statusCode()).isEqualTo(expected);
        assertThat(call(corePort, "PUT", "/api/core/accounts/" + id,
                Map.of("accountName", "Intento rechazado", "accountType", "ahorro", "interestRate", 0.01), token).statusCode()).isEqualTo(expected);
        assertThat(call(corePort, "POST", "/api/core/accounts/" + id + "/close", null, token).statusCode()).isEqualTo(expected);
    }

    private static HttpResponse<String> call(int port, String method, String path, Object body, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(10));
        if (!token.isEmpty()) builder.header("Authorization", "Bearer " + token);
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
