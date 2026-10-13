package cl.duoc.bancoxyz.mobile;

import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/bff/mobile")
public class MobileServicesController {
    private final MobileServicesClient services;

    public MobileServicesController(MobileServicesClient services) {
        this.services = services;
    }

    @GetMapping("/customers/{id}")
    public ResponseEntity<?> customer(@PathVariable Integer id) {
        return services.customer(id);
    }

    @GetMapping("/payments/operations/{operationId}")
    public ResponseEntity<?> operation(@PathVariable UUID operationId) {
        return services.operation(operationId);
    }
}
