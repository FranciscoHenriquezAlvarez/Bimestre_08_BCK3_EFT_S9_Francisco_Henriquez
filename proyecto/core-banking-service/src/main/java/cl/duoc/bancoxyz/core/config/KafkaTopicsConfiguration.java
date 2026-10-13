package cl.duoc.bancoxyz.core.config;

import cl.duoc.bancoxyz.events.TopicNames;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
@Profile("!test")
public class KafkaTopicsConfiguration {
    // Tres particiones permiten repartir cuentas entre consumidores del mismo grupo.
    private NewTopic topic(String name) {
        return TopicBuilder.name(name).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic withdrawalRequestedTopic() {
        return topic(TopicNames.WITHDRAWAL_REQUESTED);
    }

    @Bean
    NewTopic withdrawalCompletedTopic() {
        return topic(TopicNames.WITHDRAWAL_COMPLETED);
    }

    @Bean
    NewTopic withdrawalRejectedTopic() {
        return topic(TopicNames.WITHDRAWAL_REJECTED);
    }
}
