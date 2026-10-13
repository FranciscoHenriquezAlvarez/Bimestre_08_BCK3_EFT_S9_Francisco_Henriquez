package cl.duoc.bancoxyz.config;

import cl.duoc.bancoxyz.exception.InvalidDataException;
import org.springframework.batch.core.step.skip.SkipPolicy;
import org.springframework.batch.item.file.FlatFileParseException;
import org.springframework.stereotype.Component;

// Define que errores de datos se pueden omitir durante el proceso
@Component
public class BankDataSkipPolicy implements SkipPolicy {
    private static final int MAX_SKIPS = 1_000;

    @Override
    public boolean shouldSkip(Throwable throwable, long skipCount) {
        return skipCount < MAX_SKIPS
                && (throwable instanceof InvalidDataException
                        || throwable instanceof FlatFileParseException);
    }
}
