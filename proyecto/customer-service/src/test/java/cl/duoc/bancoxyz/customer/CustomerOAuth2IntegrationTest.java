package cl.duoc.bancoxyz.customer;

import static org.assertj.core.api.Assertions.assertThat;

import cl.duoc.bancoxyz.customer.CustomerModels.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.w3c.dom.Element;

@Testcontainers
class CustomerOAuth2IntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private static final Map<String, String> SECRETS = Map.of("web", UUID.randomUUID().toString(),
            "mobile", UUID.randomUUID().toString(), "atm", UUID.randomUUID().toString(), "payment", UUID.randomUUID().toString());
    private static final Map<String, String> TOKENS = new HashMap<>();
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("eft_customer_oauth_test").withUsername("eft_test").withPassword(UUID.randomUUID().toString());
    private static Process authorization;
    private static Thread authorizationLogs;
    private static Path authorizationLog;
    private static ConfigurableApplicationContext customer;
    private static int authPort;
    private static int customerPort;

    @BeforeAll
    static void startOnlyIsolatedApplications() throws Exception {
        startAuthorization();
        Map<String, Object> properties = new HashMap<>();
        properties.put("server.port", 0);
        properties.put("spring.cloud.config.enabled", false);
        properties.put("eureka.client.enabled", false);
        properties.put("spring.datasource.url", POSTGRES.getJdbcUrl());
        properties.put("spring.datasource.username", POSTGRES.getUsername());
        properties.put("spring.datasource.password", POSTGRES.getPassword());
        properties.put("spring.sql.init.mode", "always");
        properties.put("spring.security.oauth2.resourceserver.jwt.issuer-uri", "http://localhost:" + authPort);
        properties.put("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", "http://localhost:" + authPort + "/oauth2/jwks");
        properties.put("debug", false);
        properties.put("trace", false);
        properties.put("logging.level.root", "WARN");
        SpringApplication application = new SpringApplication(CustomerApplication.class);
        application.addInitializers(context -> context.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("step06-isolated", properties)));
        customer = application.run("--spring.config.location=classpath:application.properties");
        customerPort = ((ServletWebServerApplicationContext) customer).getWebServer().getPort();
        for (String client : SECRETS.keySet()) TOKENS.put(client, token(client));
    }

    @AfterAll
    static void stopOnlyTestApplications() throws Exception {
        try {
            if (customer != null) customer.close();
        } finally {
            if (authorization != null) {
                authorization.destroy();
                if (!authorization.waitFor(10, TimeUnit.SECONDS)) {
                    authorization.destroyForcibly();
                    authorization.waitFor(5, TimeUnit.SECONDS);
                }
            }
            if (authorizationLogs != null) authorizationLogs.join(2000);
        }
    }

    @Test
    void realWebAndMobileTokensCanUseAllFiveCustomerEndpoints() throws Exception {
        for (String channel : List.of("web", "mobile")) {
            String token = TOKENS.get(channel);
            JsonNode registered = register(token);
            int id = registered.path("customerId").asInt();
            String path = "/api/customers/" + id;
            var query = call("GET", path, null, token);
            assertThat(query.statusCode()).isEqualTo(200);
            assertThat(JSON.readTree(query.body())).isEqualTo(registered);
            var information = new PersonalInformation("Nombre actualizado", "Apellido actualizado", email(), "+56 9 1234 5678");
            var update = call("PUT", path, information, token);
            assertThat(update.statusCode()).isEqualTo(200);
            assertThat(JSON.readTree(update.body()).path("email").asText()).isEqualTo(information.email());
            var profile = call("GET", path + "/profile", null, token);
            assertThat(profile.statusCode()).isEqualTo(200);
            assertThat(JSON.readTree(profile.body()).path("profile").asText()).isEqualTo("ESTANDAR");
            var changeProfile = call("PUT", path + "/profile", new ProfileRequest(Profile.PREFERENTE), token);
            assertThat(changeProfile.statusCode()).isEqualTo(200);
            assertThat(JSON.readTree(changeProfile.body()).path("profile").asText()).isEqualTo("PREFERENTE");
            Customer persisted = customer.getBean(CustomerService.class).find(id);
            assertThat(persisted.email()).isEqualTo(information.email());
            assertThat(persisted.profile()).isEqualTo(Profile.PREFERENTE);
        }
        System.out.println("EFT06 OAuth2 real: Web y Mobile usan cinco endpoints, HTTP 201/200 y datos persistidos en PostgreSQL aislado");
    }

    @Test
    void realAtmAndPaymentTokensAreForbiddenAndCannotAlterCustomers() throws Exception {
        JsonNode original = register(TOKENS.get("web"));
        String path = "/api/customers/" + original.path("customerId").asInt();
        int count = customerCount();
        for (String channel : List.of("atm", "payment")) {
            assertAllEndpointsDenied(path, TOKENS.get(channel), 403);
        }
        assertThat(customerCount()).isEqualTo(count);
        assertThat(JSON.readTree(call("GET", path, null, TOKENS.get("mobile")).body())).isEqualTo(original);
        System.out.println("EFT06 OAuth2 real: ATM y account.operations reciben 403 en cinco endpoints, datos intactos");
    }

    @Test
    void missingMalformedAndInvalidSignaturesAreUnauthorizedAndHealthIsPublic() throws Exception {
        int count = customerCount();
        for (String token : List.of("", "invalid-test-token", invalidSignature(TOKENS.get("web")))) {
            assertAllEndpointsDenied("/api/customers/1", token, 401);
        }
        assertThat(customerCount()).isEqualTo(count);
        assertThat(call("GET", "/actuator/health", null, "").statusCode()).isEqualTo(200);
        for (String path : List.of("/api/customers", "/api/customers/1/private", "/actuator/env", "/unknown")) {
            assertThat(call("GET", path, null, TOKENS.get("web")).statusCode()).isEqualTo(403);
        }
        assertThat(call("DELETE", "/api/customers/1", null, TOKENS.get("mobile")).statusCode()).isEqualTo(403);
        System.out.println("EFT06 OAuth2 real: ausencia, formato invalido y firma alterada dan 401; rutas no autorizadas 403; health publico 200");
    }

    private void assertAllEndpointsDenied(String path, String token, int expected) throws Exception {
        var registration = new CreateCustomerRequest("Nombre", "Prueba", email(), null, Profile.PREFERENTE);
        var information = new PersonalInformation("Nombre actualizado", "Prueba", email(), null);
        assertThat(call("POST", "/api/customers", registration, token).statusCode()).isEqualTo(expected);
        assertThat(call("GET", path, null, token).statusCode()).isEqualTo(expected);
        assertThat(call("PUT", path, information, token).statusCode()).isEqualTo(expected);
        assertThat(call("GET", path + "/profile", null, token).statusCode()).isEqualTo(expected);
        assertThat(call("PUT", path + "/profile", new ProfileRequest(Profile.PREFERENTE), token).statusCode()).isEqualTo(expected);
    }

    private JsonNode register(String token) throws Exception {
        var response = call("POST", "/api/customers", new CreateCustomerRequest("Nombre", "Prueba", email(), null, Profile.ESTANDAR), token);
        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode result = JSON.readTree(response.body());
        assertThat(response.headers().firstValue("Location").orElseThrow()).isEqualTo("/api/customers/" + result.path("customerId").asInt());
        return result;
    }

    private static HttpResponse<String> call(String method, String path, Object body, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + customerPort + path)).timeout(Duration.ofSeconds(10));
        if (!token.isEmpty()) builder.header("Authorization", "Bearer " + token);
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String token(String client) throws Exception {
        String credentials = Base64.getEncoder().encodeToString((client + "-client:" + SECRETS.get(client)).getBytes(StandardCharsets.UTF_8));
        String scope = "payment".equals(client) ? "account.operations" : client;
        var response = HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + authPort + "/oauth2/token"))
                .timeout(Duration.ofSeconds(10)).header("Authorization", "Basic " + credentials)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials&scope=" + scope)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode result = JSON.readTree(response.body());
        assertThat(result.path("scope").asText()).isEqualTo(scope);
        return result.path("access_token").asText();
    }

    private static String invalidSignature(String token) {
        int start = token.lastIndexOf('.') + 1;
        char replacement = token.charAt(start) == 'A' ? 'B' : 'A';
        return token.substring(0, start) + replacement + token.substring(start + 1);
    }

    private static int customerCount() {
        return customer.getBean(JdbcTemplate.class).queryForObject("SELECT COUNT(*) FROM clientes", Integer.class);
    }

    private static String email() {
        return "cliente-" + UUID.randomUUID() + "@example.test";
    }

    private static void startAuthorization() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) { authPort = socket.getLocalPort(); }
        Path module = Path.of("../authorization-server").toAbsolutePath().normalize();
        Path working = Path.of("target/step06-oauth/" + UUID.randomUUID()).toAbsolutePath();
        Files.createDirectories(working);
        authorizationLog = working.resolve("authorization.log");
        var command = List.of(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-cp", authorizationClasspath(),
                "cl.duoc.bancoxyz.authorization.AuthorizationServerApplication", "--server.port=" + authPort,
                "--spring.config.location=" + module.resolve("src/main/resources/application.properties").toUri(),
                "--debug=false", "--trace=false", "--logging.level.root=WARN");
        ProcessBuilder builder = new ProcessBuilder(command).directory(working.toFile()).redirectErrorStream(true);
        builder.environment().put("OAUTH2_ISSUER", "http://localhost:" + authPort);
        SECRETS.forEach((client, secret) -> builder.environment().put("OAUTH2_" + client.toUpperCase(Locale.ROOT) + "_SECRET", secret));
        authorization = builder.start();
        authorizationLogs = new Thread(() -> {
            try (var reader = new BufferedReader(new InputStreamReader(authorization.getInputStream()));
                    var writer = Files.newBufferedWriter(authorizationLog)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    for (String secret : SECRETS.values()) line = line.replace(secret, "[CREDENCIAL OMITIDA]");
                    line = line.replaceAll("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+", "[JWT OMITIDO]")
                            .replaceAll("(?i)\\b(Basic|Bearer)\\s+[A-Za-z0-9._~+/=-]+", "$1 [TOKEN OMITIDO]");
                    writer.write(line);
                    writer.newLine();
                    writer.flush();
                }
            } catch (Exception error) {
                throw new IllegalStateException("No fue posible conservar el log filtrado de la prueba OAuth2", error);
            }
        }, "step06-authorization-log");
        authorizationLogs.setDaemon(true);
        authorizationLogs.start();
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        while (System.nanoTime() < deadline && authorization.isAlive()) {
            try {
                var response = HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + authPort + "/actuator/health"))
                        .timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() == 200) return;
            } catch (java.io.IOException ignored) {
                // El servidor aislado puede tardar en aceptar conexiones durante el inicio.
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Authorization Server de prueba no inicio: " + Files.readString(authorizationLog));
    }

    private static String authorizationClasspath() throws Exception {
        Path report = Path.of("../authorization-server/target/surefire-reports/TEST-cl.duoc.bancoxyz.authorization.AuthorizationServerTest.xml");
        if (!Files.isRegularFile(report)) throw new AssertionError("Ejecute ./mvnw test desde proyecto/ para preparar Authorization Server");
        // Reutiliza el classpath Maven existente sin agregar dependencias de produccion entre servicios.
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var properties = factory.newDocumentBuilder().parse(report.toFile()).getElementsByTagName("property");
        for (int index = 0; index < properties.getLength(); index++) {
            Element property = (Element) properties.item(index);
            if ("java.class.path".equals(property.getAttribute("name"))) {
                return String.join(File.pathSeparator, Arrays.stream(property.getAttribute("value").split(File.pathSeparator))
                        .filter(path -> !path.endsWith("/target/test-classes")).toList());
            }
        }
        throw new AssertionError("El reporte Maven no contiene el classpath de Authorization Server");
    }
}
