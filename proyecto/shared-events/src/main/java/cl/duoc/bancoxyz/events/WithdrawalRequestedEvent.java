package cl.duoc.bancoxyz.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

// Representa la solicitud de retiro enviada al Core Banking.
public record WithdrawalRequestedEvent(UUID eventId, UUID transactionId, Integer accountId,
        BigDecimal amount, Instant occurredAt) {
}
