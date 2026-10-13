package cl.duoc.bancoxyz.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

// Representa el resultado exitoso de un retiro procesado.
public record WithdrawalCompletedEvent(UUID eventId, UUID transactionId, Integer accountId,
        BigDecimal amount, BigDecimal finalBalance, Instant occurredAt) {
}
