package cl.duoc.bancoxyz.atm;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

// Protege las consultas ATM al Core ante fallos temporales.
@Service
public class AtmBankingClient {
    private final RestClient rest;
    private final CircuitBreakerFactory<?, ?> breakers;

    public AtmBankingClient(RestClient.Builder b, CircuitBreakerFactory<?, ?> breakers) {
        rest = b.baseUrl("http://core-banking-service").build();
        this.breakers = breakers;
    }

    public Balance balance(Integer id) {
        var request = rest.get().uri("/api/core/accounts/{id}/balance", id).headers(this::propagateBearerToken);
        return breakers.create("coreAtm")
                .run(() -> request.retrieve().body(Balance.class), e -> {
                    throw unavailable();
                });
    }

    public AsyncWithdrawalStatus asyncStatus(UUID transactionId) {
        var request = rest.get().uri("/api/core/accounts/async-withdrawals/{transactionId}", transactionId)
                .headers(this::propagateBearerToken);
        return breakers.create("coreAtm").run(() -> request.retrieve().body(AsyncWithdrawalStatus.class), e -> { throw unavailable(); });
    }

    private void propagateBearerToken(HttpHeaders headers) {
        // Captura el token antes de ejecutar la llamada protegida por el circuito.
        if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken jwt) {
            headers.setBearerAuth(jwt.getToken().getTokenValue());
        }
    }

    private ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Servicio bancario temporalmente no disponible");
    }

    public record Balance(Integer accountId, BigDecimal balance) {
    }

    public record AsyncWithdrawalStatus(UUID transactionId, Integer accountId, BigDecimal amount, String status,
            BigDecimal finalBalance, String rejectionReason, java.time.Instant updatedAt) {
    }
}
