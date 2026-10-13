package cl.duoc.bancoxyz.config;

import cl.duoc.bancoxyz.model.AnnualMovement;
import cl.duoc.bancoxyz.processor.AnnualMovementProcessor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.batch.item.database.builder.JdbcBatchItemWriterBuilder;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.batch.item.support.SynchronizedItemStreamReader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.retry.backoff.BackOffPolicy;
import org.springframework.retry.RetryPolicy;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

// Configura el Job y sus componentes para los estados de cuenta anuales
@Configuration
public class AnnualStatementsJobConfig {

    // Lee los movimientos anuales desde el archivo CSV legacy
    @Bean
    public SynchronizedItemStreamReader<AnnualMovement> annualStatementsReader() {
        FlatFileItemReader<AnnualMovement> reader = new FlatFileItemReaderBuilder<AnnualMovement>()
                .name("annualStatementsReader")
                .resource(new ClassPathResource("input/cuentas_anuales.csv"))
                .linesToSkip(1)
                .delimited()
                .names("cuentaId", "fecha", "transaccion", "monto", "descripcion")
                .targetType(AnnualMovement.class)
                .saveState(false)
                .build();
        SynchronizedItemStreamReader<AnnualMovement> synchronizedReader = new SynchronizedItemStreamReader<>();
        synchronizedReader.setDelegate(reader);
        return synchronizedReader;
    }

    // Crea el procesador que valida cada movimiento anual
    @Bean
    @StepScope
    public AnnualMovementProcessor annualStatementsProcessor() {
        return new AnnualMovementProcessor();
    }

    // Guarda los movimientos validos en PostgreSQL mediante JDBC
    @Bean
    public JdbcBatchItemWriter<AnnualMovement> annualStatementsWriter(javax.sql.DataSource dataSource) {
        return new JdbcBatchItemWriterBuilder<AnnualMovement>()
                .dataSource(dataSource)
                .sql("""
                        INSERT INTO movimientos_anuales (cuenta_id, fecha, transaccion, monto, descripcion)
                        VALUES (:accountId, :movementDate, :transaction, :amount, :description)
                        ON CONFLICT (cuenta_id, fecha, transaccion, monto, descripcion) DO NOTHING
                        """)
                .beanMapped()
                .assertUpdates(false)
                .build();
    }

    // Define el flujo Reader - Processor - Writer para los movimientos anuales
    @Bean
    public Step annualMovementsStep(JobRepository jobRepository,
            DataSourceTransactionManager transactionManager,
            SynchronizedItemStreamReader<AnnualMovement> annualStatementsReader,
            AnnualMovementProcessor annualStatementsProcessor,
            JdbcBatchItemWriter<AnnualMovement> annualStatementsWriter,
            ThreadPoolTaskExecutor batchTaskExecutor,
            BankDataSkipPolicy bankDataSkipPolicy,
            BatchSkipListener batchSkipListener,
            StepPerformanceListener stepPerformanceListener,
            RetryPolicy transientDataAccessRetryPolicy,
            BackOffPolicy batchBackOffPolicy,
            @Value("${bank.batch.chunk-size}") int chunkSize) {
        return new StepBuilder("annualMovementsStep", jobRepository)
                .<AnnualMovement, AnnualMovement>chunk(chunkSize, transactionManager)
                .reader(annualStatementsReader)
                .processor(annualStatementsProcessor)
                .writer(annualStatementsWriter)
                .faultTolerant()
                .skipPolicy(bankDataSkipPolicy)
                .retryPolicy(transientDataAccessRetryPolicy)
                .backOffPolicy(batchBackOffPolicy)
                .listener(batchSkipListener)
                .listener(stepPerformanceListener)
                .taskExecutor(batchTaskExecutor)
                .build();
    }

    // Genera el archivo CSV de auditoria a partir de los movimientos persistidos
    @Bean
    public Tasklet annualStatementFileTasklet(JdbcTemplate jdbcTemplate) {
        return (contribution, chunkContext) -> {
            List<Map<String, Object>> summaries = jdbcTemplate.queryForList("""
                    SELECT cuenta_id,
                           COUNT(*) AS total_movimientos,
                           COALESCE(SUM(CASE WHEN monto > 0 THEN monto ELSE 0 END), 0) AS total_depositos,
                           COALESCE(SUM(CASE WHEN monto < 0 THEN monto ELSE 0 END), 0) AS total_cargos,
                           COALESCE(SUM(monto), 0) AS saldo_neto
                    FROM movimientos_anuales
                    GROUP BY cuenta_id
                    ORDER BY cuenta_id
                    """);
            Path output = Path.of("output", "estados_cuenta_anuales.csv");
            Files.createDirectories(output.getParent());
            try (var writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
                writer.write("cuenta_id,total_movimientos,total_depositos,total_cargos,saldo_neto");
                writer.newLine();
                for (Map<String, Object> row : summaries) {
                    writer.write("%s,%s,%s,%s,%s".formatted(
                            row.get("cuenta_id"), row.get("total_movimientos"), row.get("total_depositos"),
                            row.get("total_cargos"), row.get("saldo_neto")));
                    writer.newLine();
                }
            } catch (IOException ex) {
                throw new IllegalStateException("No fue posible crear el informe anual", ex);
            }
            return org.springframework.batch.repeat.RepeatStatus.FINISHED;
        };
    }

    // Define el Step que crea el informe anual de salida
    @Bean
    public Step annualStatementReportStep(JobRepository jobRepository,
            DataSourceTransactionManager transactionManager,
            Tasklet annualStatementFileTasklet) {
        return new StepBuilder("annualStatementReportStep", jobRepository)
                .tasklet(annualStatementFileTasklet, transactionManager)
                .build();
    }

    // Define el Job que procesa y resume los movimientos anuales
    @Bean
    public Job annualStatementsJob(JobRepository jobRepository,
            Step annualMovementsStep,
            Step annualStatementReportStep,
            JobSummaryLoggingListener jobSummaryLoggingListener) {
        return new JobBuilder("annualStatementsJob", jobRepository)
                .start(annualMovementsStep)
                .next(annualStatementReportStep)
                .listener(jobSummaryLoggingListener)
                .build();
    }
}
