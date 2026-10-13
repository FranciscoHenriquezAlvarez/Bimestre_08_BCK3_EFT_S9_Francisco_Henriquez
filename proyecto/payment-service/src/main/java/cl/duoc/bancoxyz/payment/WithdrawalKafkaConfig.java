package cl.duoc.bancoxyz.payment;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;

@Configuration
public class WithdrawalKafkaConfig {
    @Bean
    ConcurrentKafkaListenerContainerFactory<Object, Object> withdrawalKafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer, ConsumerFactory<Object, Object> consumers) {
        consumers.updateConfigs(java.util.Map.of(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false));
        var factory = new ConcurrentKafkaListenerContainerFactory<Object, Object>();
        configurer.configure(factory, consumers);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.getContainerProperties().setSyncCommits(true);
        factory.getContainerProperties().setAsyncAcks(false);
        factory.getContainerProperties().setStopImmediate(true);
        // Un fallo detiene el listener sin descartar el mensaje ni reintentar indefinidamente.
        factory.setCommonErrorHandler(new CommonContainerStoppingErrorHandler());
        return factory;
    }
}
