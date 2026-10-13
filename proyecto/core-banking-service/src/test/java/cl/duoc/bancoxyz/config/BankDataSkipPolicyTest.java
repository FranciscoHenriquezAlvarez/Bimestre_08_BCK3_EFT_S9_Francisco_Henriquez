package cl.duoc.bancoxyz.config;

import cl.duoc.bancoxyz.exception.InvalidDataException;
import org.junit.jupiter.api.Test;
import org.springframework.batch.item.file.FlatFileParseException;

import static org.assertj.core.api.Assertions.assertThat;

class BankDataSkipPolicyTest {

    private final BankDataSkipPolicy skipPolicy = new BankDataSkipPolicy();

    @Test
    void debePermitirInvalidDataException() {
        assertThat(skipPolicy.shouldSkip(new InvalidDataException("dato invalido"), 0)).isTrue();
    }

    @Test
    void debePermitirFlatFileParseException() {
        assertThat(skipPolicy.shouldSkip(new FlatFileParseException("csv invalido", "fila", 1), 0)).isTrue();
    }

    @Test
    void noDebePermitirExcepcionGenerica() {
        assertThat(skipPolicy.shouldSkip(new IllegalStateException("error"), 0)).isFalse();
    }

    @Test
    void noDebePermitirSkipAlAlcanzarLimite() {
        assertThat(skipPolicy.shouldSkip(new InvalidDataException("dato invalido"), 1_000)).isFalse();
    }
}
