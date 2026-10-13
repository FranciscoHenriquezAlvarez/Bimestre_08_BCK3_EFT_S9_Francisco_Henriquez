package cl.duoc.bancoxyz.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.util.UUID;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.http.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WebServicesClient {
    private final RestClient core;
    private final RestClient customers;
    private final RestClient payments;
    private final CircuitBreakerFactory<?, ?> breakers;

    public WebServicesClient(RestClient.Builder builder, CircuitBreakerFactory<?, ?> breakers) {
        core = builder.clone().baseUrl("http://core-banking-service").build();
        customers = builder.clone().baseUrl("http://customer-service").build();
        payments = builder.clone().baseUrl("http://payment-service").build();
        this.breakers = breakers;
    }

    public ResponseEntity<JsonNode> openAccount(JsonNode body) {
        return request(core, "coreWeb", HttpMethod.POST, "/api/core/accounts", body, null, "accountId", "/api/bff/web/accounts/");
    }

    public ResponseEntity<JsonNode> updateAccount(Integer id, JsonNode body) {
        return request(core, "coreWeb", HttpMethod.PUT, "/api/core/accounts/" + id, body, null, null, null);
    }

    public ResponseEntity<JsonNode> closeAccount(Integer id) {
        return request(core, "coreWeb", HttpMethod.POST, "/api/core/accounts/" + id + "/close", null, null, null, null);
    }

    public ResponseEntity<JsonNode> createCustomer(JsonNode body) {
        return request(customers, "customerWeb", HttpMethod.POST, "/api/customers", body, null, "customerId", "/api/bff/web/customers/");
    }

    public ResponseEntity<JsonNode> customer(Integer id) {
        return request(customers, "customerWeb", HttpMethod.GET, "/api/customers/" + id, null, null, null, null);
    }

    public ResponseEntity<JsonNode> updateCustomer(Integer id, JsonNode body) {
        return request(customers, "customerWeb", HttpMethod.PUT, "/api/customers/" + id, body, null, null, null);
    }

    public ResponseEntity<JsonNode> profile(Integer id) {
        return request(customers, "customerWeb", HttpMethod.GET, "/api/customers/" + id + "/profile", null, null, null, null);
    }

    public ResponseEntity<JsonNode> updateProfile(Integer id, JsonNode body) {
        return request(customers, "customerWeb", HttpMethod.PUT, "/api/customers/" + id + "/profile", body, null, null, null);
    }

    public ResponseEntity<JsonNode> payment(JsonNode body) {
        return monetary("/api/payments", body);
    }

    public ResponseEntity<JsonNode> deposit(JsonNode body) {
        return monetary("/api/payments/deposits", body);
    }

    public ResponseEntity<JsonNode> transfer(JsonNode body) {
        return monetary("/api/payments/transfers", body);
    }

    public ResponseEntity<JsonNode> operation(UUID id) {
        return request(payments, "paymentWeb", HttpMethod.GET, "/api/payments/operations/" + id, null, id, null, null);
    }

    private ResponseEntity<JsonNode> monetary(String path, JsonNode body) {
        UUID id;
        try {
            id = UUID.fromString(body.path("operationId").asText());
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El UUID de operacion es obligatorio y debe ser valido");
        }
        return request(payments, "paymentWeb", HttpMethod.POST, path, body, id, null, null);
    }

    private ResponseEntity<JsonNode> request(RestClient service, String circuit, HttpMethod method, String path,
            JsonNode body, UUID operationId, String createdId, String publicLocation) {
        var request = service.method(method).uri(path);
        // Captura el Bearer antes de que el circuito cambie el hilo de ejecucion.
        if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken jwt) {
            request.headers(headers -> headers.setBearerAuth(jwt.getToken().getTokenValue()));
        }
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).body(body);
        return breakers.create(circuit).run(() -> request.exchange((sent, response) -> {
            int status = response.getStatusCode().value();
            // Estos errores no representan indisponibilidad y no abren el circuito.
            if (status == 400 || status == 404 || status == 409 || status == 401 || status == 403) {
                return ResponseEntity.status(status).body(error(status, operationId));
            }
            if (status != (createdId == null ? 200 : 201)) throw new RestClientException("Respuesta HTTP inesperada");
            JsonNode result = response.bodyTo(JsonNode.class);
            if (result == null || !result.isObject()) throw new RestClientException("Respuesta incompleta");
            if (operationId != null && (!operationId.toString().equals(result.path("operationId").asText())
                    || !("COMPLETED".equals(result.path("status").asText()) || "REJECTED".equals(result.path("status").asText())))) {
                throw new RestClientException("Resultado monetario no confirmado");
            }
            if (createdId != null) {
                JsonNode id = result.path(createdId);
                if (!id.isIntegralNumber() || !id.canConvertToInt() || id.intValue() <= 0) throw new RestClientException("Identificador no confirmado");
                return ResponseEntity.created(URI.create(publicLocation + id.intValue())).body(result);
            }
            return ResponseEntity.ok(result);
        }), failure -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(error(503, operationId)));
    }

    private JsonNode error(int status, UUID id) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
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
}
