package cl.duoc.bancoxyz.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.SkipListener;
import org.springframework.stereotype.Component;

// Registra los datos omitidos durante la ejecucion del step
@Component
public class BatchSkipListener implements SkipListener<Object, Object> {
    private static final Logger log = LoggerFactory.getLogger(BatchSkipListener.class);

    @Override
    public void onSkipInRead(Throwable throwable) {
        log.warn("Registro omitido al leer: {}", throwable.getMessage());
    }

    @Override
    public void onSkipInProcess(Object item, Throwable throwable) {
        log.warn("Registro omitido al procesar {}: {}", item, throwable.getMessage());
    }

    @Override
    public void onSkipInWrite(Object item, Throwable throwable) {
        log.warn("Registro omitido al escribir {}: {}", item, throwable.getMessage());
    }
}
