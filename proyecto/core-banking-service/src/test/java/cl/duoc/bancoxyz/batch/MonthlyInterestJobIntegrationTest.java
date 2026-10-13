package cl.duoc.bancoxyz.batch;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.beans.factory.annotation.Qualifier;

import static org.assertj.core.api.Assertions.assertThat;

class MonthlyInterestJobIntegrationTest extends BatchIntegrationSupport {

    @org.springframework.beans.factory.annotation.Autowired
    @Qualifier("monthlyInterestJob")
    private Job monthlyInterestJob;

    @Test
    void debeProcesarDatasetMonthlyHistorico() throws Exception {
        JobExecution execution = jobLauncher.run(monthlyInterestJob, new JobParametersBuilder()
                .addLong("test-run", System.nanoTime())
                .toJobParameters());
        StepExecution step = execution.getStepExecutions().iterator().next();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(step.getReadCount()).isEqualTo(1_000);
        assertThat(step.getWriteCount()).isEqualTo(263);
        assertThat(step.getSkipCount()).isEqualTo(737);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM intereses_mensuales", Integer.class))
                .isEqualTo(50);
    }
}
