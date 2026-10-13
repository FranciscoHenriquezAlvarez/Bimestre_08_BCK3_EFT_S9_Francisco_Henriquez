package cl.duoc.bancoxyz.processor;

import cl.duoc.bancoxyz.exception.InvalidDataException;
import cl.duoc.bancoxyz.model.AnnualMovement;
import java.time.LocalDate;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnnualMovementProcessorTest {

    private final AnnualMovementProcessor processor = new AnnualMovementProcessor();

    @ParameterizedTest
    @CsvSource({
            "2024-01-31,deposito,100", "31-01-2024,retiro,-100",
            "2024/01/31,compra,-100", "31/01/2024,deposito,100"
    })
    void debeAceptarMovimientosYformatosLegacy(String date, String transaction, String amount) {
        AnnualMovement result = processor.process(movement(date, transaction, amount));

        assertThat(result.getMovementDate()).isEqualTo(LocalDate.of(2024, 1, 31));
        assertThat(result.getTransaction()).isEqualTo(transaction);
        assertThat(result.getAmount()).isEqualByComparingTo(amount);
    }

    @ParameterizedTest
    @CsvSource({
            "deposito,-100", "retiro,100", "compra,100", "deposito,0", "transferencia,100"
    })
    void debeRechazarSignosOtiposInvalidos(String transaction, String amount) {
        assertThatThrownBy(() -> processor.process(movement("2024-01-31", transaction, amount)))
                .isInstanceOf(InvalidDataException.class);
    }

    @ParameterizedTest
    @CsvSource({ "2024-02-30", "fecha-invalida" })
    void debeRechazarFechaInvalida(String date) {
        assertThatThrownBy(() -> processor.process(movement(date, "deposito", "100")))
                .isInstanceOf(InvalidDataException.class);
    }

    private AnnualMovement movement(String date, String transaction, String amount) {
        AnnualMovement movement = new AnnualMovement();
        movement.setCuentaId("10");
        movement.setFecha(date);
        movement.setTransaccion(transaction);
        movement.setMonto(amount);
        movement.setDescripcion("Movimiento de prueba");
        return movement;
    }
}
