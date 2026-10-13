package cl.duoc.bancoxyz.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.SocketTimeoutException;
import java.util.*;
import java.util.function.*;
import org.junit.jupiter.api.*;
import org.springframework.cloud.client.circuitbreaker.*;
import org.springframework.http.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

class WebServicesClientTest {
    private static final UUID ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private final ObjectMapper json = new ObjectMapper();
    private MockRestServiceServer server;
    private WebServicesClient client;
    private CircuitBreakerFactory<?, ?> factory;

    @BeforeEach
    void setup() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        factory = mock(CircuitBreakerFactory.class);
        when(factory.create(anyString())).thenReturn(new CircuitBreaker() {
            public <T> T run(Supplier<T> call, Function<Throwable, T> fallback) {
                SecurityContextHolder.clearContext();
                try { return call.get(); } catch (RuntimeException error) { return fallback.apply(error); }
            }
        });
        client = new WebServicesClient(builder, factory);
    }

    @AfterEach
    void cleanup() {
        try { server.verify(); } finally { SecurityContextHolder.clearContext(); }
    }

    @Test
    void forwardsAdministrativeRequestsAndRebuildsPublicLocation() throws Exception {
        JsonNode body = json.readTree("{\"customerId\":17,\"accountName\":\"Cuenta\",\"accountType\":\"ahorro\",\"interestRate\":0.01}");
        expect("core-banking-service", HttpMethod.POST, "/api/core/accounts", body, 201, "{\"accountId\":101,\"customerId\":17,\"state\":\"ACTIVA\",\"finalBalance\":0}");
        var opened = authorized(() -> client.openAccount(body));
        assertThat(opened.getStatusCode().value()).isEqualTo(201);
        assertThat(opened.getHeaders().getLocation().toString()).isEqualTo("/api/bff/web/accounts/101");
        expect("core-banking-service", HttpMethod.PUT, "/api/core/accounts/101", body, 200, "{\"accountId\":101,\"finalBalance\":40}");
        assertThat(authorized(() -> client.updateAccount(101, body)).getBody().path("finalBalance").asInt()).isEqualTo(40);
        expect("core-banking-service", HttpMethod.POST, "/api/core/accounts/101/close", null, 200, "{\"accountId\":101,\"state\":\"CERRADA\"}");
        assertThat(authorized(() -> client.closeAccount(101)).getBody().path("state").asText()).isEqualTo("CERRADA");
        verify(factory, times(3)).create("coreWeb");
    }

    @Test
    void forwardsAllFiveCustomerContractsWithoutDuplicatingTheirModels() throws Exception {
        JsonNode body = json.readTree("{\"firstName\":\"Nombre\",\"lastName\":\"Prueba\",\"email\":\"cliente@example.test\",\"profile\":\"ESTANDAR\"}");
        String response = "{\"customerId\":17,\"firstName\":\"Nombre\",\"lastName\":\"Prueba\",\"email\":\"cliente@example.test\",\"phone\":null,\"profile\":\"ESTANDAR\"}";
        expect("customer-service", HttpMethod.POST, "/api/customers", body, 201, response);
        assertThat(authorized(() -> client.createCustomer(body)).getHeaders().getLocation().toString()).isEqualTo("/api/bff/web/customers/17");
        expect("customer-service", HttpMethod.GET, "/api/customers/17", null, 200, response);
        assertThat(authorized(() -> client.customer(17)).getBody()).isEqualTo(json.readTree(response));
        expect("customer-service", HttpMethod.PUT, "/api/customers/17", body, 200, response);
        assertThat(authorized(() -> client.updateCustomer(17, body)).getStatusCode().value()).isEqualTo(200);
        expect("customer-service", HttpMethod.GET, "/api/customers/17/profile", null, 200, "{\"customerId\":17,\"profile\":\"ESTANDAR\"}");
        assertThat(authorized(() -> client.profile(17)).getBody().path("profile").asText()).isEqualTo("ESTANDAR");
        expect("customer-service", HttpMethod.PUT, "/api/customers/17/profile", body, 200, "{\"customerId\":17,\"profile\":\"PREFERENTE\"}");
        assertThat(authorized(() -> client.updateProfile(17, body)).getBody().path("profile").asText()).isEqualTo("PREFERENTE");
        verify(factory, times(5)).create("customerWeb");
    }

    @Test
    void forwardsPaymentsWithoutChangingUuidAmountsOrResults() throws Exception {
        JsonNode account = json.readTree("{\"operationId\":\""+ID+"\",\"accountId\":101,\"amount\":10.25}");
        JsonNode transfer = json.readTree("{\"operationId\":\""+ID+"\",\"sourceAccountId\":101,\"targetAccountId\":102,\"amount\":10.25}");
        String result = "{\"operationId\":\""+ID+"\",\"type\":\"DEBIT\",\"status\":\"COMPLETED\",\"sourceBalance\":90.75,\"targetBalance\":null,\"rejectionReason\":null,\"completedAt\":\"2026-01-01T00:00:00Z\"}";
        expect("payment-service", HttpMethod.POST, "/api/payments", account, 200, result);
        assertThat(authorized(() -> client.payment(account)).getBody()).isEqualTo(json.readTree(result));
        expect("payment-service", HttpMethod.POST, "/api/payments/deposits", account, 200, result);
        assertThat(authorized(() -> client.deposit(account)).getStatusCode().value()).isEqualTo(200);
        expect("payment-service", HttpMethod.POST, "/api/payments/transfers", transfer, 200, result);
        assertThat(authorized(() -> client.transfer(transfer)).getStatusCode().value()).isEqualTo(200);
        expect("payment-service", HttpMethod.GET, "/api/payments/operations/"+ID, null, 200, result);
        assertThat(authorized(() -> client.operation(ID)).getBody()).isEqualTo(json.readTree(result));
        verify(factory, times(4)).create("paymentWeb");
    }

    @Test
    void functionalAndAuthorizationErrorsRetainTheirStatusAcrossServices() {
        for (int status : List.of(400, 404, 409, 401, 403)) {
            expect("core-banking-service", HttpMethod.POST, "/api/core/accounts/101/close", null, status, "{}");
            assertThat(authorized(() -> client.closeAccount(101)).getStatusCode().value()).isEqualTo(status);
            expect("customer-service", HttpMethod.GET, "/api/customers/17", null, status, "{}");
            assertThat(authorized(() -> client.customer(17)).getStatusCode().value()).isEqualTo(status);
            expect("payment-service", HttpMethod.GET, "/api/payments/operations/"+ID, null, status, "{}");
            var result = authorized(() -> client.operation(ID));
            assertThat(result.getStatusCode().value()).isEqualTo(status);
            assertThat(result.getBody().path("operationId").asText()).isEqualTo(ID.toString());
        }
    }

    @Test
    void timeoutKeepsUnknownAndUuidWithoutRepeatingTheCommand() throws Exception {
        JsonNode body = json.readTree("{\"operationId\":\""+ID+"\",\"accountId\":101,\"amount\":10}");
        server.expect(requestTo("http://payment-service/api/payments/deposits")).andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer web-test-token")).andExpect(content().json(body.toString()))
                .andRespond(withException(new SocketTimeoutException("Timeout simulado")));
        var result = authorized(() -> client.deposit(body));
        assertThat(result.getStatusCode().value()).isEqualTo(503);
        assertThat(result.getBody().path("status").asText()).isEqualTo("UNKNOWN");
        assertThat(result.getBody().path("operationId").asText()).isEqualTo(ID.toString());
        verify(factory).create("paymentWeb");
    }

    @Test
    void unavailableUnexpectedOrUnconfirmedResultsNeverReportCompletion() {
        for (String response : List.of("{}", "null", "{\"operationId\":\""+UUID.randomUUID()+"\",\"status\":\"COMPLETED\"}",
                "{\"operationId\":\""+ID+"\",\"status\":\"UNKNOWN\"}")) {
            expect("payment-service", HttpMethod.GET, "/api/payments/operations/"+ID, null, 200, response);
            assertThat(authorized(() -> client.operation(ID)).getBody().path("status").asText()).isEqualTo("UNKNOWN");
        }
        for (int status : List.of(302, 500, 502, 503)) {
            expect("payment-service", HttpMethod.GET, "/api/payments/operations/"+ID, null, status, "{}");
            assertThat(authorized(() -> client.operation(ID)).getStatusCode().value()).isEqualTo(503);
        }
    }

    @Test
    void missingOrInvalidOperationUuidNeverSendsARequest() throws Exception {
        for (String body : List.of("{}", "{\"operationId\":\"invalid\"}")) {
            assertThatThrownBy(() -> client.deposit(json.readTree(body))).isInstanceOf(ResponseStatusException.class)
                    .satisfies(error -> assertThat(((ResponseStatusException)error).getStatusCode().value()).isEqualTo(400));
        }
        verifyNoInteractions(factory);
    }

    private <T> T authorized(Supplier<T> call) {
        var token = Jwt.withTokenValue("web-test-token").header("alg", "RS256").claim("scope", "web").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(token));
        try { return call.get(); } finally { server.verify(); server.reset(); }
    }

    private void expect(String service, HttpMethod method, String path, JsonNode body, int status, String response) {
        var expected = server.expect(requestTo("http://"+service+path)).andExpect(method(method))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer web-test-token"));
        if (body != null) expected.andExpect(content().json(body.toString()));
        expected.andRespond(withStatus(HttpStatus.valueOf(status)).contentType(MediaType.APPLICATION_JSON)
                .header("Location", "http://internal-service/private").body(response));
    }
}
