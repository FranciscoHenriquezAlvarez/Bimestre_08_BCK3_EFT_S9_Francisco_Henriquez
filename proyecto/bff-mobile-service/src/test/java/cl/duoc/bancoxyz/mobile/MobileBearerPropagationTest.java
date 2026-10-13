package cl.duoc.bancoxyz.mobile;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class MobileBearerPropagationTest {
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void propagatesAuthenticatedBearerTokenToCore() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        CircuitBreakerFactory<?, ?> factory = mock(CircuitBreakerFactory.class);
        when(factory.create("coreMobile")).thenReturn(new CircuitBreaker() {
            @Override
            public <T> T run(Supplier<T> supplier, Function<Throwable, T> fallback) {
                SecurityContextHolder.clearContext();
                SecurityContextHolder.clearContext();
                return supplier.get();
            }
        });
        MobileBankingClient client = new MobileBankingClient(builder, factory);
        Jwt token = Jwt.withTokenValue("oauth-access-token").header("alg", "RS256").claim("scope", "mobile").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(token));
        server.expect(requestTo("http://core-banking-service/api/core/accounts/104"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer oauth-access-token"))
                .andRespond(withSuccess("{\"accountId\":104}", MediaType.APPLICATION_JSON));

        assertEquals(104, client.account(104).accountId());
        server.verify();
        verify(factory).create("coreMobile");
    }
}
