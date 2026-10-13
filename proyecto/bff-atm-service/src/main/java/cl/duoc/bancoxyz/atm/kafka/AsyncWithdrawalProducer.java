package cl.duoc.bancoxyz.atm.kafka;

import cl.duoc.bancoxyz.events.TopicNames;
import cl.duoc.bancoxyz.events.WithdrawalRequestedEvent;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

@Service
public class AsyncWithdrawalProducer {
    private static final Logger log = LoggerFactory.getLogger(AsyncWithdrawalProducer.class);
    private final KafkaTemplate<String, Object> kafka;
    private final long publishTimeoutMs;

    public AsyncWithdrawalProducer(KafkaTemplate<String, Object> kafka,
            @Value("${app.kafka.publish-timeout-ms:5000}") long publishTimeoutMs) {
        this.kafka = kafka;
        this.publishTimeoutMs = publishTimeoutMs;
    }

    // Usa la cuenta como clave para conservar el orden de sus retiros.
    public void publish(WithdrawalRequestedEvent event) {
        try {
            // Espera la confirmacion de Kafka antes de aceptar la solicitud.
            kafka.send(TopicNames.WITHDRAWAL_REQUESTED, event.accountId().toString(), event)
                    .get(publishTimeoutMs, TimeUnit.MILLISECONDS);
            log.info("[Kafka][Producer] WithdrawalRequested eventId={} transactionId={}",
                    event.eventId(), event.transactionId());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw publicationFailure(event, error);
        } catch (ExecutionException error) {
            throw publicationFailure(event, error.getCause());
        } catch (TimeoutException | RuntimeException error) {
            throw publicationFailure(event, error);
        }
    }

    private KafkaPublicationException publicationFailure(WithdrawalRequestedEvent event, Throwable cause) {
        log.error("[Kafka][Producer] No fue posible publicar eventId={} transactionId={}",
                event.eventId(), event.transactionId(), cause);
        return new KafkaPublicationException(event.transactionId(), cause);
    }
}
