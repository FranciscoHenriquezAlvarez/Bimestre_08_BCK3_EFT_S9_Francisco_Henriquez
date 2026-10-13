package cl.duoc.bancoxyz.core;

import cl.duoc.bancoxyz.core.AccountManagementModels.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AccountManagementService {
    private final JdbcTemplate jdbc;
    private final CustomerAccountClient customers;

    public AccountManagementService(JdbcTemplate jdbc, CustomerAccountClient customers) {
        this.jdbc = jdbc;
        this.customers = customers;
    }

    @Transactional
    public ManagedAccount open(OpenAccountRequest request, String bearer) {
        if (request == null || request.customerId() == null || request.customerId() <= 0) {
            throw badRequest("El cliente debe tener un identificador positivo");
        }
        UpdateAccountRequest data = validate(new UpdateAccountRequest(request.accountName(), request.accountType(), request.interestRate()));
        customers.requireExistingCustomer(request.customerId(), bearer);
        int id = nextAccountId();
        return jdbc.queryForObject("""
                INSERT INTO cuentas (cuenta_id, cliente_id, nombre, tipo, saldo_inicial, tasa, saldo_operativo, estado)
                VALUES (?, ?, ?, ?, 0, ?, 0, 'ACTIVA') RETURNING *
                """, this::account, id, request.customerId(), data.accountName(), data.accountType(), data.interestRate());
    }

    @Transactional
    public ManagedAccount update(Integer id, UpdateAccountRequest request) {
        positiveId(id);
        UpdateAccountRequest data = validate(request);
        active(lock(id));
        return jdbc.queryForObject("""
                UPDATE cuentas SET nombre=?, tipo=?, tasa=?, actualizada_en=CURRENT_TIMESTAMP
                WHERE cuenta_id=? RETURNING *
                """, this::account, data.accountName(), data.accountType(), data.interestRate(), id);
    }

    @Transactional
    public ManagedAccount close(Integer id) {
        positiveId(id);
        // Usa la misma fila que contabilidad bloquea antes de cambiar estado o saldo.
        ManagedAccount account = lock(id);
        active(account);
        if (account.finalBalance().signum() != 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La cuenta debe tener saldo operativo cero");
        }
        return jdbc.queryForObject("""
                UPDATE cuentas SET estado='CERRADA', actualizada_en=CURRENT_TIMESTAMP
                WHERE cuenta_id=? RETURNING *
                """, this::account, id);
    }

    private int nextAccountId() {
        // Serializa aperturas y escrituras historicas durante la asignacion del identificador.
        jdbc.execute("LOCK TABLE cuentas, intereses_mensuales, movimientos_anuales IN SHARE ROW EXCLUSIVE MODE");
        Long previous = jdbc.queryForObject("""
                SELECT GREATEST(
                    COALESCE((SELECT MAX(cuenta_id) FROM cuentas), 0),
                    COALESCE((SELECT MAX(cuenta_id) FROM intereses_mensuales), 0),
                    COALESCE((SELECT MAX(cuenta_id) FROM movimientos_anuales), 0),
                    (SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM cuentas_id_seq))
                """, Long.class);
        if (previous == null || previous >= Integer.MAX_VALUE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Se agotaron los identificadores enteros de cuenta");
        }
        jdbc.queryForObject("SELECT setval('cuentas_id_seq', ?, false)", Long.class, previous + 1);
        return jdbc.queryForObject("SELECT nextval('cuentas_id_seq')", Integer.class);
    }

    private ManagedAccount lock(Integer id) {
        List<ManagedAccount> rows = jdbc.query("SELECT * FROM cuentas WHERE cuenta_id=? FOR UPDATE", this::account, id);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cuenta operativa no encontrada");
        }
        return rows.get(0);
    }

    private void active(ManagedAccount account) {
        if (!"ACTIVA".equals(account.state())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La cuenta ya se encuentra cerrada");
        }
    }

    private void positiveId(Integer id) {
        if (id == null || id <= 0) {
            throw badRequest("La cuenta debe tener un identificador positivo");
        }
    }

    private UpdateAccountRequest validate(UpdateAccountRequest request) {
        if (request == null || request.accountName() == null || request.accountName().isBlank()
                || request.accountName().trim().length() > 100 || request.accountType() == null
                || request.interestRate() == null) {
            throw badRequest("Nombre, tipo y tasa son obligatorios y deben ser validos");
        }
        String type = request.accountType().trim().toLowerCase(Locale.ROOT);
        if (!List.of("ahorro", "prestamo").contains(type) || request.interestRate().signum() < 0
                || request.interestRate().compareTo(BigDecimal.ONE) > 0) {
            throw badRequest("El tipo debe ser ahorro o prestamo y la tasa debe estar entre cero y uno");
        }
        try {
            return new UpdateAccountRequest(request.accountName().trim(), type,
                    request.interestRate().setScale(4, RoundingMode.UNNECESSARY));
        } catch (ArithmeticException error) {
            throw badRequest("La tasa admite hasta cuatro decimales");
        }
    }

    private ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    private ManagedAccount account(ResultSet rs, int row) throws SQLException {
        return new ManagedAccount(rs.getInt("cuenta_id"), rs.getObject("cliente_id", Integer.class),
                rs.getString("nombre"), rs.getString("tipo"), rs.getBigDecimal("saldo_inicial"),
                rs.getBigDecimal("tasa"), rs.getBigDecimal("saldo_operativo"), rs.getString("estado"),
                rs.getTimestamp("creada_en").toInstant(), rs.getTimestamp("actualizada_en").toInstant());
    }
}
