package cl.duoc.bancoxyz.web;

import java.math.BigDecimal;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

// Protege las consultas web al Core ante fallos temporales.
@Service
public class WebBankingClient {
    private final RestClient rest;
    private final CircuitBreakerFactory<?, ?> breakers;

    public WebBankingClient(RestClient.Builder builder, CircuitBreakerFactory<?, ?> breakers) {
        this.rest = builder.baseUrl("http://core-banking-service").build();
        this.breakers = breakers;
    }

    public WebAccount account(Integer id) {
        var request = rest.get().uri("/api/core/accounts/{id}", id).headers(this::propagateBearerToken);
        return breakers.create("coreWeb")
                .run(() -> request.retrieve().body(WebAccount.class), e -> {
                    throw unavailable(e);
                });
    }

    public AnnualStatement statement(Integer id) {
        var request = rest.get().uri("/api/core/accounts/{id}/annual-statement", id)
                .headers(this::propagateBearerToken);
        return breakers.create("coreWeb").run(() -> request.retrieve().body(AnnualStatement.class), e -> {
                    throw unavailable(e);
                });
    }

    private void propagateBearerToken(HttpHeaders headers) {
        // Captura el token antes de ejecutar la llamada protegida por el circuito.
        if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken jwt) {
            headers.setBearerAuth(jwt.getToken().getTokenValue());
        }
    }

    private ResponseStatusException unavailable(Throwable failure) {
        Throwable cause = failure;
        while (cause != null) {
            if (cause instanceof RestClientResponseException response) {
                int status = response.getStatusCode().value();
                if (status == 400 || status == 404 || status == 409 || status == 401 || status == 403) {
                    return new ResponseStatusException(response.getStatusCode(), "Core rechazo la solicitud");
                }
            }
            cause = cause.getCause();
        }
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Servicio bancario temporalmente no disponible");
    }

    public record WebAccount(Integer accountId, String accountName, String accountType, BigDecimal initialBalance,
            BigDecimal interestRate, BigDecimal finalBalance) {
    }

    public record AnnualStatement(Integer accountId, int movementCount, BigDecimal totalDeposits,
            BigDecimal totalCharges, BigDecimal netBalance) {
    }
}
