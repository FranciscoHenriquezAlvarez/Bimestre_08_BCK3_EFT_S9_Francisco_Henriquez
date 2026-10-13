package cl.duoc.bancoxyz.batch;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.beans.factory.annotation.Qualifier;

import static org.assertj.core.api.Assertions.assertThat;

class DailyTransactionsJobIntegrationTest extends BatchIntegrationSupport {

    @org.springframework.beans.factory.annotation.Autowired
    @Qualifier("dailyTransactionsJob")
    private Job dailyTransactionsJob;

    @Test
    void debeProcesarDatasetDailyHistorico() throws Exception {
        JobExecution execution = jobLauncher.run(dailyTransactionsJob, new JobParametersBuilder()
                .addLong("test-run", System.nanoTime())
                .toJobParameters());
        StepExecution step = execution.getStepExecutions().iterator().next();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(step.getReadCount()).isEqualTo(1_000);
        assertThat(step.getWriteCount()).isEqualTo(387);
        assertThat(step.getSkipCount()).isEqualTo(613);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM transacciones_procesadas", Integer.class))
                .isEqualTo(387);
    }
}
