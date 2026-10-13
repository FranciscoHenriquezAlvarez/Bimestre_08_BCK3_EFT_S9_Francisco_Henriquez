package cl.duoc.bancoxyz.config;

import cl.duoc.bancoxyz.model.InterestAccount;
import cl.duoc.bancoxyz.processor.MonthlyInterestProcessor;
import java.math.BigDecimal;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.retry.backoff.BackOffPolicy;
import org.springframework.retry.RetryPolicy;

// Configura el Job y sus componentes para el calculo mensual de intereses
@Configuration
public class MonthlyInterestJobConfig {

        // Lee las cuentas desde el archivo CSV legacy
        @Bean
        public SynchronizedItemStreamReader<InterestAccount> monthlyInterestReader() {
                FlatFileItemReader<InterestAccount> reader = new FlatFileItemReaderBuilder<InterestAccount>()
                                .name("monthlyInterestReader")
                                .resource(new ClassPathResource("input/intereses.csv"))
                                .linesToSkip(1)
                                .delimited()
                                .names("cuentaId", "nombre", "saldo", "edad", "tipo")
                                .targetType(InterestAccount.class)
                                .saveState(false)
                                .build();
                SynchronizedItemStreamReader<InterestAccount> synchronizedReader = new SynchronizedItemStreamReader<>();
                synchronizedReader.setDelegate(reader);
                return synchronizedReader;
        }

        // Crea el procesador que calcula el saldo final de cada cuenta
        @Bean
        @StepScope
        public MonthlyInterestProcessor monthlyInterestProcessor(
                        @Value("${bank.interest.savings-rate}") BigDecimal savingsRate,
                        @Value("${bank.interest.loan-rate}") BigDecimal loanRate) {
                return new MonthlyInterestProcessor(savingsRate, loanRate);
        }

        // Guarda los intereses calculados en PostgreSQL mediante JDBC
        @Bean
        public JdbcBatchItemWriter<InterestAccount> monthlyInterestWriter(javax.sql.DataSource dataSource) {
                return new JdbcBatchItemWriterBuilder<InterestAccount>()
                                .dataSource(dataSource)
                                .sql("""
                                                INSERT INTO intereses_mensuales
                                                (cuenta_id, nombre, tipo, saldo_inicial, tasa, saldo_final)
                                                VALUES (:accountId, :accountName, :accountType, :initialBalance, :rate, :finalBalance)
                                                ON CONFLICT (cuenta_id) DO UPDATE SET nombre = EXCLUDED.nombre, tipo = EXCLUDED.tipo,
                                                saldo_inicial = EXCLUDED.saldo_inicial, tasa = EXCLUDED.tasa, saldo_final = EXCLUDED.saldo_final
                                                """)
                                .beanMapped()
                                .build();
        }

        // Procesa en orden: el ultimo registro valido del CSV prevalece por cuenta
        @Bean
        public Step monthlyInterestStep(JobRepository jobRepository,
                        DataSourceTransactionManager transactionManager,
                        SynchronizedItemStreamReader<InterestAccount> monthlyInterestReader,
                        MonthlyInterestProcessor monthlyInterestProcessor,
                        JdbcBatchItemWriter<InterestAccount> monthlyInterestWriter,
                        BankDataSkipPolicy bankDataSkipPolicy,
                        BatchSkipListener batchSkipListener,
                        StepPerformanceListener stepPerformanceListener,
                        RetryPolicy transientDataAccessRetryPolicy,
                        BackOffPolicy batchBackOffPolicy,
                        @Value("${bank.batch.chunk-size}") int chunkSize) {
                return new StepBuilder("monthlyInterestStep", jobRepository)
                                .<InterestAccount, InterestAccount>chunk(chunkSize, transactionManager)
                                .reader(monthlyInterestReader)
                                .processor(monthlyInterestProcessor)
                                .writer(monthlyInterestWriter)
                                .faultTolerant()
                                .skipPolicy(bankDataSkipPolicy)
                                .retryPolicy(transientDataAccessRetryPolicy)
                                .backOffPolicy(batchBackOffPolicy)
                                .listener(batchSkipListener)
                                .listener(stepPerformanceListener)
                                .build();
        }

        // Define el Job encargado del calculo mensual de intereses
        @Bean
        public Job monthlyInterestJob(JobRepository jobRepository, Step monthlyInterestStep,
                        JobSummaryLoggingListener jobSummaryLoggingListener) {
                return new JobBuilder("monthlyInterestJob", jobRepository)
                                .start(monthlyInterestStep)
                                .listener(jobSummaryLoggingListener)
                                .build();
        }
}
