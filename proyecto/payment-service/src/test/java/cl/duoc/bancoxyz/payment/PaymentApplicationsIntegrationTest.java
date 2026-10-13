package cl.duoc.bancoxyz.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class PaymentApplicationsIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private static final Map<String, String> SECRETS = Map.of("web", UUID.randomUUID().toString(),
            "mobile", UUID.randomUUID().toString(), "atm", UUID.randomUUID().toString(),
            "payment", UUID.randomUUID().toString());
    private static final Map<String, String> TOKENS = new HashMap<>();
    private static final AtomicReference<ResponseDelay> DELAY = new AtomicReference<>();
    private static final AtomicInteger FORCED_STATUS = new AtomicInteger();
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("eft_payment_http_test").withUsername("eft_test")
            .withPassword(UUID.randomUUID().toString());
    private static TestApplicationProcess authorization;
    private static TestApplicationProcess core;
    private static TestApplicationProcess payment;
    private static HttpServer proxy;
    private static ExecutorService proxyThreads;
    private static int authPort;
    private static int corePort;
    private static int paymentPort;

    @BeforeAll
    static void startRealApplications() throws Exception {
        authPort = TestApplicationProcess.freePort();
        Map<String, String> environment = new HashMap<>();
        environment.put("OAUTH2_ISSUER", "http://localhost:" + authPort);
        environment.put("OAUTH2_JWK_SET_URI", "http://localhost:" + authPort + "/oauth2/jwks");
        environment.put("OAUTH2_TOKEN_URI", "http://localhost:" + authPort + "/oauth2/token");
        SECRETS.forEach((client, secret) -> environment.put("OAUTH2_" + client.toUpperCase() + "_SECRET", secret));
        List<String> secrets = new ArrayList<>(SECRETS.values());
        secrets.add(POSTGRES.getPassword());
        authorization = new TestApplicationProcess("authorization-server",
                "cl.duoc.bancoxyz.authorization.AuthorizationServerApplication",
                TestApplicationProcess.moduleClasspath("authorization-server",
                        "cl.duoc.bancoxyz.authorization.AuthorizationServerTest"),
                authPort, environment, List.of(), secrets);
        authorization.awaitHealth(authPort);

        corePort = TestApplicationProcess.freePort();
        Map<String, String> coreEnvironment = new HashMap<>(environment);
        coreEnvironment.put("SPRING_DATASOURCE_URL", POSTGRES.getJdbcUrl());
        coreEnvironment.put("SPRING_DATASOURCE_USERNAME", POSTGRES.getUsername());
        coreEnvironment.put("SPRING_DATASOURCE_PASSWORD", POSTGRES.getPassword());
        core = new TestApplicationProcess("core-banking-service", "cl.duoc.bancoxyz.BankBatchApplication",
                TestApplicationProcess.moduleClasspath("core-banking-service",
                        "cl.duoc.bancoxyz.core.AccountOperationServiceIntegrationTest"),
                corePort, coreEnvironment, List.of("--spring.kafka.listener.auto-startup=false",
                        "--spring.kafka.admin.auto-create=false", "--bank.config-origin=Prueba HTTP aislada"), secrets);
        core.awaitHealth(corePort);

        // Este intermediario de prueba permite perder una respuesta despues de un commit real.
        proxy = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        proxyThreads = Executors.newCachedThreadPool();
        proxy.setExecutor(proxyThreads);
        proxy.createContext("/", PaymentApplicationsIntegrationTest::forwardToRealCore);
        proxy.start();
        paymentPort = TestApplicationProcess.freePort();
        payment = new TestApplicationProcess("payment-service", "cl.duoc.bancoxyz.payment.PaymentApplication",
                TestApplicationProcess.productionClasses(System.getProperty("java.class.path")),
                paymentPort, environment, List.of("--payment.http.read-timeout-ms=2000",
                        "--spring.cloud.discovery.client.simple.instances.core-banking-service[0].uri=http://localhost:"
                                + proxy.getAddress().getPort()), secrets);
        payment.awaitHealth(paymentPort);
        for (String channel : SECRETS.keySet()) {
            TOKENS.put(channel, token(channel, "payment".equals(channel) ? "account.operations" : channel));
        }
        // La consulta inicial comprueba discovery, OAuth2 Client y ambos validadores JWT reales.
        assertThat(call(paymentPort, "GET", "/api/payments/operations/" + UUID.randomUUID(), null, TOKENS.get("web"))
                .statusCode()).isEqualTo(404);
    }

    @AfterAll
    static void stopOnlyTestApplications() throws Exception {
        try {
            if (payment != null) payment.close();
        } finally {
            try {
                if (proxy != null) proxy.stop(0);
                if (proxyThreads != null) proxyThreads.shutdownNow();
                if (core != null) core.close();
            } finally {
                if (authorization != null) authorization.close();
            }
        }
    }

    @BeforeEach
    void prepareOnlyIsolatedAccounts() throws Exception {
        FORCED_STATUS.set(0);
        DELAY.set(null);
        sql("DELETE FROM operaciones_cuenta");
        sql("DELETE FROM cuentas");
        sql("""
                INSERT INTO cuentas (cuenta_id, nombre, tipo, saldo_inicial, tasa, saldo_operativo)
                VALUES (101, 'Origen de prueba', 'ahorro', 100, 0.01, 100),
                       (102, 'Destino de prueba', 'ahorro', 20, 0.01, 20)
                """);
    }

    @Test
    void paymentsDepositsAndTransfersUseRealHttpOauthAndPostgres() throws Exception {
        JsonNode debit = completed("/api/payments", accountBody(UUID.randomUUID(), 101, "10"), "web");
        assertThat(debit.path("type").asText()).isEqualTo("DEBIT");
        JsonNode credit = completed("/api/payments/deposits", accountBody(UUID.randomUUID(), 102, "5"), "mobile");
        assertThat(credit.path("type").asText()).isEqualTo("CREDIT");
        JsonNode transfer = completed("/api/payments/transfers", transferBody(UUID.randomUUID(), "30"), "web");
        assertThat(transfer.path("type").asText()).isEqualTo("TRANSFER");
        assertThat(balance(101)).isEqualByComparingTo("60");
        assertThat(balance(102)).isEqualByComparingTo("55");
        assertThat(operationCount()).isEqualTo(3);
    }

    @Test
    void duplicateKeepsOriginalResultAfterLaterDepositAndConflictingDataCannotCharge() throws Exception {
        UUID id = UUID.randomUUID();
        String body = accountBody(id, 101, "10");
        JsonNode original = completed("/api/payments", body, "web");
        completed("/api/payments/deposits", accountBody(UUID.randomUUID(), 101, "50"), "mobile");
        assertThat(completed("/api/payments", body, "mobile")).isEqualTo(original);
        var query = call(paymentPort, "GET", "/api/payments/operations/" + id, null, TOKENS.get("web"));
        assertThat(query.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(query.body())).isEqualTo(original);
        for (String conflicting : new String[] {accountBody(id, 101, "11"), accountBody(id, 102, "10")}) {
            var response = call(paymentPort, "POST", "/api/payments", conflicting, TOKENS.get("web"));
            assertThat(response.statusCode()).isEqualTo(409);
            assertThat(JSON.readTree(response.body()).path("status").asText()).isEqualTo("CONFLICT");
        }
        assertThat(call(paymentPort, "POST", "/api/payments/deposits", body, TOKENS.get("mobile")).statusCode())
                .isEqualTo(409);
        assertThat(balance(101)).isEqualByComparingTo("140");
        assertThat(balance(102)).isEqualByComparingTo("20");
        assertThat(operationCount()).isEqualTo(2);
    }

    @Test
    void accountAndAmountErrorsAreDecidedAndPersistedByCore() throws Exception {
        for (String body : new String[] {accountBody(UUID.randomUUID(), 999, "10"),
                accountBody(UUID.randomUUID(), 101, "101"), accountBody(UUID.randomUUID(), 101, "0"),
                accountBody(UUID.randomUUID(), 101, "-1")}) {
            var response = call(paymentPort, "POST", "/api/payments", body, TOKENS.get("web"));
            assertThat(response.statusCode()).isEqualTo(200);
            JsonNode result = JSON.readTree(response.body());
            assertThat(result.path("status").asText()).isEqualTo("REJECTED");
            assertThat(result.path("rejectionReason").asText()).isNotBlank();
        }
        sql("UPDATE cuentas SET estado='CERRADA' WHERE cuenta_id=102");
        var closed = call(paymentPort, "POST", "/api/payments/deposits", accountBody(UUID.randomUUID(), 102, "10"),
                TOKENS.get("mobile"));
        assertThat(JSON.readTree(closed.body()).path("status").asText()).isEqualTo("REJECTED");
        assertThat(balance(101)).isEqualByComparingTo("100");
        assertThat(balance(102)).isEqualByComparingTo("20");
        assertThat(operationCount()).isEqualTo(5);
    }

    @Test
    void invalidPrecisionAndIncompleteRequestsWriteNothing() throws Exception {
        for (String body : new String[] {"{}", accountBody(UUID.randomUUID(), 101, "1.001"),
                accountBody(UUID.randomUUID(), 101, "1000000000000")}) {
            assertThat(call(paymentPort, "POST", "/api/payments", body, TOKENS.get("web")).statusCode())
                    .isEqualTo(400);
        }
        assertThat(operationCount()).isZero();
        assertThat(balance(101)).isEqualByComparingTo("100");
    }

    @Test
    void lostResponseAfterCommitIsUnknownAndSameIdentifierRecoversWithoutChargingAgain() throws Exception {
        UUID id = UUID.randomUUID();
        String body = accountBody(id, 101, "10");
        ResponseDelay delay = new ResponseDelay(new CountDownLatch(1), new CountDownLatch(1));
        DELAY.set(delay);
        try {
            var response = call(paymentPort, "POST", "/api/payments", body, TOKENS.get("web"));
            assertThat(response.statusCode()).isEqualTo(503);
            JsonNode unknown = JSON.readTree(response.body());
            assertThat(unknown.path("status").asText()).isEqualTo("UNKNOWN");
            assertThat(unknown.path("operationId").asText()).isEqualTo(id.toString());
            assertThat(delay.committed().await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(balance(101)).isEqualByComparingTo("90");
            assertThat(operationCount()).isEqualTo(1);
            var query = call(paymentPort, "GET", "/api/payments/operations/" + id, null, TOKENS.get("mobile"));
            assertThat(query.statusCode()).isEqualTo(200);
            JsonNode persisted = JSON.readTree(query.body());
            assertThat(persisted.path("status").asText()).isEqualTo("COMPLETED");
            assertThat(completed("/api/payments", body, "web")).isEqualTo(persisted);
            assertThat(balance(101)).isEqualByComparingTo("90");
            assertThat(operationCount()).isEqualTo(1);
        } finally {
            delay.release().countDown();
        }
    }

    @Test
    void sqlFailureRollsBackBothAccountsAndSameIdentifierCanBeRetried() throws Exception {
        sql("""
                CREATE FUNCTION step04_fail_target() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.cuenta_id=102 THEN RAISE EXCEPTION 'Fallo aislado de prueba'; END IF;
                    RETURN NEW;
                END $$
                """);
        sql("CREATE TRIGGER step04_fail BEFORE UPDATE ON cuentas FOR EACH ROW EXECUTE FUNCTION step04_fail_target()");
        UUID id = UUID.randomUUID();
        String body = transferBody(id, "30");
        try {
            var response = call(paymentPort, "POST", "/api/payments/transfers", body, TOKENS.get("mobile"));
            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(JSON.readTree(response.body()).path("status").asText()).isEqualTo("UNKNOWN");
            assertThat(balance(101)).isEqualByComparingTo("100");
            assertThat(balance(102)).isEqualByComparingTo("20");
            assertThat(operationCount()).isZero();
            assertThat(call(paymentPort, "GET", "/api/payments/operations/" + id, null, TOKENS.get("web"))
                    .statusCode()).isEqualTo(404);
        } finally {
            sql("DROP TRIGGER step04_fail ON cuentas");
            sql("DROP FUNCTION step04_fail_target()");
        }
        completed("/api/payments/transfers", body, "mobile");
        assertThat(balance(101)).isEqualByComparingTo("70");
        assertThat(balance(102)).isEqualByComparingTo("50");
        assertThat(operationCount()).isEqualTo(1);
    }

    @Test
    void realSignedTokensEnforceTheInternalAndPublicScopeMatrix() throws Exception {
        String[] actions = {"debit", "credit", "transfer"};
        UUID id = UUID.randomUUID();
        for (String channel : new String[] {"web", "mobile", "atm"}) {
            for (String action : actions) {
                assertThat(call(corePort, "POST", "/api/core/internal/operations/" + action,
                        accountBody(id, 101, "10"), TOKENS.get(channel)).statusCode()).isEqualTo(403);
            }
            assertThat(call(corePort, "GET", "/api/core/internal/operations/" + id, null, TOKENS.get(channel))
                    .statusCode()).isEqualTo(403);
        }
        for (String token : new String[] {"", "invalid-token"}) {
            for (String action : actions) {
                assertThat(call(corePort, "POST", "/api/core/internal/operations/" + action,
                        accountBody(id, 101, "10"), token).statusCode()).isEqualTo(401);
            }
            assertThat(call(corePort, "GET", "/api/core/internal/operations/" + id, null, token).statusCode())
                    .isEqualTo(401);
        }
        JsonNode debit = JSON.readTree(call(corePort, "POST", "/api/core/internal/operations/debit",
                accountBody(id, 101, "10"), TOKENS.get("payment")).body());
        assertThat(debit.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(call(corePort, "GET", "/api/core/internal/operations/" + id, null, TOKENS.get("payment"))
                .statusCode()).isEqualTo(200);

        for (String channel : new String[] {"web", "mobile"}) {
            completed("/api/payments", accountBody(UUID.randomUUID(), 101, "1"), channel);
            completed("/api/payments/deposits", accountBody(UUID.randomUUID(), 102, "1"), channel);
            completed("/api/payments/transfers", transferBody(UUID.randomUUID(), "1"), channel);
            assertThat(call(paymentPort, "GET", "/api/payments/operations/" + id, null, TOKENS.get(channel))
                    .statusCode()).isEqualTo(200);
        }
        for (String denied : new String[] {TOKENS.get("atm"), TOKENS.get("payment"), "", "invalid-token"}) {
            int expected = denied.isEmpty() || denied.equals("invalid-token") ? 401 : 403;
            for (String path : new String[] {"/api/payments", "/api/payments/deposits", "/api/payments/transfers"}) {
                assertThat(call(paymentPort, "POST", path, accountBody(UUID.randomUUID(), 101, "10"), denied)
                        .statusCode()).isEqualTo(expected);
            }
            assertThat(call(paymentPort, "GET", "/api/payments/operations/" + id, null, denied).statusCode())
                    .isEqualTo(expected);
        }
    }

    @Test
    void internalHttpAuthenticationFailuresAreUnknownAndNeverAlterAccounts() throws Exception {
        for (int status : new int[] {401, 403}) {
            FORCED_STATUS.set(status);
            var response = call(paymentPort, "POST", "/api/payments", accountBody(UUID.randomUUID(), 101, "10"),
                    TOKENS.get("web"));
            assertThat(response.statusCode()).isEqualTo(502);
            assertThat(JSON.readTree(response.body()).path("status").asText()).isEqualTo("UNKNOWN");
        }
        FORCED_STATUS.set(0);
        assertThat(balance(101)).isEqualByComparingTo("100");
        assertThat(operationCount()).isZero();
    }

    private static String token(String client, String scope) throws Exception {
        String credential = java.util.Base64.getEncoder().encodeToString(
                (client + "-client:" + SECRETS.get(client)).getBytes(StandardCharsets.UTF_8));
        var response = HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + authPort + "/oauth2/token"))
                .timeout(Duration.ofSeconds(10)).header("Authorization", "Basic " + credential)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials&scope=" + scope)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.path("scope").asText()).isEqualTo(scope);
        return body.path("access_token").asText();
    }

    private static void forwardToRealCore(HttpExchange exchange) throws java.io.IOException {
        try {
            int forced = FORCED_STATUS.get();
            if (forced != 0) {
                exchange.sendResponseHeaders(forced, -1);
                return;
            }
            byte[] body = exchange.getRequestBody().readAllBytes();
            var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + corePort + exchange.getRequestURI()))
                    .timeout(Duration.ofSeconds(10));
            for (String header : new String[] {"Authorization", "Content-Type"}) {
                String value = exchange.getRequestHeaders().getFirst(header);
                if (value != null) builder.header(header, value);
            }
            builder.method(exchange.getRequestMethod(), HttpRequest.BodyPublishers.ofByteArray(body));
            var response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            ResponseDelay delay = "POST".equals(exchange.getRequestMethod()) ? DELAY.getAndSet(null) : null;
            if (delay != null && response.statusCode() == 200) {
                delay.committed().countDown();
                delay.release().await(8, TimeUnit.SECONDS);
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(response.statusCode(), response.body().length);
            exchange.getResponseBody().write(response.body());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (java.io.IOException ignored) {
            // El cliente abandona la conexion al vencer su timeout en la prueba de respuesta perdida.
        } finally {
            exchange.close();
        }
    }

    private static HttpResponse<String> call(int port, String method, String path, String body, String token)
            throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(15));
        if (token != null && !token.isEmpty()) builder.header("Authorization", "Bearer " + token);
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode completed(String path, String body, String channel) throws Exception {
        var response = call(paymentPort, "POST", path, body, TOKENS.get(channel));
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode result = JSON.readTree(response.body());
        assertThat(result.path("status").asText()).isEqualTo("COMPLETED");
        return result;
    }

    private String accountBody(UUID id, int account, String amount) {
        return "{\"operationId\":\"" + id + "\",\"accountId\":" + account + ",\"amount\":" + amount + "}";
    }

    private String transferBody(UUID id, String amount) {
        return "{\"operationId\":\"" + id + "\",\"sourceAccountId\":101,\"targetAccountId\":102,\"amount\":" + amount + "}";
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private void sql(String sql) throws Exception {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private BigDecimal balance(int account) throws Exception {
        try (Connection connection = connection(); var statement = connection.prepareStatement(
                "SELECT saldo_operativo FROM cuentas WHERE cuenta_id=?")) {
            statement.setInt(1, account);
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getBigDecimal(1);
            }
        }
    }

    private int operationCount() throws Exception {
        try (Connection connection = connection(); var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT COUNT(*) FROM operaciones_cuenta")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private record ResponseDelay(CountDownLatch committed, CountDownLatch release) {
    }
}
