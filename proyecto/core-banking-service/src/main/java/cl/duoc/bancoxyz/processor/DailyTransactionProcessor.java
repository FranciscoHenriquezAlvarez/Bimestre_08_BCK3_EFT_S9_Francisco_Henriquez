package cl.duoc.bancoxyz.processor;

import cl.duoc.bancoxyz.exception.InvalidDataException;
import cl.duoc.bancoxyz.model.DailyTransaction;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

// Valida y transforma las transacciones diarias antes de guardarlas
public class DailyTransactionProcessor implements ItemProcessor<DailyTransaction, DailyTransaction> {
    private static final List<DateTimeFormatter> LEGACY_DATE_FORMATTERS = List.of(
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("dd-MM-uuuu").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("uuuu/MM/dd").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT));
    private final JdbcTemplate jdbcTemplate;
    // Guarda las claves procesadas para detectar duplicados en la misma ejecucion
    private final ConcurrentMap<String, Integer> processedKeys = new ConcurrentHashMap<>();

    public DailyTransactionProcessor(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public DailyTransaction process(DailyTransaction item) {
        try {
            int id = Integer.parseInt(required(item.getId(), "id"));
            LocalDate date = parseLegacyDate(item.getFecha());
            BigDecimal amount = new BigDecimal(required(item.getMonto(), "monto"));
            String type = required(item.getTipo(), "tipo").toLowerCase(Locale.ROOT);

            // Rechaza montos no positivos y tipos que no pertenecen al proceso
            if (amount.signum() <= 0) {
                throw new InvalidDataException("La transacción " + id + " tiene monto no positivo");
            }
            if (!type.equals("debito") && !type.equals("credito")) {
                throw new InvalidDataException("La transacción " + id + " tiene tipo no reconocido");
            }
            String duplicateKey = date + "|" + amount.stripTrailingZeros().toPlainString() + "|" + type;
            // Detecta transacciones duplicadas ya persistidas con los mismos datos principales
            Integer duplicates = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM transacciones_procesadas
                    WHERE fecha = ? AND monto = ? AND tipo = ? AND id <> ?
                    """, Integer.class, date, amount, type, id);
            if (duplicates != null && duplicates > 0) {
                throw new InvalidDataException("La transacción " + id + " está duplicada según fecha, monto y tipo");
            }
            Integer previousId = processedKeys.putIfAbsent(duplicateKey, id);
            if (previousId != null && !previousId.equals(id)) {
                throw new InvalidDataException("La transacción " + id + " está duplicada en la misma ejecución");
            }

            item.setTransactionId(id);
            item.setTransactionDate(date);
            item.setAmount(amount);
            item.setTransactionType(type);
            return item;
        } catch (InvalidDataException ex) {
            throw ex;
        // Mantiene el error temporal para que Spring Batch pueda reintentarlo
        } catch (TransientDataAccessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new InvalidDataException("Transacción con formato inválido: " + ex.getMessage());
        }
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new InvalidDataException("Campo requerido vacío: " + field);
        }
        return value.trim();
    }

    private LocalDate parseLegacyDate(String value) {
        String dateValue = required(value, "fecha");
        for (DateTimeFormatter formatter : LEGACY_DATE_FORMATTERS) {
            try {
                return LocalDate.parse(dateValue, formatter);
            } catch (DateTimeParseException ignored) {
                // Prueba el siguiente formato legacy permitido.
            }
        }
        throw new InvalidDataException("Fecha legacy invalida: " + dateValue);
    }
}
