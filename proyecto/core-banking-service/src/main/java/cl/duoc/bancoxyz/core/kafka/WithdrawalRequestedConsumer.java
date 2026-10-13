package cl.duoc.bancoxyz.core.kafka;

import cl.duoc.bancoxyz.events.TopicNames;
import cl.duoc.bancoxyz.events.WithdrawalRequestedEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

// Procesa los retiros recibidos y publica su resultado en Kafka.
@Component
public class WithdrawalRequestedConsumer {
    private static final Logger log = LoggerFactory.getLogger(WithdrawalRequestedConsumer.class);
    private final AsyncWithdrawalService withdrawals;
    private final KafkaTemplate<String, Object> kafka;

    public WithdrawalRequestedConsumer(AsyncWithdrawalService withdrawals, KafkaTemplate<String, Object> kafka) {
        this.withdrawals = withdrawals;
        this.kafka = kafka;
    }

    // La version actualizada deja este listener inactivo durante el traslado a Pagos.
    @KafkaListener(topics = TopicNames.WITHDRAWAL_REQUESTED, autoStartup = "false")
    public void consume(ConsumerRecord<String, WithdrawalRequestedEvent> record) {
        WithdrawalRequestedEvent requested = record.value();
        log.info("[Kafka][Consumer] partition={} thread={} eventId={} transactionId={}", record.partition(),
                Thread.currentThread().getName(), requested.eventId(), requested.transactionId());
        AsyncWithdrawalService.ProcessingResult result = withdrawals.process(requested);
        if (!result.duplicate()) {
            String topic = result.resultEvent() instanceof cl.duoc.bancoxyz.events.WithdrawalCompletedEvent
                    ? TopicNames.WITHDRAWAL_COMPLETED : TopicNames.WITHDRAWAL_REJECTED;
            kafka.send(topic, requested.accountId().toString(), result.resultEvent());
            log.info("[Kafka][Producer] {} transactionId={}", topic, requested.transactionId());
        }
    }
}
