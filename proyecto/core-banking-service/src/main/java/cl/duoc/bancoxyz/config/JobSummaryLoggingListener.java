package cl.duoc.bancoxyz.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.StepExecution;
import org.springframework.stereotype.Component;

// Registra los contadores de cada Step al finalizar un Job
@Component
public class JobSummaryLoggingListener implements JobExecutionListener {
    private static final Logger log = LoggerFactory.getLogger(JobSummaryLoggingListener.class);

    @Override
    public void beforeJob(JobExecution jobExecution) {
        log.info("Inicia Job {} - ejecucion={}", jobExecution.getJobInstance().getJobName(), jobExecution.getId());
    }

    @Override
    public void afterJob(JobExecution jobExecution) {
        for (StepExecution step : jobExecution.getStepExecutions()) {
            log.info(
                    "RESUMEN {} - JobId={}, leidos={}, filtrados={}, procesados={}, escritos={}, omitidos={}, estado={}",
                    jobExecution.getJobInstance().getJobName(), jobExecution.getId(), step.getReadCount(),
                    step.getFilterCount(), step.getReadCount() - step.getFilterCount() - step.getSkipCount(),
                    step.getWriteCount(), step.getSkipCount(), jobExecution.getStatus());
        }
    }
}
