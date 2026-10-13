package cl.duoc.bancoxyz.payment;

import cl.duoc.bancoxyz.events.WithdrawalCompletedEvent;
import cl.duoc.bancoxyz.events.WithdrawalRejectedEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class PaymentModels {
    private PaymentModels() {
    }

    public record AccountCommand(UUID operationId, Integer accountId, BigDecimal amount) {
    }

    public record TransferCommand(UUID operationId, Integer sourceAccountId, Integer targetAccountId,
            BigDecimal amount) {
    }

    public record OperationResult(UUID operationId, String type, String status, BigDecimal sourceBalance,
            BigDecimal targetBalance, String rejectionReason, Instant completedAt) {
    }

    public record OperationError(UUID operationId, String status, String message) {
    }

    public record WithdrawalResult(String status, WithdrawalCompletedEvent completed, WithdrawalRejectedEvent rejected) {
        public Object event() {
            return completed == null ? rejected : completed;
        }
    }
}
