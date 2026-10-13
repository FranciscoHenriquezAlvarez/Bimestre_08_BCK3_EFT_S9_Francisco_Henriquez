package cl.duoc.bancoxyz.core;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@Component
public class CustomerAccountClient {
    private final RestClient client;

    public CustomerAccountClient(@Qualifier("customerRestClientBuilder") RestClient.Builder builder) {
        this.client = builder.baseUrl("http://customer-service").build();
    }

    public void requireExistingCustomer(Integer id, String bearer) {
        if (id == null || id <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El cliente debe tener un identificador positivo");
        }
        if (bearer == null || bearer.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Se requiere un token validado");
        }
        try {
            client.get().uri("/api/customers/{id}", id).headers(headers -> headers.setBearerAuth(bearer))
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 404) {
                            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cliente no encontrado");
                        }
                        if (status == 401 || status == 403) {
                            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Clientes rechazo la autorizacion");
                        }
                        if (status != 200) {
                            throw unavailable();
                        }
                        CustomerIdentity customer = response.bodyTo(CustomerIdentity.class);
                        if (customer == null || !Objects.equals(id, customer.customerId())
                                || blank(customer.firstName()) || blank(customer.lastName()) || blank(customer.email())
                                || !("ESTANDAR".equals(customer.profile()) || "PREFERENTE".equals(customer.profile()))) {
                            throw unavailable();
                        }
                        return null;
                    });
        } catch (RestClientException error) {
            // No incorpora el cuerpo remoto ni las credenciales al error publico.
            throw unavailable();
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "No fue posible validar el cliente temporalmente");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CustomerIdentity(Integer customerId, String firstName, String lastName, String email, String profile) {
    }
}
