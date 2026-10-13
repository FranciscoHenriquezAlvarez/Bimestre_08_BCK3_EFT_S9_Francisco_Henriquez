package cl.duoc.bancoxyz.processor;

import cl.duoc.bancoxyz.exception.InvalidDataException;
import cl.duoc.bancoxyz.model.InterestAccount;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import org.springframework.batch.item.ItemProcessor;

// Valida las cuentas y calcula su saldo final con la tasa correspondiente
public class MonthlyInterestProcessor implements ItemProcessor<InterestAccount, InterestAccount> {
    private final BigDecimal savingsRate;
    private final BigDecimal loanRate;

    public MonthlyInterestProcessor(BigDecimal savingsRate, BigDecimal loanRate) {
        this.savingsRate = savingsRate;
        this.loanRate = loanRate;
    }

    @Override
    public InterestAccount process(InterestAccount item) {
        try {
            int accountId = Integer.parseInt(required(item.getCuentaId(), "cuenta_id"));
            String name = required(item.getNombre(), "nombre");
            BigDecimal balance = new BigDecimal(required(item.getSaldo(), "saldo"));
            int age = Integer.parseInt(required(item.getEdad(), "edad")); // Se comprueba que el dato legacy sea numerico.
            String type = required(item.getTipo(), "tipo").toLowerCase(Locale.ROOT);

            // Rechaza saldos no positivos antes de aplicar el interes
            if (balance.signum() <= 0) {
                throw new InvalidDataException("La cuenta " + accountId + " no tiene saldo positivo para calcular interés");
            }
            if (age < 18 || age > 100) {
                throw new InvalidDataException("La cuenta " + accountId + " tiene edad fuera del rango permitido");
            }
            BigDecimal rate;
            // Selecciona la tasa segun el tipo de cuenta reconocido
            if (type.equals("ahorro")) {
                rate = savingsRate;
            } else if (type.equals("prestamo")) {
                rate = loanRate;
            } else {
                throw new InvalidDataException("La cuenta " + accountId + " tiene tipo no procesable: " + type);
            }

            item.setAccountId(accountId);
            item.setAccountName(name);
            item.setInitialBalance(balance);
            item.setAccountType(type);
            item.setRate(rate);
            // Calcula el saldo final que sera persistido por el escritor
            item.setFinalBalance(balance.multiply(BigDecimal.ONE.add(rate)).setScale(2, RoundingMode.HALF_UP));
            return item;
        } catch (InvalidDataException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new InvalidDataException("Cuenta con formato inválido: " + ex.getMessage());
        }
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new InvalidDataException("Campo requerido vacío: " + field);
        }
        return value.trim();
    }
}
