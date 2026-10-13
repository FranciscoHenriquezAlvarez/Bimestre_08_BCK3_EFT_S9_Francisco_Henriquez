package cl.duoc.bancoxyz.payment;

import cl.duoc.bancoxyz.events.TopicNames;
import cl.duoc.bancoxyz.events.WithdrawalRequestedEvent;
import cl.duoc.bancoxyz.payment.CoreOperationClient.CoreRequestException;
import cl.duoc.bancoxyz.payment.PaymentModels.WithdrawalResult;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class WithdrawalRequestedConsumer {
    private static final Logger log = LoggerFactory.getLogger(WithdrawalRequestedConsumer.class);
    private final CoreOperationClient core;
    private final KafkaTemplate<String, Object> kafka;
    private final long publishTimeoutMs;

    public WithdrawalRequestedConsumer(CoreOperationClient core, KafkaTemplate<String, Object> kafka,
            @Value("${payment.kafka.publish-timeout-ms:5000}") long publishTimeoutMs) {
        this.core = core;
        this.kafka = kafka;
        this.publishTimeoutMs = publishTimeoutMs;
    }

    @KafkaListener(id = "paymentWithdrawals", topics = TopicNames.WITHDRAWAL_REQUESTED,
            groupId = "core-banking-withdrawal-group", containerFactory = "withdrawalKafkaListenerContainerFactory",
            autoStartup = "${payment.kafka.withdrawals.enabled:false}")
    public void consume(ConsumerRecord<String, WithdrawalRequestedEvent> record, Acknowledgment acknowledgment) {
        WithdrawalRequestedEvent requested = record.value();
        WithdrawalResult result = result(requested);
        String topic = result.completed() == null ? TopicNames.WITHDRAWAL_REJECTED : TopicNames.WITHDRAWAL_COMPLETED;
        try {
            kafka.send(topic, requested.accountId().toString(), result.event()).get(publishTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw publicationFailure(requested);
        } catch (ExecutionException | TimeoutException | RuntimeException error) {
            throw publicationFailure(requested);
        }
        // acknowledge se ejecuta en el hilo consumidor, despues de la confirmacion del broker.
        acknowledgment.acknowledge();
        log.info("Retiro confirmado transactionId={} partition={} offset={}", requested.transactionId(),
                record.partition(), record.offset());
    }

    private WithdrawalResult result(WithdrawalRequestedEvent requested) {
        try {
            return core.withdraw(requested);
        } catch (CoreRequestException error) {
            if (!"UNKNOWN".equals(error.result().status())) {
                throw error;
            }
            // Una unica consulta puede recuperar el commit cuya respuesta se perdio.
            return core.withdrawal(requested.transactionId(), requested);
        }
    }

    private IllegalStateException publicationFailure(WithdrawalRequestedEvent requested) {
        log.error("Publicacion no confirmada transactionId={}; solicitud sin reconocer, listener se detendra",
                requested.transactionId());
        return new IllegalStateException("No se confirmo el resultado Kafka; se conserva la solicitud para recuperacion");
    }
}
