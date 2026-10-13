package cl.duoc.bancoxyz.mobile;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

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

class MobileServicesClientTest {
    private static final UUID ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private MockRestServiceServer server;
    private MobileServicesClient client;
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
        client = new MobileServicesClient(builder, factory);
    }

    @AfterEach
    void cleanup() {
        try { server.verify(); } finally { SecurityContextHolder.clearContext(); }
    }

    @Test
    void projectsCustomerIntoThreeEssentialFields() {
        expect("customer-service/api/customers/17", 200, "{\"customerId\":17,\"firstName\":\"Nombre\",\"lastName\":\"Prueba\",\"email\":\"cliente@example.test\",\"phone\":\"123456\",\"profile\":\"ESTANDAR\",\"createdAt\":\"2026-01-01T00:00:00Z\"}");
        var result = authorized(() -> client.customer(17));
        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(result.getBody()).isEqualTo(new MobileServicesClient.Customer(17, "Nombre Prueba", "ESTANDAR"));
        verify(factory).create("customerMobile");
    }

    @Test
    void projectsCompletedAndRejectedOperationsWithoutBalancesOrPersonalData() {
        for (String state : List.of("COMPLETED", "REJECTED")) {
            expect("payment-service/api/payments/operations/"+ID, 200, "{\"operationId\":\""+ID+"\",\"type\":\"DEBIT\",\"status\":\""+state+"\",\"sourceBalance\":90,\"targetBalance\":null,\"rejectionReason\":null,\"completedAt\":\"2026-01-01T00:00:00Z\"}");
            var result = authorized(() -> client.operation(ID));
            assertThat(result.getBody()).isEqualTo(new MobileServicesClient.Operation(ID, "DEBIT", state, null));
        }
        verify(factory, times(2)).create("paymentMobile");
    }

    @Test
    void preservesFunctionalAndAuthorizationStatuses() {
        for (int status : List.of(400, 404, 409, 401, 403)) {
            expect("customer-service/api/customers/17", status, "{}");
            assertThat(authorized(() -> client.customer(17)).getStatusCode().value()).isEqualTo(status);
            expect("payment-service/api/payments/operations/"+ID, status, "{}");
            assertThat(authorized(() -> client.operation(ID)).getStatusCode().value()).isEqualTo(status);
        }
    }

    @Test
    void malformedOrWrongCustomerIdentityProduces503() {
        for (String body : List.of("{}", "null", "{\"customerId\":18}", "{\"customerId\":4294967313,\"firstName\":\"Nombre\",\"lastName\":\"Prueba\",\"profile\":\"ESTANDAR\"}")) {
            expect("customer-service/api/customers/17", 200, body);
            assertThat(authorized(() -> client.customer(17)).getStatusCode().value()).isEqualTo(503);
        }
    }

    @Test
    void lostOrUnconfirmedMonetaryResultRemainsUnknown() {
        server.expect(requestTo("http://payment-service/api/payments/operations/"+ID))
                .andExpect(header("Authorization", "Bearer mobile-test-token"))
                .andRespond(withException(new SocketTimeoutException("Timeout simulado")));
        var result = authorized(() -> client.operation(ID));
        assertThat(result.getStatusCode().value()).isEqualTo(503);
        assertThat(result.getBody().toString()).contains(ID.toString(), "UNKNOWN");
        for (String body : List.of("{}", "{\"operationId\":\""+UUID.randomUUID()+"\",\"status\":\"COMPLETED\",\"type\":\"DEBIT\"}")) {
            expect("payment-service/api/payments/operations/"+ID, 200, body);
            assertThat(authorized(() -> client.operation(ID)).getStatusCode().value()).isEqualTo(503);
        }
    }

    private <T> T authorized(Supplier<T> call) {
        var token = Jwt.withTokenValue("mobile-test-token").header("alg", "RS256").claim("scope", "mobile").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(token));
        try { return call.get(); } finally { server.verify(); server.reset(); }
    }

    private void expect(String path, int status, String body) {
        server.expect(requestTo("http://"+path)).andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer mobile-test-token"))
                .andRespond(withStatus(HttpStatus.valueOf(status)).contentType(MediaType.APPLICATION_JSON).body(body));
    }
}
