package cl.duoc.bancoxyz.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

// Representa el rechazo de un retiro procesado.
public record WithdrawalRejectedEvent(UUID eventId, UUID transactionId, Integer accountId,
        BigDecimal amount, String reason, Instant occurredAt) {
}
