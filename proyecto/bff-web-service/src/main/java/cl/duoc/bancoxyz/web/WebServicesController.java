package cl.duoc.bancoxyz.web;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/bff/web")
public class WebServicesController {
    private final WebServicesClient services;

    public WebServicesController(WebServicesClient services) {
        this.services = services;
    }

    @PostMapping("/accounts")
    public ResponseEntity<JsonNode> open(@RequestBody JsonNode body) { return services.openAccount(body); }

    @PutMapping("/accounts/{id}")
    public ResponseEntity<JsonNode> updateAccount(@PathVariable Integer id, @RequestBody JsonNode body) { return services.updateAccount(id, body); }

    @PostMapping("/accounts/{id}/close")
    public ResponseEntity<JsonNode> close(@PathVariable Integer id) { return services.closeAccount(id); }

    @PostMapping("/customers")
    public ResponseEntity<JsonNode> createCustomer(@RequestBody JsonNode body) { return services.createCustomer(body); }

    @GetMapping("/customers/{id}")
    public ResponseEntity<JsonNode> customer(@PathVariable Integer id) { return services.customer(id); }

    @PutMapping("/customers/{id}")
    public ResponseEntity<JsonNode> updateCustomer(@PathVariable Integer id, @RequestBody JsonNode body) { return services.updateCustomer(id, body); }

    @GetMapping("/customers/{id}/profile")
    public ResponseEntity<JsonNode> profile(@PathVariable Integer id) { return services.profile(id); }

    @PutMapping("/customers/{id}/profile")
    public ResponseEntity<JsonNode> updateProfile(@PathVariable Integer id, @RequestBody JsonNode body) { return services.updateProfile(id, body); }

    @PostMapping("/payments")
    public ResponseEntity<JsonNode> payment(@RequestBody JsonNode body) { return services.payment(body); }

    @PostMapping("/payments/deposits")
    public ResponseEntity<JsonNode> deposit(@RequestBody JsonNode body) { return services.deposit(body); }

    @PostMapping("/payments/transfers")
    public ResponseEntity<JsonNode> transfer(@RequestBody JsonNode body) { return services.transfer(body); }

    @GetMapping("/payments/operations/{operationId}")
    public ResponseEntity<JsonNode> operation(@PathVariable UUID operationId) { return services.operation(operationId); }
}
