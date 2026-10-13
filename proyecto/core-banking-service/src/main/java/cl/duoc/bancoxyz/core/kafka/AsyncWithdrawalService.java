package cl.duoc.bancoxyz.core.kafka;

import cl.duoc.bancoxyz.events.WithdrawalCompletedEvent;
import cl.duoc.bancoxyz.events.WithdrawalRejectedEvent;
import cl.duoc.bancoxyz.events.WithdrawalRequestedEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AsyncWithdrawalService {
    private static final Logger log = LoggerFactory.getLogger(AsyncWithdrawalService.class);
    private static final BigDecimal ATM_WITHDRAWAL_MULTIPLE = new BigDecimal("10000");
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public AsyncWithdrawalService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ProcessingResult process(WithdrawalRequestedEvent requested) {
        // El registro persistente evita descontar dos veces tras reintentos o reinicios.
        if (jdbc.update("INSERT INTO processed_events (event_id, processed_at) VALUES (?, ?) ON CONFLICT DO NOTHING",
                requested.eventId(), Timestamp.from(Instant.now())) == 0) {
            log.warn("[Idempotency] Evento {} ya procesado, ignorando", requested.eventId());
            return ProcessingResult.ignoredDuplicate();
        }

        saveEvent(requested.eventId(), requested.transactionId(), requested.accountId(), "WithdrawalRequested",
                requested.amount(), requested.occurredAt(), requested);
        if (jdbc.update("""
                INSERT INTO withdrawal_transactions (transaction_id, account_id, amount, status, created_at, updated_at)
                VALUES (?, ?, ?, 'PENDING', ?, ?)
                ON CONFLICT (transaction_id) DO NOTHING
                """, requested.transactionId(), requested.accountId(), requested.amount(), Timestamp.from(Instant.now()), Timestamp.from(Instant.now())) == 0) {
            log.warn("[Idempotency] Transaccion {} ya registrada, ignorando", requested.transactionId());
            return ProcessingResult.ignoredDuplicate();
        }

        String rejection = validate(requested);
        if (rejection != null) {
            return reject(requested, rejection);
        }

        BigDecimal current = balance(requested.accountId());
        if (current == null) {
            return reject(requested, "Cuenta no encontrada");
        }
        if (current.compareTo(requested.amount()) < 0) {
            return reject(requested, "Saldo insuficiente");
        }

        BigDecimal finalBalance = current.subtract(requested.amount());
        jdbc.update("UPDATE intereses_mensuales SET saldo_final=? WHERE cuenta_id=?", finalBalance, requested.accountId());
        jdbc.update("UPDATE withdrawal_transactions SET status='COMPLETED', final_balance=?, updated_at=? WHERE transaction_id=?",
                finalBalance, Timestamp.from(Instant.now()), requested.transactionId());
        WithdrawalCompletedEvent completed = new WithdrawalCompletedEvent(UUID.randomUUID(), requested.transactionId(),
                requested.accountId(), requested.amount(), finalBalance, Instant.now());
        saveEvent(completed.eventId(), completed.transactionId(), completed.accountId(), "WithdrawalCompleted",
                completed.amount(), completed.occurredAt(), completed);
        log.info("[Banking] Withdrawal completed accountId={} transactionId={}", requested.accountId(),
                requested.transactionId());
        return ProcessingResult.completed(completed);
    }

    public TransactionStatus asyncWithdrawalStatus(UUID transactionId) {
        List<TransactionStatus> rows = jdbc.query("""
                SELECT transaction_id, account_id, amount, status, final_balance, rejection_reason, updated_at
                FROM withdrawal_transactions WHERE transaction_id=?
                """, (rs, row) -> new TransactionStatus(UUID.fromString(rs.getString("transaction_id")),
                rs.getInt("account_id"), rs.getBigDecimal("amount"), rs.getString("status"),
                rs.getBigDecimal("final_balance"), rs.getString("rejection_reason"),
                rs.getTimestamp("updated_at").toInstant()), transactionId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private ProcessingResult reject(WithdrawalRequestedEvent requested, String reason) {
        jdbc.update("UPDATE withdrawal_transactions SET status='REJECTED', rejection_reason=?, updated_at=? WHERE transaction_id=?",
                reason, Timestamp.from(Instant.now()), requested.transactionId());
        WithdrawalRejectedEvent rejected = new WithdrawalRejectedEvent(UUID.randomUUID(), requested.transactionId(),
                requested.accountId(), requested.amount(), reason, Instant.now());
        saveEvent(rejected.eventId(), rejected.transactionId(), rejected.accountId(), "WithdrawalRejected",
                rejected.amount(), rejected.occurredAt(), rejected);
        log.info("[Banking] Withdrawal rejected accountId={} transactionId={} reason={}", requested.accountId(),
                requested.transactionId(), reason);
        return ProcessingResult.rejected(rejected);
    }

    private String validate(WithdrawalRequestedEvent requested) {
        if (requested.amount() == null || requested.amount().signum() <= 0) {
            return "El monto debe ser mayor a cero";
        }
        if (requested.amount().remainder(ATM_WITHDRAWAL_MULTIPLE).signum() != 0) {
            return "El cajero solo permite retiros en multiplos de 10000";
        }
        return null;
    }

    private BigDecimal balance(Integer accountId) {
        List<BigDecimal> rows = jdbc.query("SELECT saldo_final FROM intereses_mensuales WHERE cuenta_id=?",
                (rs, row) -> rs.getBigDecimal("saldo_final"), accountId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // Guarda hechos inmutables; el saldo operativo sigue siendo una proyeccion.
    private void saveEvent(UUID eventId, UUID transactionId, Integer accountId, String type, BigDecimal amount,
            Instant occurredAt, Object payload) {
        try {
            jdbc.update("""
                    INSERT INTO banking_events (event_id, transaction_id, account_id, event_type, amount, occurred_at, payload)
                    VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                    """, eventId, transactionId, accountId, type, amount, Timestamp.from(occurredAt),
                    objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("No fue posible serializar el evento", exception);
        }
    }

    public record ProcessingResult(Object resultEvent, boolean duplicate) {
        static ProcessingResult completed(WithdrawalCompletedEvent event) {
            return new ProcessingResult(event, false);
        }

        static ProcessingResult rejected(WithdrawalRejectedEvent event) {
            return new ProcessingResult(event, false);
        }

        static ProcessingResult ignoredDuplicate() {
            return new ProcessingResult(null, true);
        }
    }

    public record TransactionStatus(UUID transactionId, Integer accountId, BigDecimal amount, String status,
            BigDecimal finalBalance, String rejectionReason, Instant updatedAt) {
    }
}
