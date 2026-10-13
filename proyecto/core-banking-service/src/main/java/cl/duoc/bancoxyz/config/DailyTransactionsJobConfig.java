package cl.duoc.bancoxyz.config;

import cl.duoc.bancoxyz.model.DailyTransaction;
import cl.duoc.bancoxyz.processor.DailyTransactionProcessor;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.batch.item.database.builder.JdbcBatchItemWriterBuilder;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.batch.item.support.SynchronizedItemStreamReader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.retry.backoff.BackOffPolicy;
import org.springframework.retry.RetryPolicy;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

// Configura el Job y sus componentes para las transacciones diarias
@Configuration
public class DailyTransactionsJobConfig {

    // Lee las transacciones desde el archivo CSV legacy
    @Bean
    public SynchronizedItemStreamReader<DailyTransaction> dailyTransactionsReader() {
        FlatFileItemReader<DailyTransaction> reader = new FlatFileItemReaderBuilder<DailyTransaction>()
                .name("dailyTransactionsReader")
                .resource(new ClassPathResource("input/transacciones.csv"))
                .linesToSkip(1)
                .delimited()
                .names("id", "fecha", "monto", "tipo")
                .targetType(DailyTransaction.class)
                .saveState(false)
                .build();
        SynchronizedItemStreamReader<DailyTransaction> synchronizedReader = new SynchronizedItemStreamReader<>();
        synchronizedReader.setDelegate(reader);
        return synchronizedReader;
    }

    // Crea el procesador que valida cada transaccion
    @Bean
    @StepScope
    public DailyTransactionProcessor dailyTransactionsProcessor(
            org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        return new DailyTransactionProcessor(jdbcTemplate);
    }

    // Guarda las transacciones validas en PostgreSQL mediante JDBC
    @Bean
    public JdbcBatchItemWriter<DailyTransaction> dailyTransactionsWriter(javax.sql.DataSource dataSource) {
        return new JdbcBatchItemWriterBuilder<DailyTransaction>()
                .dataSource(dataSource)
                .sql("""
                        INSERT INTO transacciones_procesadas (id, fecha, monto, tipo)
                        VALUES (:transactionId, :transactionDate, :amount, :transactionType)
                        ON CONFLICT (id) DO UPDATE SET fecha = EXCLUDED.fecha,
                        monto = EXCLUDED.monto, tipo = EXCLUDED.tipo
                        """)
                .beanMapped()
                .build();
    }

    // Define el flujo paralelo Reader - Processor - Writer con manejo de registros invalidos
    @Bean
    public Step dailyTransactionsStep(JobRepository jobRepository,
            DataSourceTransactionManager transactionManager,
            SynchronizedItemStreamReader<DailyTransaction> dailyTransactionsReader,
            DailyTransactionProcessor dailyTransactionsProcessor,
            JdbcBatchItemWriter<DailyTransaction> dailyTransactionsWriter,
            ThreadPoolTaskExecutor batchTaskExecutor,
            BankDataSkipPolicy bankDataSkipPolicy,
            BatchSkipListener batchSkipListener,
            StepPerformanceListener stepPerformanceListener,
            RetryPolicy transientDataAccessRetryPolicy,
            BackOffPolicy batchBackOffPolicy,
            @Value("${bank.batch.chunk-size}") int chunkSize) {
        return new StepBuilder("dailyTransactionsStep", jobRepository)
                .<DailyTransaction, DailyTransaction>chunk(chunkSize, transactionManager)
                .reader(dailyTransactionsReader)
                .processor(dailyTransactionsProcessor)
                .writer(dailyTransactionsWriter)
                .faultTolerant()
                .skipPolicy(bankDataSkipPolicy)
                .retryPolicy(transientDataAccessRetryPolicy)
                .backOffPolicy(batchBackOffPolicy)
                .listener(batchSkipListener)
                .listener(stepPerformanceListener)
                .taskExecutor(batchTaskExecutor)
                .build();
    }

    // Define el Job encargado del reporte diario de transacciones
    @Bean
    public Job dailyTransactionsJob(JobRepository jobRepository,
            Step dailyTransactionsStep,
            DailyTransactionsSummaryListener dailyTransactionsSummaryListener) {
        return new JobBuilder("dailyTransactionsJob", jobRepository)
                .start(dailyTransactionsStep)
                .listener(dailyTransactionsSummaryListener)
                .build();
    }
}
