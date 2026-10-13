package cl.duoc.bancoxyz.core;

import cl.duoc.bancoxyz.BankBatchApplication;
import cl.duoc.bancoxyz.core.AccountOperationService.OperationRequest;
import cl.duoc.bancoxyz.core.AccountOperationService.OperationType;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.boot.SpringApplication;

public class BatchIsolationRunner {
    public static void main(String[] args) throws Exception {
        // Otro proceso permite aislar el CSV anual sin cambiar el Job existente.
        try (var context = SpringApplication.run(BankBatchApplication.class, args)) {
            JobLauncher launcher = context.getBean(JobLauncher.class);
            for (String jobName : new String[] {"dailyTransactionsJob", "monthlyInterestJob", "annualStatementsJob"}) {
                var execution = launcher.run(context.getBean(jobName, Job.class), new JobParametersBuilder()
                        .addString("eft-isolation-run", UUID.randomUUID().toString()).toJobParameters());
                if (execution.getStatus() != BatchStatus.COMPLETED) {
                    throw new IllegalStateException("Job incompleto: " + jobName + " " + execution.getAllFailureExceptions());
                }
                execution.getStepExecutions().forEach(step -> System.out.printf(
                        "EFT_JOB %s %s %s read=%d write=%d skip=%d%n", jobName, step.getStepName(),
                        step.getStatus(), step.getReadCount(), step.getWriteCount(), step.getSkipCount()));
            }
            UUID operationId = UUID.fromString(System.getenv("EFT_TEST_OPERATION_ID"));
            var operations = context.getBean(AccountOperationService.class);
            var original = operations.operation(operationId);
            var duplicate = operations.execute(new OperationRequest(operationId, OperationType.DEBIT, 101, null,
                    new BigDecimal("10")));
            if (!original.equals(duplicate) || !"COMPLETED".equals(duplicate.status())
                    || duplicate.sourceBalance().compareTo(new BigDecimal("90")) != 0) {
                throw new IllegalStateException("El reinicio no conservo el resultado original");
            }
            System.out.println("EFT_RESTART resultado original conservado; duplicado sin nuevo debito");
        }
    }
}
