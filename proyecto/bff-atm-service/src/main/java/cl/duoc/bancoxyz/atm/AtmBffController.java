package cl.duoc.bancoxyz.atm;

import cl.duoc.bancoxyz.atm.kafka.AsyncWithdrawalProducer;
import cl.duoc.bancoxyz.atm.kafka.KafkaPublicationException;
import cl.duoc.bancoxyz.events.WithdrawalRequestedEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

// Publica retiros ATM y consulta su procesamiento asincrono.
@RestController
@RequestMapping("/api/bff/atm/accounts")
public class AtmBffController {
    private final AtmBankingClient core;
    private final AsyncWithdrawalProducer producer;

    public AtmBffController(AtmBankingClient core, AsyncWithdrawalProducer producer) {
        this.core = core;
        this.producer = producer;
    }

    @GetMapping("/{id}/balance")
    public AtmBankingClient.Balance balance(@PathVariable Integer id) {
        return core.balance(id);
    }

    @PostMapping("/{id}/withdraw")
    public ResponseEntity<AsyncWithdrawalAccepted> withdraw(@PathVariable Integer id,
            @RequestBody WithdrawRequest request) {
        UUID transactionId = UUID.randomUUID();
        producer.publish(new WithdrawalRequestedEvent(UUID.randomUUID(), transactionId, id, request.amount(),
                Instant.now()));
        return ResponseEntity.accepted().body(new AsyncWithdrawalAccepted(transactionId, "PENDING"));
    }

    @GetMapping("/transactions/{transactionId}")
    public AtmBankingClient.AsyncWithdrawalStatus transaction(@PathVariable UUID transactionId) {
        return core.asyncStatus(transactionId);
    }

    public record WithdrawRequest(BigDecimal amount) {
    }

    public record AsyncWithdrawalAccepted(UUID transactionId, String status) {
    }

    @ExceptionHandler(KafkaPublicationException.class)
    public ResponseEntity<AsyncWithdrawalNotPublished> publicationFailed(KafkaPublicationException error) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new AsyncWithdrawalNotPublished(error.getTransactionId(), "NOT_PUBLISHED", error.getMessage()));
    }

    public record AsyncWithdrawalNotPublished(UUID transactionId, String status, String message) {
    }
}
