package cl.duoc.bancoxyz.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.retry.backoff.BackOffPolicy;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.RetryPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;

// Configura el executor, retry y backoff del procesamiento batch.
@Configuration
public class BatchExecutionConfig {
    private static final Logger log = LoggerFactory.getLogger(BatchExecutionConfig.class);

    @Bean
    public ThreadPoolTaskExecutor batchTaskExecutor(
            @Value("${bank.batch.core-pool-size}") int corePoolSize,
            @Value("${bank.batch.max-pool-size}") int maxPoolSize,
            @Value("${bank.batch.queue-capacity}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("Batch-Thread-");
        log.info("TaskExecutor configurado: core={}, max={}, cola={}, prefijo=Batch-Thread-",
                corePoolSize, maxPoolSize, queueCapacity);
        return executor;
    }

    @Bean
    public RetryPolicy transientDataAccessRetryPolicy(
            @Value("${bank.batch.retry-limit}") int retryLimit) {
        return new SimpleRetryPolicy(retryLimit,
                Map.of(TransientDataAccessException.class, true), true);
    }

    @Bean
    public BackOffPolicy batchBackOffPolicy(@Value("${bank.batch.backoff-ms}") long backOffMs) {
        FixedBackOffPolicy policy = new FixedBackOffPolicy();
        policy.setBackOffPeriod(backOffMs);
        return policy;
    }
}
