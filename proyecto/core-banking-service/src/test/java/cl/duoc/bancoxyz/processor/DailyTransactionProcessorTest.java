package cl.duoc.bancoxyz.processor;

import cl.duoc.bancoxyz.exception.InvalidDataException;
import cl.duoc.bancoxyz.model.DailyTransaction;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DailyTransactionProcessorTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final DailyTransactionProcessor processor = new DailyTransactionProcessor(jdbcTemplate);

    @ParameterizedTest
    @CsvSource({ "2024-01-31", "31-01-2024", "2024/01/31", "31/01/2024" })
    void debeTransformarTransaccionValidaConFormatosLegacy(String date) {
        whenDuplicatesAre(0);

        DailyTransaction result = processor.process(transaction("10", date, "1500.25", "CREDITO"));

        assertThat(result.getTransactionId()).isEqualTo(10);
        assertThat(result.getTransactionDate()).isEqualTo(LocalDate.of(2024, 1, 31));
        assertThat(result.getAmount()).isEqualByComparingTo("1500.25");
        assertThat(result.getTransactionType()).isEqualTo("credito");
    }

    @ParameterizedTest
    @CsvSource({ "0,credito", "-1,credito", "1000,transferencia" })
    void debeRechazarMontoOtipoNoPermitido(String amount, String type) {
        assertThatThrownBy(() -> processor.process(transaction("20", "2024-01-31", amount, type)))
                .isInstanceOf(InvalidDataException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = { "2024-02-30", "fecha-invalida" })
    void debeRechazarFechaInvalida(String date) {
        assertThatThrownBy(() -> processor.process(transaction("21", date, "100", "debito")))
                .isInstanceOf(InvalidDataException.class);
    }

    @org.junit.jupiter.api.Test
    void debeRechazarDuplicadoPersistido() {
        whenDuplicatesAre(1);

        assertThatThrownBy(() -> processor.process(transaction("30", "2024-01-31", "100", "debito")))
                .isInstanceOf(InvalidDataException.class)
                .hasMessageContaining("duplicada");
    }

    @org.junit.jupiter.api.Test
    void debeRechazarDuplicadoEnMismaEjecucion() {
        whenDuplicatesAre(0);
        processor.process(transaction("31", "2024-01-31", "100", "debito"));

        assertThatThrownBy(() -> processor.process(transaction("32", "2024-01-31", "100.00", "debito")))
                .isInstanceOf(InvalidDataException.class)
                .hasMessageContaining("misma ejecución");
    }

    @org.junit.jupiter.api.Test
    void debePropagarErrorTemporalParaRetry() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any(), any(), any()))
                .thenThrow(new TransientDataAccessResourceException("conexion temporal"));

        assertThatThrownBy(() -> processor.process(transaction("40", "2024-01-31", "100", "credito")))
                .isInstanceOf(TransientDataAccessResourceException.class);
    }

    private void whenDuplicatesAre(int duplicates) {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any(), any(), any()))
                .thenReturn(duplicates);
    }

    private DailyTransaction transaction(String id, String date, String amount, String type) {
        DailyTransaction transaction = new DailyTransaction();
        transaction.setId(id);
        transaction.setFecha(date);
        transaction.setMonto(amount);
        transaction.setTipo(type);
        return transaction;
    }
}
