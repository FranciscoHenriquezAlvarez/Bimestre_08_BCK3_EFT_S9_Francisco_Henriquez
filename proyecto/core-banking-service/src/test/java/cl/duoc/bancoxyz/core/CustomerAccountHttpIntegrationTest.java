package cl.duoc.bancoxyz.core;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class CustomerAccountHttpIntegrationTest extends OperationalAccountsIntegrationSupport {
    private static final String CUSTOMER = "{\"customerId\":17,\"firstName\":\"Nombre\",\"lastName\":\"Prueba\",\"email\":\"cliente@example.test\",\"profile\":\"ESTANDAR\"}";
    private static final String OPEN = "{\"customerId\":17,\"accountName\":\"Cuenta de prueba\",\"accountType\":\"ahorro\",\"interestRate\":0.01}";
    private static final AtomicReference<RemoteResponse> RESPONSE = new AtomicReference<>();
    private static final AtomicReference<String> BEARER = new AtomicReference<>();
    private static final AtomicReference<String> PATH = new AtomicReference<>();
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static final ExecutorService HTTP_THREADS = Executors.newCachedThreadPool();
    private static final HttpServer CUSTOMER_HTTP = startHttp();
    @Autowired MockMvc mvc;
    @MockBean JwtDecoder decoder;

    @DynamicPropertySource
    static void discovery(DynamicPropertyRegistry properties) {
        properties.add("spring.cloud.discovery.client.simple.instances.customer-service[0].uri",
                () -> "http://localhost:" + CUSTOMER_HTTP.getAddress().getPort());
        properties.add("core.customer.connect-timeout-ms", () -> 500);
        properties.add("core.customer.read-timeout-ms", () -> 500);
    }

    @BeforeEach
    void resetRemote() {
        RESPONSE.set(new RemoteResponse(200, CUSTOMER, 0));
        BEARER.set(null);
        PATH.set(null);
        CALLS.set(0);
    }

    @AfterAll
    static void stopOnlyTheHttpFixture() {
        CUSTOMER_HTTP.stop(0);
        HTTP_THREADS.shutdownNow();
    }

    @Test
    void loadBalancerResolvesCustomersAndPropagatesOnlyTheIncomingBearer() throws Exception {
        open(201);
        assertThat(BEARER.get()).isEqualTo("Bearer token");
        assertThat(PATH.get()).isEqualTo("/api/customers/17");
        assertThat(CALLS.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT cliente_id FROM cuentas", Integer.class)).isEqualTo(17);
    }

    @Test
    void missingCustomerIsNotConfusedWithTemporaryFailure() throws Exception {
        RESPONSE.set(new RemoteResponse(404, "{}", 0));
        open(404);
        noWrites();
    }

    @Test
    void upstreamAuthenticationRejectionsPreventAccountCreation() throws Exception {
        for (int status : List.of(401, 403)) {
            RESPONSE.set(new RemoteResponse(status, "{}", 0));
            open(403);
            noWrites();
        }
    }

    @Test
    void unexpectedStatusesAndRedirectsDoNotCreateAccountsOrForwardTokens() throws Exception {
        List<Integer> statuses = List.of(201, 204, 301, 302, 307, 308, 400, 429, 500, 503);
        for (int status : statuses) {
            RESPONSE.set(new RemoteResponse(status, "{}", 0));
            open(503);
            noWrites();
        }
        assertThat(CALLS.get()).isEqualTo(statuses.size());
        assertThat(PATH.get()).isEqualTo("/api/customers/17");
    }

    @Test
    void wrongIdentityIncompleteOrMalformedBodiesFailClosed() throws Exception {
        for (String body : List.of(CUSTOMER.replace("17", "18"), "{}", "null", "", "not-json",
                CUSTOMER.replace("ESTANDAR", "ADMIN"), CUSTOMER.replace("Nombre", ""), CUSTOMER.replace("17", "null"))) {
            RESPONSE.set(new RemoteResponse(200, body, 0));
            open(503);
            noWrites();
        }
    }

    @Test
    void aRealReadTimeoutDoesNotConfirmCustomerExistence() throws Exception {
        RESPONSE.set(new RemoteResponse(200, CUSTOMER, 1500));
        long start = System.nanoTime();
        open(503);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
        noWrites();
    }

    @Test
    void brokenHttpConnectionProducesTemporaryFailureWithoutWrites() throws Exception {
        RESPONSE.set(new RemoteResponse(-1, "", 0));
        open(503);
        noWrites();
    }

    private void open(int status) throws Exception {
        mvc.perform(post("/api/core/accounts").contentType(MediaType.APPLICATION_JSON).content(OPEN)
                .with(jwt().jwt(token -> token.claim("scope", "web"))))
                .andExpect(status().is(status));
    }

    private void noWrites() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cuentas", Integer.class)).isZero();
        assertThat(operationCount()).isZero();
    }

    private static HttpServer startHttp() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.setExecutor(HTTP_THREADS);
            server.createContext("/", exchange -> {
                RemoteResponse response = RESPONSE.get();
                BEARER.set(exchange.getRequestHeaders().getFirst("Authorization"));
                PATH.set(exchange.getRequestURI().getPath());
                CALLS.incrementAndGet();
                try {
                    if (response.status() == -1) return;
                    if (response.delayMs() > 0) Thread.sleep(response.delayMs());
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    if (response.status() >= 300 && response.status() < 400) {
                        exchange.getResponseHeaders().set("Location", "http://localhost:" + server.getAddress().getPort() + "/must-not-receive-token");
                    }
                    byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(response.status(), response.status() == 204 ? -1 : body.length);
                    if (response.status() != 204) exchange.getResponseBody().write(body);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                } catch (java.io.IOException ignored) {
                    // El cliente puede cerrar la conexion cuando vence el timeout de la prueba.
                } finally {
                    exchange.close();
                }
            });
            server.start();
            return server;
        } catch (java.io.IOException error) {
            throw new IllegalStateException("No fue posible iniciar Clientes HTTP simulado", error);
        }
    }

    private record RemoteResponse(int status, String body, int delayMs) {
    }
}
