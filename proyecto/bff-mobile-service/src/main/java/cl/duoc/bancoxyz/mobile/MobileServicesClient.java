package cl.duoc.bancoxyz.mobile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.UUID;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.http.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Service
public class MobileServicesClient {
    private final RestClient customers;
    private final RestClient payments;
    private final CircuitBreakerFactory<?, ?> breakers;

    public MobileServicesClient(RestClient.Builder builder, CircuitBreakerFactory<?, ?> breakers) {
        customers = builder.clone().baseUrl("http://customer-service").build();
        payments = builder.clone().baseUrl("http://payment-service").build();
        this.breakers = breakers;
    }

    public ResponseEntity<?> customer(Integer id) {
        var response = request(customers, "customerMobile", "/api/customers/" + id, null, id);
        if (!response.getStatusCode().is2xxSuccessful()) return response;
        JsonNode body = response.getBody();
        return ResponseEntity.ok(new Customer(id, body.path("firstName").asText() + " " + body.path("lastName").asText(),
                body.path("profile").asText()));
    }

    public ResponseEntity<?> operation(UUID id) {
        var response = request(payments, "paymentMobile", "/api/payments/operations/" + id, id, null);
        if (!response.getStatusCode().is2xxSuccessful()) return response;
        JsonNode body = response.getBody();
        return ResponseEntity.ok(new Operation(id, body.path("type").asText(), body.path("status").asText(),
                body.path("rejectionReason").isTextual() ? body.path("rejectionReason").asText() : null));
    }

    private ResponseEntity<JsonNode> request(RestClient service, String circuit, String path, UUID operationId, Integer customerId) {
        var request = service.get().uri(path);
        // El token se captura antes de entrar al circuito; no se solicitan credenciales internas.
        if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken jwt) {
            request.headers(headers -> headers.setBearerAuth(jwt.getToken().getTokenValue()));
        }
        return breakers.create(circuit).run(() -> request.exchange((sent, response) -> {
            int status = response.getStatusCode().value();
            if (status == 400 || status == 404 || status == 409 || status == 401 || status == 403) {
                return ResponseEntity.status(status).body(error(status, operationId));
            }
            if (status != 200) throw new RestClientException("Respuesta HTTP inesperada");
            JsonNode body = response.bodyTo(JsonNode.class);
            if (body == null || !body.isObject()) throw new RestClientException("Respuesta incompleta");
            if (customerId != null && (!body.path("customerId").isIntegralNumber() || !body.path("customerId").canConvertToInt() || body.path("customerId").intValue() != customerId
                    || !body.path("firstName").isTextual() || !body.path("lastName").isTextual() || !body.path("profile").isTextual())) {
                throw new RestClientException("Cliente no confirmado");
            }
            if (operationId != null && (!operationId.toString().equals(body.path("operationId").asText())
                    || !("COMPLETED".equals(body.path("status").asText()) || "REJECTED".equals(body.path("status").asText()))
                    || !body.path("type").isTextual())) throw new RestClientException("Resultado monetario no confirmado");
            return ResponseEntity.ok(body);
        }), failure -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(error(503, operationId)));
    }

    private JsonNode error(int status, UUID id) {
        var result = JsonNodeFactory.instance.objectNode();
        if (id != null) {
            result.put("operationId", id.toString());
            result.put("status", switch (status) {
                case 400 -> "INVALID_REQUEST";
                case 404 -> "NOT_FOUND";
                case 409 -> "CONFLICT";
                case 401, 403 -> "UNAUTHORIZED";
                default -> "UNKNOWN";
            });
        } else result.put("status", status);
        result.put("message", status == 503 && id != null
                ? "Resultado incierto; conserve el UUID y consulte la operacion"
                : status == 503 ? "Servicio temporalmente no disponible" : "El servicio rechazo la solicitud");
        return result;
    }

    public record Customer(Integer customerId, String name, String profile) {
    }

    public record Operation(UUID operationId, String type, String status, String rejectionReason) {
    }
}
