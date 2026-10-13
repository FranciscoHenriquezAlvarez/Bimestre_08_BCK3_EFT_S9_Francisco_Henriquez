package cl.duoc.bancoxyz.batch;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBatchTest
class AnnualMovementsStepIntegrationTest extends BatchIntegrationSupport {

    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    @Autowired
    @Qualifier("annualStatementsJob")
    private Job annualStatementsJob;

    @Test
    void debeProcesarStepAnualSinGenerarArchivoDeSalida() throws Exception {
        jobLauncherTestUtils.setJob(annualStatementsJob);
        JobExecution execution = jobLauncherTestUtils.launchStep("annualMovementsStep",
                new JobParametersBuilder().addLong("test-run", System.nanoTime()).toJobParameters());
        var step = execution.getStepExecutions().iterator().next();

        assertThat(step.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(step.getReadCount()).isEqualTo(1_000);
        assertThat(step.getWriteCount()).isEqualTo(289);
        assertThat(step.getSkipCount()).isEqualTo(711);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM movimientos_anuales", Integer.class))
                .isEqualTo(289);
    }
}
