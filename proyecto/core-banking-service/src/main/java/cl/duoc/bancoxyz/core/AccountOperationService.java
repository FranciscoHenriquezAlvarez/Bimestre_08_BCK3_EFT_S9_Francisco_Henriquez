package cl.duoc.bancoxyz.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AccountOperationService {
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999999.99");
    private final JdbcTemplate jdbc;

    public AccountOperationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public OperationResult execute(OperationRequest request) {
        return process(request, null);
    }

    @Transactional
    public OperationResult reject(OperationRequest request, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("El motivo de rechazo es obligatorio");
        }
        return process(request, reason);
    }

    private OperationResult process(OperationRequest request, String rejectionReason) {
        OperationRequest validated = validate(request);
        // La clave unica serializa solicitudes concurrentes con el mismo identificador.
        int inserted = jdbc.update("""
                INSERT INTO operaciones_cuenta
                    (operacion_id, tipo, cuenta_origen_id, cuenta_destino_id, monto, estado)
                VALUES (?, ?, ?, ?, ?, 'PENDING')
                ON CONFLICT (operacion_id) DO NOTHING
                """, validated.operationId(), validated.type().name(), validated.sourceAccountId(),
                validated.targetAccountId(), validated.amount());
        if (inserted == 0) {
            StoredOperation stored = find(validated.operationId());
            if (!sameRequest(validated, stored.request())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "El identificador ya pertenece a otra solicitud");
            }
            if ("PENDING".equals(stored.result().status())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "La operacion requiere revisar su resultado pendiente");
            }
            return stored.result();
        }

        // Una restriccion del canal tambien reserva el UUID en el registro monetario.
        if (rejectionReason != null) {
            return finish(validated.operationId(), "REJECTED", null, null, rejectionReason);
        }
        if (validated.amount().signum() <= 0) {
            return finish(validated.operationId(), "REJECTED", null, null, "El monto debe ser mayor a cero");
        }

        List<AccountSnapshot> accounts = lockAccounts(validated);
        AccountSnapshot source = account(accounts, validated.sourceAccountId());
        AccountSnapshot target = account(accounts, validated.targetAccountId());
        String rejection = rejection(validated, source, target);
        if (rejection != null) {
            return finish(validated.operationId(), "REJECTED", balance(source), balance(target), rejection);
        }

        BigDecimal sourceBalance = source == null ? null : source.balance().subtract(validated.amount());
        BigDecimal targetBalance = target == null ? null : target.balance().add(validated.amount());
        if (source != null) {
            updateBalance(source.accountId(), sourceBalance);
        }
        if (target != null) {
            updateBalance(target.accountId(), targetBalance);
        }
        return finish(validated.operationId(), "COMPLETED", sourceBalance, targetBalance, null);
    }

    public OperationResult operation(UUID operationId) {
        if (operationId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El identificador es obligatorio");
        }
        return find(operationId).result();
    }

    private OperationRequest validate(OperationRequest request) {
        if (request == null || request.operationId() == null || request.type() == null || request.amount() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La solicitud esta incompleta");
        }
        boolean validAccounts = switch (request.type()) {
            case DEBIT -> request.sourceAccountId() != null && request.targetAccountId() == null;
            case CREDIT -> request.sourceAccountId() == null && request.targetAccountId() != null;
            case TRANSFER -> request.sourceAccountId() != null && request.targetAccountId() != null
                    && !request.sourceAccountId().equals(request.targetAccountId());
        };
        if (!validAccounts || request.amount().abs().compareTo(MAX_AMOUNT) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Las cuentas o el monto no son validos");
        }
        try {
            return new OperationRequest(request.operationId(), request.type(), request.sourceAccountId(),
                    request.targetAccountId(), request.amount().setScale(2, RoundingMode.UNNECESSARY));
        } catch (ArithmeticException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El monto admite hasta dos decimales");
        }
    }

    private List<AccountSnapshot> lockAccounts(OperationRequest request) {
        // El orden estable evita que transferencias opuestas bloqueen cuentas en distinto orden.
        String condition;
        Object[] ids;
        if (request.type() == OperationType.TRANSFER) {
            condition = "cuenta_id IN (?, ?)";
            ids = new Object[] {Math.min(request.sourceAccountId(), request.targetAccountId()),
                    Math.max(request.sourceAccountId(), request.targetAccountId())};
        } else {
            condition = "cuenta_id = ?";
            ids = new Object[] {request.sourceAccountId() == null ? request.targetAccountId() : request.sourceAccountId()};
        }
        return jdbc.query("SELECT cuenta_id, estado, saldo_operativo FROM cuentas WHERE " + condition
                        + " ORDER BY cuenta_id FOR UPDATE",
                (rs, row) -> new AccountSnapshot(rs.getInt("cuenta_id"), rs.getString("estado"),
                        rs.getBigDecimal("saldo_operativo")), ids);
    }

    private String rejection(OperationRequest request, AccountSnapshot source, AccountSnapshot target) {
        if (request.sourceAccountId() != null && source == null) {
            return "Cuenta origen no encontrada";
        }
        if (request.targetAccountId() != null && target == null) {
            return "Cuenta destino no encontrada";
        }
        if (source != null && !"ACTIVA".equals(source.state())
                || target != null && !"ACTIVA".equals(target.state())) {
            return "La cuenta se encuentra cerrada";
        }
        if (source != null && source.balance().compareTo(request.amount()) < 0) {
            return "Saldo insuficiente";
        }
        if (target != null && target.balance().add(request.amount()).compareTo(MAX_AMOUNT) > 0) {
            return "El saldo destino supera el limite permitido";
        }
        return null;
    }

    private AccountSnapshot account(List<AccountSnapshot> accounts, Integer id) {
        return accounts.stream().filter(account -> Objects.equals(account.accountId(), id)).findFirst().orElse(null);
    }

    private BigDecimal balance(AccountSnapshot account) {
        return account == null ? null : account.balance();
    }

    private void updateBalance(Integer accountId, BigDecimal balance) {
        jdbc.update("UPDATE cuentas SET saldo_operativo=?, actualizada_en=CURRENT_TIMESTAMP WHERE cuenta_id=?",
                balance, accountId);
    }

    private OperationResult finish(UUID id, String status, BigDecimal source, BigDecimal target, String reason) {
        jdbc.update("""
                UPDATE operaciones_cuenta
                SET estado=?, saldo_origen=?, saldo_destino=?, motivo_rechazo=?, finalizada_en=CURRENT_TIMESTAMP
                WHERE operacion_id=?
                """, status, source, target, reason, id);
        // Lee el resultado persistido para conservar tambien la precision temporal de PostgreSQL.
        return find(id).result();
    }

    private StoredOperation find(UUID id) {
        List<StoredOperation> operations = jdbc.query("SELECT * FROM operaciones_cuenta WHERE operacion_id=?",
                (rs, row) -> {
                    OperationRequest request = new OperationRequest(rs.getObject("operacion_id", UUID.class),
                            OperationType.valueOf(rs.getString("tipo")),
                            rs.getObject("cuenta_origen_id", Integer.class),
                            rs.getObject("cuenta_destino_id", Integer.class), rs.getBigDecimal("monto"));
                    Timestamp completed = rs.getTimestamp("finalizada_en");
                    OperationResult result = new OperationResult(request.operationId(), request.type(),
                            rs.getString("estado"), rs.getBigDecimal("saldo_origen"), rs.getBigDecimal("saldo_destino"),
                            rs.getString("motivo_rechazo"), completed == null ? null : completed.toInstant());
                    return new StoredOperation(request, result);
                }, id);
        if (operations.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Operacion no encontrada");
        }
        return operations.get(0);
    }

    private boolean sameRequest(OperationRequest requested, OperationRequest stored) {
        return requested.type() == stored.type()
                && Objects.equals(requested.sourceAccountId(), stored.sourceAccountId())
                && Objects.equals(requested.targetAccountId(), stored.targetAccountId())
                && requested.amount().compareTo(stored.amount()) == 0;
    }

    public enum OperationType {
        DEBIT, CREDIT, TRANSFER
    }

    public record OperationRequest(UUID operationId, OperationType type, Integer sourceAccountId,
            Integer targetAccountId, BigDecimal amount) {
    }

    public record OperationResult(UUID operationId, OperationType type, String status, BigDecimal sourceBalance,
            BigDecimal targetBalance, String rejectionReason, Instant completedAt) {
    }

    private record AccountSnapshot(Integer accountId, String state, BigDecimal balance) {
    }

    private record StoredOperation(OperationRequest request, OperationResult result) {
    }
}
