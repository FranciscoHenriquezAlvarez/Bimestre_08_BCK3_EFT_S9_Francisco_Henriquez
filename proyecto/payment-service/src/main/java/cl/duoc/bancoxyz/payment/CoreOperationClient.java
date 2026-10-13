package cl.duoc.bancoxyz.payment;

import cl.duoc.bancoxyz.events.WithdrawalCompletedEvent;
import cl.duoc.bancoxyz.events.WithdrawalRejectedEvent;
import cl.duoc.bancoxyz.events.WithdrawalRequestedEvent;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import cl.duoc.bancoxyz.payment.PaymentModels.OperationError;
import cl.duoc.bancoxyz.payment.PaymentModels.OperationResult;
import cl.duoc.bancoxyz.payment.PaymentModels.WithdrawalResult;

@Component
public class CoreOperationClient {
    private final RestClient rest;
    private final OAuth2AuthorizedClientManager tokens;
    private final OAuth2AuthorizedClientService clients;

    public CoreOperationClient(RestClient.Builder builder, OAuth2AuthorizedClientManager tokens,
            OAuth2AuthorizedClientService clients,
            @Value("${payment.core.base-url}") String baseUrl) {
        this.rest = builder.baseUrl(baseUrl).build();
        this.tokens = tokens;
        this.clients = clients;
    }

    public OperationResult execute(UUID operationId, String action, String type, Object command) {
        return request(operationId, type, () -> rest.post()
                .uri("/api/core/internal/operations/{action}", action)
                .headers(headers -> headers.setBearerAuth(internalToken()))
                .body(command).retrieve().body(OperationResult.class));
    }

    public OperationResult operation(UUID operationId) {
        return request(operationId, null, () -> rest.get()
                .uri("/api/core/internal/operations/{operationId}", operationId)
                .headers(headers -> headers.setBearerAuth(internalToken()))
                .retrieve().body(OperationResult.class));
    }

    public WithdrawalResult withdraw(WithdrawalRequestedEvent requested) {
        return withdrawalResult(requested.transactionId(), requested, () -> rest.post()
                .uri("/api/core/internal/withdrawals")
                .headers(headers -> headers.setBearerAuth(internalToken()))
                .body(requested).retrieve().body(WithdrawalResult.class));
    }

    public WithdrawalResult withdrawal(UUID id, WithdrawalRequestedEvent requested) {
        return withdrawalResult(id, requested, () -> rest.get()
                .uri("/api/core/internal/withdrawals/{transactionId}", id)
                .headers(headers -> headers.setBearerAuth(internalToken()))
                .retrieve().body(WithdrawalResult.class));
    }

    private WithdrawalResult withdrawalResult(UUID id, WithdrawalRequestedEvent requested,
            Supplier<WithdrawalResult> call) {
        WithdrawalResult result = request(id, call);
        if (result != null && "COMPLETED".equals(result.status()) && result.completed() != null
                && result.rejected() == null) {
            WithdrawalCompletedEvent event = result.completed();
            if (id.equals(event.transactionId()) && requested.accountId().equals(event.accountId())
                    && event.amount() != null && requested.amount().compareTo(event.amount()) == 0
                    && event.eventId() != null && event.occurredAt() != null && event.finalBalance() != null) {
                return result;
            }
        }
        if (result != null && "REJECTED".equals(result.status()) && result.rejected() != null
                && result.completed() == null) {
            WithdrawalRejectedEvent event = result.rejected();
            if (id.equals(event.transactionId()) && requested.accountId().equals(event.accountId())
                    && event.amount() != null && requested.amount().compareTo(event.amount()) == 0
                    && event.eventId() != null && event.occurredAt() != null && event.reason() != null) {
                return result;
            }
        }
        throw uncertain(id, "Core no entrego el evento original definitivo del retiro");
    }

    private String internalToken() {
        OAuth2AuthorizedClient client = tokens.authorize(OAuth2AuthorizeRequest
                .withClientRegistrationId("core-operations").principal("payment-service").build());
        if (client == null) {
            throw new OAuth2AuthorizationException(new OAuth2Error("internal_token_unavailable"));
        }
        return client.getAccessToken().getTokenValue();
    }

    private OperationResult request(UUID id, String expectedType, Supplier<OperationResult> call) {
        OperationResult result = request(id, call);
        if (result == null || !id.equals(result.operationId())
                || !("DEBIT".equals(result.type()) || "CREDIT".equals(result.type())
                        || "TRANSFER".equals(result.type()))
                || expectedType != null && !expectedType.equals(result.type())
                || !("COMPLETED".equals(result.status()) || "REJECTED".equals(result.status()))
                || result.completedAt() == null) {
            throw uncertain(id, "Core no entrego un resultado definitivo valido");
        }
        return result;
    }

    private <T> T request(UUID id, Supplier<T> call) {
        try {
            return call.get();
        } catch (RestClientResponseException error) {
            int status = error.getStatusCode().value();
            if (status == 400 || status == 404 || status == 409) {
                String state = status == 400 ? "INVALID_REQUEST" : status == 404 ? "NOT_FOUND" : "CONFLICT";
                String message = status == 400 ? "Core rechazo los datos de la solicitud"
                        : status == 404 ? "Operacion no encontrada en Core"
                        : "El identificador esta en conflicto; revise la solicitud original";
                throw new CoreRequestException(HttpStatus.valueOf(status), new OperationError(id, state, message));
            }
            if (status == 401 || status == 403) {
                // La proxima recuperacion pedira un token nuevo, sin repetir el comando actual.
                clients.removeAuthorizedClient("core-operations", "payment-service");
                throw new CoreRequestException(HttpStatus.BAD_GATEWAY, new OperationError(id, "UNKNOWN",
                        "Core rechazo la credencial interna; conserve el identificador y consulte su resultado"));
            }
            throw uncertain(id, "Core no pudo entregar el resultado; consulte con el mismo identificador");
        } catch (OAuth2AuthorizationException error) {
            throw uncertain(id, "No se pudo obtener el token interno; conserve el identificador");
        } catch (RestClientException error) {
            // La perdida de la respuesta puede ocurrir despues del commit de Core.
            throw uncertain(id, "Resultado incierto; consulte o reenvie los mismos datos con el mismo identificador");
        }
    }

    private CoreRequestException uncertain(UUID id, String message) {
        return new CoreRequestException(HttpStatus.SERVICE_UNAVAILABLE, new OperationError(id, "UNKNOWN", message));
    }

    public static class CoreRequestException extends RuntimeException {
        private final HttpStatus status;
        private final OperationError result;

        public CoreRequestException(HttpStatus status, OperationError result) {
            super(result.message());
            this.status = status;
            this.result = result;
        }

        public HttpStatus status() {
            return status;
        }

        public OperationError result() {
            return result;
        }
    }
}
