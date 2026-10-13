package cl.duoc.bancoxyz.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// Solo procesos y PostgreSQL propios; no publica solicitudes ni activa consumidores Kafka.
@Testcontainers
class AccountsBffOAuth2IT {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private static final Map<String, String> SECRETS = Map.of("web", UUID.randomUUID().toString(),
            "mobile", UUID.randomUUID().toString(), "atm", UUID.randomUUID().toString(),
            "payment", UUID.randomUUID().toString());
    private static final Map<String, String> TOKENS = new HashMap<>();
    private static final Map<String, Integer> PORTS = new HashMap<>();
    private static final List<BffTestApplicationProcess> APPS = new ArrayList<>();
    private static final UUID TRANSACTION = UUID.randomUUID();
    private static int accountId;

    @Container
    static final PostgreSQLContainer<?> CORE_DB = database("eft_accounts_bff");
    @Container
    static final PostgreSQLContainer<?> CUSTOMER_DB = database("eft_accounts_customers");

    @BeforeAll
    static void startOnlyOwnedApplications() throws Exception {
        for (String app : List.of("auth", "core", "customer", "payment", "web", "mobile", "atm")) {
            PORTS.put(app, BffTestApplicationProcess.freePort());
        }
        Map<String, String> auth = new HashMap<>(jwtEnvironment());
        SECRETS.forEach((key, value) -> auth.put("OAUTH2_" + key.toUpperCase(Locale.ROOT) + "_SECRET", value));
        start("auth", "authorization-server", "cl.duoc.bancoxyz.authorization.AuthorizationServerApplication",
                "cl.duoc.bancoxyz.authorization.AuthorizationServerTest", auth, List.of());
        start("customer", "customer-service", "cl.duoc.bancoxyz.customer.CustomerApplication",
                "cl.duoc.bancoxyz.customer.CustomerServiceTest", databaseEnvironment(CUSTOMER_DB),
                List.of("--spring.sql.init.mode=always"));
        prepareHistoricalData();
        start("core", "core-banking-service", "cl.duoc.bancoxyz.BankBatchApplication",
                "cl.duoc.bancoxyz.core.CoreSecurityTest", databaseEnvironment(CORE_DB),
                List.of("--bank.config-origin=Prueba de cuentas aislada", "--spring.sql.init.mode=never",
                        "--spring.batch.job.enabled=false",
                        "--spring.cloud.discovery.client.simple.instances.customer-service[0].uri=" + url("customer")));
        Map<String, String> payment = new HashMap<>(jwtEnvironment());
        payment.put("OAUTH2_PAYMENT_SECRET", SECRETS.get("payment"));
        payment.put("OAUTH2_TOKEN_URI", url("auth") + "/oauth2/token");
        start("payment", "payment-service", "cl.duoc.bancoxyz.payment.PaymentApplication",
                "cl.duoc.bancoxyz.payment.PaymentControllerTest", payment,
                List.of("--payment.kafka.withdrawals.enabled=false",
                        "--spring.cloud.discovery.client.simple.instances.core-banking-service[0].uri=" + url("core")));
        for (String channel : List.of("web", "mobile", "atm")) {
            String title = channel.equals("web") ? "Web" : channel.equals("mobile") ? "Mobile" : "Atm";
            String test = channel.equals("web") ? "WebSecurityTest" : channel.equals("mobile") ? "MobileSecurityTest" : "AtmBffControllerTest";
            start(channel, "bff-" + channel + "-service", "cl.duoc.bancoxyz." + title + "BffApplication",
                    "cl.duoc.bancoxyz." + channel + "." + test, jwtEnvironment(), List.of(
                            "--spring.cloud.discovery.client.simple.instances.core-banking-service[0].uri=" + url("core"),
                            "--spring.cloud.discovery.client.simple.instances.customer-service[0].uri=" + url("customer"),
                            "--spring.cloud.discovery.client.simple.instances.payment-service[0].uri=" + url("payment")));
            TOKENS.put(channel, token(channel));
        }
        TOKENS.put("payment", token("payment"));
        prepareOperativeAccountThroughApis();
    }

    @AfterAll
    static void stopOnlyOwnedApplications() throws Exception {
        List<BffTestApplicationProcess> reversed = new ArrayList<>(APPS);
        Collections.reverse(reversed);
        Exception failure = null;
        for (BffTestApplicationProcess app : reversed) {
            try {
                app.close();
            } catch (Exception error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
    }

    @Test
    void operativeAccountsRemainReadableAndNewApiAccountsAvoidAllHistoricalIds() throws Exception {
        Map<String, List<String>> before = snapshot();
        // Solo consultas idempotentes esperan que el LoadBalancer este disponible tras el arranque.
        for (String channel : List.of("web", "mobile", "atm")) {
            String path = "/api/bff/" + channel + "/accounts/" + accountId + (channel.equals("atm") ? "/balance" : "");
            await().pollInSameThread().atMost(Duration.ofSeconds(10)).until(() -> call(channel, "GET", path, null).statusCode() == 200);
            HttpResponse<String> response = call(channel, "GET", path, null);
            assertThat(response.statusCode()).isEqualTo(200);
            JsonNode account = JSON.readTree(response.body());
            assertThat(account.path("accountId").asInt()).isEqualTo(accountId);
            assertThat(account.path(channel.equals("atm") ? "balance" : "finalBalance").decimalValue())
                    .isEqualByComparingTo("90000");
            if (!channel.equals("atm")) {
                assertThat(account.path("accountName").asText()).isEqualTo("Cuenta operativa por API");
                assertThat(account.path("accountType").asText()).isEqualTo("ahorro");
            }
            System.out.println("EFT CUENTAS BFF " + channel + " HTTP200 JWT valido " + account);
        }
        HttpResponse<String> annual = call("web", "GET", "/api/bff/web/accounts/" + accountId + "/annual-statement", null);
        assertThat(annual.statusCode()).isEqualTo(200);
        JsonNode statement = JSON.readTree(annual.body());
        assertThat(statement.path("movementCount").asInt()).isEqualTo(2);
        assertThat(statement.path("netBalance").decimalValue()).isEqualByComparingTo("5");
        HttpResponse<String> latest = call("mobile", "GET", "/api/bff/mobile/accounts/" + accountId + "/transactions", null);
        assertThat(latest.statusCode()).isEqualTo(200);
        JsonNode movements = JSON.readTree(latest.body());
        assertThat(movements.size()).isEqualTo(2);
        assertThat(movements.get(0).path("amount").decimalValue()).isEqualByComparingTo("-5");
        HttpResponse<String> withdrawal = call("atm", "GET", "/api/bff/atm/accounts/transactions/" + TRANSACTION, null);
        assertThat(withdrawal.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(withdrawal.body()).path("status").asText()).isEqualTo("COMPLETED");
        assertThat(JSON.readTree(withdrawal.body()).path("finalBalance").decimalValue()).isEqualByComparingTo("90000");
        assertThat(snapshot()).isEqualTo(before);

        HttpResponse<String> customer = call("web", "POST", "/api/bff/web/customers", Map.of(
                "firstName", "Nombre sintetico", "lastName", "Ensayo", "email", "cuentas-" + UUID.randomUUID() + "@example.test",
                "profile", "ESTANDAR"));
        assertThat(customer.statusCode()).isEqualTo(201);
        int customerId = JSON.readTree(customer.body()).path("customerId").asInt();
        HttpResponse<String> opening = call("web", "POST", "/api/bff/web/accounts", Map.of(
                "customerId", customerId, "accountName", "Cuenta nueva por API", "accountType", "ahorro", "interestRate", 0.01));
        assertThat(opening.statusCode()).isEqualTo(201);
        JsonNode opened = JSON.readTree(opening.body());
        assertThat(opened.path("accountId").asInt()).isGreaterThan(750);
        assertThat(opened.path("finalBalance").decimalValue()).isEqualByComparingTo("0");
        assertThat(opened.path("customerId").asInt()).isEqualTo(customerId);
        Map<String, List<String>> after = snapshot();
        assertThat(after.get("cuentas")).containsAll(before.get("cuentas")).hasSize(before.get("cuentas").size() + 1);
        for (String table : before.keySet()) {
            if (!table.equals("cuentas")) assertThat(after.get(table)).isEqualTo(before.get(table));
        }
        System.out.println("EFT CUENTAS BFF consultas conservan filas completas; nueva cuenta HTTP201 id=" + opened.path("accountId")
                + " mayor que historico750, cliente existente validado, saldo0; sin ejecutar operaciones monetarias");
    }

    private static void prepareHistoricalData() throws Exception {
        try (Connection connection = coreConnection(); java.sql.Statement sql = connection.createStatement()) {
            sql.execute(Files.readString(Path.of("../core-banking-service/src/main/resources/schema.sql")));
            sql.execute("""
                    INSERT INTO intereses_mensuales VALUES (101, 'Cuenta historica de prueba', 'ahorro', 100000, 0.01, 90000);
                    INSERT INTO movimientos_anuales (cuenta_id,fecha,transaccion,monto,descripcion) VALUES
                    (750,'2025-01-01','deposito',1,'Identificador historico maximo');
                    """);
        }
    }

    private static void prepareOperativeAccountThroughApis() throws Exception {
        // Espera solo una consulta; las escrituras se envian una vez y conservan sus UUID.
        await().pollInSameThread().atMost(Duration.ofSeconds(10)).until(() ->
                call("web", "GET", "/api/bff/web/customers/2147483647", null).statusCode() == 404);
        HttpResponse<String> customer = call("web", "POST", "/api/bff/web/customers", Map.of(
                "firstName", "Titular", "lastName", "Prueba", "email", "titular-" + UUID.randomUUID() + "@example.test",
                "profile", "ESTANDAR"));
        assertThat(customer.statusCode()).isEqualTo(201);
        int customerId = JSON.readTree(customer.body()).path("customerId").asInt();
        HttpResponse<String> opening = call("web", "POST", "/api/bff/web/accounts", Map.of(
                "customerId", customerId, "accountName", "Cuenta operativa por API", "accountType", "ahorro", "interestRate", 0.01));
        assertThat(opening.statusCode()).isEqualTo(201);
        JsonNode opened = JSON.readTree(opening.body());
        accountId = opened.path("accountId").asInt();
        assertThat(accountId).isGreaterThan(750);
        assertThat(opened.path("finalBalance").decimalValue()).isEqualByComparingTo("0");
        HttpResponse<String> deposit = call("web", "POST", "/api/bff/web/payments/deposits", Map.of(
                "operationId", UUID.randomUUID().toString(), "accountId", accountId, "amount", 100000));
        assertThat(deposit.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(deposit.body()).path("status").asText()).isEqualTo("COMPLETED");
        HttpResponse<String> withdrawal = call("core", "POST", "/api/core/internal/withdrawals", Map.of(
                "eventId", UUID.randomUUID().toString(), "transactionId", TRANSACTION.toString(), "accountId", accountId,
                "amount", 10000, "occurredAt", Instant.now().toString()), "payment");
        assertThat(withdrawal.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(withdrawal.body()).path("status").asText()).isEqualTo("COMPLETED");
        // Solo el historial sintetico usa JDBC; cliente, cuenta y dinero se registran por API.
        try (Connection connection = coreConnection(); java.sql.PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO movimientos_anuales (cuenta_id,fecha,transaccion,monto,descripcion) VALUES
                (?,'2025-01-01','deposito',10,'Movimiento sintetico'),
                (?,'2025-01-02','cargo',-5,'Movimiento sintetico')
                """)) {
            insert.setInt(1, accountId);
            insert.setInt(2, accountId);
            insert.executeUpdate();
        }
    }

    private static Map<String, List<String>> snapshot() throws Exception {
        Map<String, List<String>> result = new LinkedHashMap<>();
        try (Connection connection = coreConnection(); java.sql.Statement sql = connection.createStatement()) {
            for (String table : List.of("cuentas", "operaciones_cuenta", "intereses_mensuales", "withdrawal_transactions", "banking_events", "movimientos_anuales")) {
                List<String> rows = new ArrayList<>();
                try (java.sql.ResultSet values = sql.executeQuery("SELECT row_to_json(t)::text FROM " + table + " t ORDER BY 1")) {
                    while (values.next()) rows.add(values.getString(1));
                }
                result.put(table, rows);
            }
        }
        return result;
    }

    private static Connection coreConnection() throws Exception {
        return DriverManager.getConnection(CORE_DB.getJdbcUrl(), CORE_DB.getUsername(), CORE_DB.getPassword());
    }

    private static void start(String name, String module, String main, String reference, Map<String, String> environment,
            List<String> properties) throws Exception {
        List<String> arguments = new ArrayList<>(properties);
        arguments.addAll(List.of("--spring.kafka.admin.auto-create=false", "--spring.kafka.listener.auto-startup=false"));
        List<String> secrets = new ArrayList<>(SECRETS.values());
        secrets.add(CORE_DB.getPassword()); secrets.add(CUSTOMER_DB.getPassword());
        BffTestApplicationProcess process = new BffTestApplicationProcess(module, main,
                BffTestApplicationProcess.moduleClasspath(module, reference), PORTS.get(name), environment, arguments, secrets);
        APPS.add(process);
        process.awaitHealth(PORTS.get(name));
    }

    private static Map<String, String> jwtEnvironment() {
        return Map.of("OAUTH2_ISSUER", url("auth"), "OAUTH2_JWK_SET_URI", url("auth") + "/oauth2/jwks");
    }

    private static Map<String, String> databaseEnvironment(PostgreSQLContainer<?> database) {
        Map<String, String> result = new HashMap<>(jwtEnvironment());
        result.put("SPRING_DATASOURCE_URL", database.getJdbcUrl());
        result.put("SPRING_DATASOURCE_USERNAME", database.getUsername());
        result.put("SPRING_DATASOURCE_PASSWORD", database.getPassword());
        return result;
    }

    private static PostgreSQLContainer<?> database(String name) {
        return new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName(name)
                .withUsername("eft_test").withPassword(UUID.randomUUID().toString());
    }

    private static String token(String channel) throws Exception {
        String basic = Base64.getEncoder().encodeToString((channel + "-client:" + SECRETS.get(channel)).getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(url("auth") + "/oauth2/token"))
                .timeout(Duration.ofSeconds(10)).header("Authorization", "Basic " + basic)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials&scope="
                        + (channel.equals("payment") ? "account.operations" : channel))).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return JSON.readTree(response.body()).path("access_token").asText();
    }

    private static HttpResponse<String> call(String channel, String method, String path, Object body) throws Exception {
        return call(channel, method, path, body, channel);
    }

    private static HttpResponse<String> call(String channel, String method, String path, Object body, String client) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url(channel) + path)).timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + TOKENS.get(client));
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String url(String app) {
        return "http://localhost:" + PORTS.get(app);
    }
}
