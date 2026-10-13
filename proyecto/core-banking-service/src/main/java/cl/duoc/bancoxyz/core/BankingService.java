package cl.duoc.bancoxyz.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BankingService {
    private final JdbcTemplate jdbc;

    public BankingService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Account account(Integer id) {
        return find(id);
    }

    public WebStatement annualStatement(Integer id) {
        find(id);
        String sql = "SELECT COUNT(*) movement_count, COALESCE(SUM(CASE WHEN monto > 0 THEN monto ELSE 0 END),0) deposits, COALESCE(SUM(CASE WHEN monto < 0 THEN monto ELSE 0 END),0) charges, COALESCE(SUM(monto),0) net FROM movimientos_anuales WHERE cuenta_id=?";
        return jdbc.queryForObject(sql, (rs, n) -> new WebStatement(id, rs.getInt("movement_count"),
                rs.getBigDecimal("deposits"), rs.getBigDecimal("charges"), rs.getBigDecimal("net")), id);
    }

    // El limite se ejecuta en PostgreSQL: no se cargan movimientos adicionales en memoria.
    public List<Movement> latestMovements(Integer id) {
        find(id);
        String sql = "SELECT fecha, transaccion, monto FROM movimientos_anuales WHERE cuenta_id=? ORDER BY fecha DESC, movimiento_id DESC LIMIT 5";
        return jdbc.query(sql, (rs, n) -> new Movement(rs.getObject("fecha", LocalDate.class),
                rs.getString("transaccion"), rs.getBigDecimal("monto")), id);
    }

    private Account find(Integer id) {
        // El saldo vigente pertenece a Cuentas; Batch conserva solamente el resultado historico.
        List<Account> operative = jdbc.query(
                "SELECT cuenta_id,nombre,tipo,saldo_inicial,tasa,saldo_operativo FROM cuentas WHERE cuenta_id=?",
                (rs, n) -> new Account(rs.getInt("cuenta_id"), rs.getString("nombre"), rs.getString("tipo"),
                        rs.getBigDecimal("saldo_inicial"), rs.getBigDecimal("tasa"), rs.getBigDecimal("saldo_operativo")), id);
        if (!operative.isEmpty()) {
            return operative.get(0);
        }
        // Consulta los resultados historicos de Batch cuando no existe una cuenta operativa.
        List<Account> rows = jdbc.query(
                "SELECT cuenta_id,nombre,tipo,saldo_inicial,tasa,saldo_final FROM intereses_mensuales WHERE cuenta_id=?",
                (rs, n) -> new Account(rs.getInt("cuenta_id"), rs.getString("nombre"), rs.getString("tipo"),
                        rs.getBigDecimal("saldo_inicial"), rs.getBigDecimal("tasa"), rs.getBigDecimal("saldo_final")),
                id);
        if (rows.isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cuenta no encontrada");
        return rows.get(0);
    }

    public record Account(Integer accountId, String accountName, String accountType, BigDecimal initialBalance,
            BigDecimal interestRate, BigDecimal finalBalance) {
        public BigDecimal calculatedInterest() {
            return initialBalance.multiply(interestRate).setScale(2, RoundingMode.HALF_UP);
        }
    }

    public record Movement(LocalDate date, String transaction, BigDecimal amount) {
    }

    public record WebStatement(Integer accountId, int movementCount, BigDecimal totalDeposits, BigDecimal totalCharges,
            BigDecimal netBalance) {
    }
}
