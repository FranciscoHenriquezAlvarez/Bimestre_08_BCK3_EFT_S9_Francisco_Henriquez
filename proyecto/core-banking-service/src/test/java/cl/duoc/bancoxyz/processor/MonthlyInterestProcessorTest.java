package cl.duoc.bancoxyz.processor;

import cl.duoc.bancoxyz.exception.InvalidDataException;
import cl.duoc.bancoxyz.model.InterestAccount;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MonthlyInterestProcessorTest {

    private final MonthlyInterestProcessor processor = new MonthlyInterestProcessor(
            new BigDecimal("0.01"), new BigDecimal("0.02"));

    @Test
    void debeCalcularInteresParaCuentaAhorro() {
        InterestAccount result = processor.process(account("1", "Ana", "1000.00", "30", "ahorro"));

        assertThat(result.getRate()).isEqualByComparingTo("0.01");
        assertThat(result.getFinalBalance()).isEqualByComparingTo("1010.00");
        assertThat(result.getFinalBalance().scale()).isEqualTo(2);
    }

    @Test
    void debeCalcularInteresParaCuentaPrestamo() {
        InterestAccount result = processor.process(account("2", "Luis", "1000.00", "30", "prestamo"));

        assertThat(result.getRate()).isEqualByComparingTo("0.02");
        assertThat(result.getFinalBalance()).isEqualByComparingTo("1020.00");
    }

    @ParameterizedTest
    @CsvSource({ "0,30,ahorro", "-1,30,ahorro", "1000,17,ahorro", "1000,101,ahorro", "1000,30,cuenta", "1000,30,''" })
    void debeRechazarDatosNoPermitidos(String balance, String age, String type) {
        assertThatThrownBy(() -> processor.process(account("3", "Ana", balance, age, type)))
                .isInstanceOf(InvalidDataException.class);
    }

    private InterestAccount account(String id, String name, String balance, String age, String type) {
        InterestAccount account = new InterestAccount();
        account.setCuentaId(id);
        account.setNombre(name);
        account.setSaldo(balance);
        account.setEdad(age);
        account.setTipo(type);
        return account;
    }
}
