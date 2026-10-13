package cl.duoc.bancoxyz.processor;

import cl.duoc.bancoxyz.exception.InvalidDataException;
import cl.duoc.bancoxyz.model.AnnualMovement;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Locale;
import org.springframework.batch.item.ItemProcessor;

// Valida y transforma los movimientos anuales antes de crear el informe
public class AnnualMovementProcessor implements ItemProcessor<AnnualMovement, AnnualMovement> {
    private static final List<DateTimeFormatter> LEGACY_DATE_FORMATTERS = List.of(
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("dd-MM-uuuu").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("uuuu/MM/dd").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT));

    @Override
    public AnnualMovement process(AnnualMovement item) {
        try {
            int accountId = Integer.parseInt(required(item.getCuentaId(), "cuenta_id"));
            LocalDate date = parseLegacyDate(item.getFecha());
            String transaction = required(item.getTransaccion(), "transaccion").toLowerCase(Locale.ROOT);
            BigDecimal amount = new BigDecimal(required(item.getMonto(), "monto"));
            String description = required(item.getDescripcion(), "descripcion");

            // Rechaza montos cero y tipos de movimiento no reconocidos
            if (amount.signum() == 0) {
                throw new InvalidDataException("El movimiento de cuenta " + accountId + " tiene monto cero");
            }
            if (!transaction.equals("deposito") && !transaction.equals("retiro") && !transaction.equals("compra")) {
                throw new InvalidDataException("Tipo de movimiento no reconocido: " + transaction);
            }
            // Comprueba que el signo del monto sea coherente con el movimiento
            if (transaction.equals("deposito") && amount.signum() < 0
                    || (!transaction.equals("deposito") && amount.signum() > 0)) {
                throw new InvalidDataException("Monto incoherente con el tipo de movimiento de cuenta " + accountId);
            }

            item.setAccountId(accountId);
            item.setMovementDate(date);
            item.setTransaction(transaction);
            item.setAmount(amount);
            item.setDescription(description);
            return item;
        } catch (InvalidDataException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new InvalidDataException("Movimiento anual con formato inválido: " + ex.getMessage());
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
