package cl.duoc.bancoxyz.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.stereotype.Component;

// Registra el resultado y tiempo de ejecucion del step
@Component
public class StepPerformanceListener implements StepExecutionListener {
    private static final Logger log = LoggerFactory.getLogger(StepPerformanceListener.class);
    private static final String START_TIME = "stepStartTime";

    @Override
    public void beforeStep(StepExecution stepExecution) {
        stepExecution.getExecutionContext().putLong(START_TIME, System.currentTimeMillis());
        log.info("Inicia Step {}", stepExecution.getStepName());
    }

    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        long startTime = stepExecution.getExecutionContext().getLong(START_TIME, System.currentTimeMillis());
        long duration = System.currentTimeMillis() - startTime;
        log.info("Finaliza Step {} - duracionMs={}, leidos={}, escritos={}, omitidos={}, estado={}",
                stepExecution.getStepName(), duration, stepExecution.getReadCount(),
                stepExecution.getWriteCount(), stepExecution.getSkipCount(), stepExecution.getStatus());
        return null;
    }
}
