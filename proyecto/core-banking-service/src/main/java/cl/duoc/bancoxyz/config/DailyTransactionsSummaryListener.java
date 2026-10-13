package cl.duoc.bancoxyz.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.StepExecution;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

// Guarda y registra el resumen al finalizar el Job de transacciones diarias
@Component
public class DailyTransactionsSummaryListener implements JobExecutionListener {
    private static final Logger log = LoggerFactory.getLogger(DailyTransactionsSummaryListener.class);

    private final JdbcTemplate jdbcTemplate;

    public DailyTransactionsSummaryListener(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void beforeJob(JobExecution jobExecution) {
        log.info("Inicia Job {} - ejecucion={}", jobExecution.getJobInstance().getJobName(), jobExecution.getId());
    }

    @Override
    public void afterJob(JobExecution jobExecution) {
        for (StepExecution step : jobExecution.getStepExecutions()) {
            jdbcTemplate.update(
                    """
                            INSERT INTO resumen_transacciones_diarias
                            (job_execution_id, registros_leidos, registros_procesados, registros_escritos, registros_omitidos, estado)
                            VALUES (?, ?, ?, ?, ?, ?)
                            """,
                    jobExecution.getId(), step.getReadCount(),
                    step.getReadCount() - step.getFilterCount() - step.getSkipCount(), step.getWriteCount(),
                    step.getSkipCount(), jobExecution.getStatus().toString());
            log.info(
                    "RESUMEN {} - JobId={}, leidos={}, filtrados={}, procesados={}, escritos={}, omitidos={}, estado={}",
                    jobExecution.getJobInstance().getJobName(), jobExecution.getId(), step.getReadCount(),
                    step.getFilterCount(), step.getReadCount() - step.getFilterCount() - step.getSkipCount(),
                    step.getWriteCount(), step.getSkipCount(), jobExecution.getStatus());
        }
    }
}
