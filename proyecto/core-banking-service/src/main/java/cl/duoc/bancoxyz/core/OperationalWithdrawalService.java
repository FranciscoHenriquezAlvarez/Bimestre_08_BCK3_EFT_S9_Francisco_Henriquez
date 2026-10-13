package cl.duoc.bancoxyz.core;

import cl.duoc.bancoxyz.core.AccountOperationService.OperationRequest;
import cl.duoc.bancoxyz.core.AccountOperationService.OperationResult;
import cl.duoc.bancoxyz.core.AccountOperationService.OperationType;
import cl.duoc.bancoxyz.events.WithdrawalCompletedEvent;
import cl.duoc.bancoxyz.events.WithdrawalRejectedEvent;
import cl.duoc.bancoxyz.events.WithdrawalRequestedEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OperationalWithdrawalService {
    private static final BigDecimal ATM_MULTIPLE = new BigDecimal("10000");
    private final AccountOperationService operations;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public OperationalWithdrawalService(AccountOperationService operations, JdbcTemplate jdbc, ObjectMapper json) {
        this.operations = operations;
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional
    public WithdrawalResult process(WithdrawalRequestedEvent requested) {
        validateRequiredFields(requested);
        StoredWithdrawal stored = find(requested.transactionId());
        if (stored != null) {
            return originalResult(requested, stored);
        }
        OperationRequest command = new OperationRequest(requested.transactionId(), OperationType.DEBIT,
                requested.accountId(), null, requested.amount());
        String rejection = atmRejection(requested.amount());
        OperationResult operation = rejection == null ? operations.execute(command) : operations.reject(command, rejection);

        // El registro monetario serializa tambien solicitudes concurrentes con el mismo transactionId.
        stored = find(requested.transactionId());
        if (stored != null) {
            return originalResult(requested, stored);
        }
        if (rejection != null && "COMPLETED".equals(operation.status())) {
            throw conflict("El identificador pertenece a una operacion incompatible con las reglas ATM");
        }
        boolean completed = "COMPLETED".equals(operation.status());
        Instant completedAt = operation.completedAt();
        jdbc.update("""
                INSERT INTO withdrawal_transactions
                    (transaction_id, account_id, amount, status, final_balance, rejection_reason, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, requested.transactionId(), requested.accountId(), requested.amount(), operation.status(),
                completed ? operation.sourceBalance() : null, operation.rejectionReason(),
                Timestamp.from(requested.occurredAt()), Timestamp.from(completedAt));
        saveEvent(requested.eventId(), requested.transactionId(), requested.accountId(), "WithdrawalRequested",
                requested.amount(), requested.occurredAt(), requested);
        UUID resultId = UUID.randomUUID();
        if (completed) {
            var event = new WithdrawalCompletedEvent(resultId, requested.transactionId(), requested.accountId(),
                    requested.amount(), operation.sourceBalance(), completedAt);
            saveEvent(event.eventId(), event.transactionId(), event.accountId(), "WithdrawalCompleted",
                    event.amount(), event.occurredAt(), event);
        } else {
            var event = new WithdrawalRejectedEvent(resultId, requested.transactionId(), requested.accountId(),
                    requested.amount(), operation.rejectionReason(), completedAt);
            saveEvent(event.eventId(), event.transactionId(), event.accountId(), "WithdrawalRejected",
                    event.amount(), event.occurredAt(), event);
        }
        // Si el estado o el evento no pueden guardarse, la misma transaccion revierte el debito.
        return result(requested.transactionId());
    }

    public WithdrawalResult result(UUID transactionId) {
        StoredWithdrawal stored = find(transactionId);
        if (stored == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Retiro no encontrado");
        }
        return result(stored, transactionId);
    }

    private WithdrawalResult originalResult(WithdrawalRequestedEvent requested, StoredWithdrawal stored) {
        if (!stored.accountId().equals(requested.accountId()) || stored.amount().compareTo(requested.amount()) != 0) {
            throw conflict("El identificador ya pertenece a otro retiro");
        }
        // eventId y occurredAt describen el envio; se conserva el primer resultado monetario.
        return result(stored, requested.transactionId());
    }

    private WithdrawalResult result(StoredWithdrawal stored, UUID id) {
        String type = switch (stored.status()) {
            case "COMPLETED" -> "WithdrawalCompleted";
            case "REJECTED" -> "WithdrawalRejected";
            default -> throw conflict("El retiro requiere revisar su estado pendiente");
        };
        List<String> payloads = jdbc.query("""
                SELECT payload::text FROM banking_events
                WHERE transaction_id=? AND event_type IN ('WithdrawalCompleted', 'WithdrawalRejected')
                """, (rs, row) -> rs.getString(1), id);
        if (payloads.size() != 1) {
            throw conflict("El retiro requiere revisar su evento persistido");
        }
        try {
            if ("WithdrawalCompleted".equals(type)) {
                var event = json.readValue(payloads.get(0), WithdrawalCompletedEvent.class);
                if (!id.equals(event.transactionId()) || !stored.accountId().equals(event.accountId())
                        || event.amount() == null || stored.amount().compareTo(event.amount()) != 0
                        || event.finalBalance() == null || stored.finalBalance() == null
                        || stored.finalBalance().compareTo(event.finalBalance()) != 0
                        || event.eventId() == null || event.occurredAt() == null) {
                    throw conflict("El resultado persistido del retiro no es consistente");
                }
                return new WithdrawalResult(stored.status(), event, null);
            }
            var event = json.readValue(payloads.get(0), WithdrawalRejectedEvent.class);
            if (!id.equals(event.transactionId()) || !stored.accountId().equals(event.accountId())
                    || event.amount() == null || stored.amount().compareTo(event.amount()) != 0
                    || !java.util.Objects.equals(stored.reason(), event.reason())
                    || event.reason() == null || event.eventId() == null || event.occurredAt() == null) {
                throw conflict("El resultado persistido del retiro no es consistente");
            }
            return new WithdrawalResult(stored.status(), null, event);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("No fue posible recuperar el evento persistido", error);
        }
    }

    private StoredWithdrawal find(UUID id) {
        List<StoredWithdrawal> rows = jdbc.query("""
                SELECT account_id, amount, status, final_balance, rejection_reason
                FROM withdrawal_transactions WHERE transaction_id=?
                """, (rs, row) -> new StoredWithdrawal(rs.getInt("account_id"), rs.getBigDecimal("amount"),
                rs.getString("status"), rs.getBigDecimal("final_balance"), rs.getString("rejection_reason")), id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void validateRequiredFields(WithdrawalRequestedEvent requested) {
        if (requested == null || requested.eventId() == null || requested.transactionId() == null
                || requested.accountId() == null || requested.amount() == null || requested.occurredAt() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La solicitud de retiro esta incompleta");
        }
    }

    private String atmRejection(BigDecimal amount) {
        if (amount.signum() <= 0) {
            return "El monto debe ser mayor a cero";
        }
        return amount.remainder(ATM_MULTIPLE).signum() == 0 ? null
                : "El cajero solo permite retiros en multiplos de 10000";
    }

    private void saveEvent(UUID eventId, UUID transactionId, Integer accountId, String type, BigDecimal amount,
            Instant occurredAt, Object event) {
        try {
            jdbc.update("""
                    INSERT INTO banking_events
                        (event_id, transaction_id, account_id, event_type, amount, occurred_at, payload)
                    VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                    """, eventId, transactionId, accountId, type, amount, Timestamp.from(occurredAt),
                    json.writeValueAsString(event));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("No fue posible guardar el evento de retiro", error);
        }
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    public record WithdrawalResult(String status, WithdrawalCompletedEvent completed, WithdrawalRejectedEvent rejected) {
    }

    private record StoredWithdrawal(Integer accountId, BigDecimal amount, String status, BigDecimal finalBalance,
            String reason) {
    }
}
