package cl.duoc.bancoxyz.atm.kafka;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cl.duoc.bancoxyz.events.TopicNames;
import cl.duoc.bancoxyz.events.WithdrawalRequestedEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

class AsyncWithdrawalProducerTest {
    private KafkaTemplate<String, Object> kafka;
    private AsyncWithdrawalProducer producer;
    private final WithdrawalRequestedEvent event = new WithdrawalRequestedEvent(UUID.randomUUID(),
            UUID.randomUUID(), 104, new BigDecimal("10000"), Instant.now());

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        kafka = mock(KafkaTemplate.class);
        producer = new AsyncWithdrawalProducer(kafka, 20);
    }

    @Test
    void publishSucceedsWhenKafkaAcknowledgesMessage() {
        when(kafka.send(TopicNames.WITHDRAWAL_REQUESTED, "104", event))
                .thenReturn(CompletableFuture.completedFuture(new SendResult<>(null, null)));

        assertDoesNotThrow(() -> producer.publish(event));
        verify(kafka).send(TopicNames.WITHDRAWAL_REQUESTED, "104", event);
    }

    @Test
    void publishFailsWhenKafkaRejectsMessage() {
        RuntimeException cause = new RuntimeException("Broker unavailable");
        when(kafka.send(TopicNames.WITHDRAWAL_REQUESTED, "104", event))
                .thenReturn(CompletableFuture.failedFuture(cause));

        KafkaPublicationException error = assertThrows(KafkaPublicationException.class, () -> producer.publish(event));
        assertSame(cause, error.getCause());
        assertEquals(event.transactionId(), error.getTransactionId());
        verify(kafka).send(TopicNames.WITHDRAWAL_REQUESTED, "104", event);
    }

    @Test
    void publishFailsWhenKafkaDoesNotAcknowledgeWithinTimeout() {
        when(kafka.send(TopicNames.WITHDRAWAL_REQUESTED, "104", event)).thenReturn(new CompletableFuture<>());

        KafkaPublicationException error = assertThrows(KafkaPublicationException.class, () -> producer.publish(event));
        assertInstanceOf(TimeoutException.class, error.getCause());
        verify(kafka).send(TopicNames.WITHDRAWAL_REQUESTED, "104", event);
    }

    @Test
    void publishRestoresInterruptFlagWhenInterrupted() {
        when(kafka.send(TopicNames.WITHDRAWAL_REQUESTED, "104", event)).thenReturn(new CompletableFuture<>());

        try {
            Thread.currentThread().interrupt();
            KafkaPublicationException error = assertThrows(KafkaPublicationException.class,
                    () -> producer.publish(event));
            assertInstanceOf(InterruptedException.class, error.getCause());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void publishFailsWhenKafkaSendThrowsImmediately() {
        RuntimeException cause = new RuntimeException("Send failed");
        when(kafka.send(TopicNames.WITHDRAWAL_REQUESTED, "104", event)).thenThrow(cause);

        KafkaPublicationException error = assertThrows(KafkaPublicationException.class, () -> producer.publish(event));
        assertSame(cause, error.getCause());
        verify(kafka).send(TopicNames.WITHDRAWAL_REQUESTED, "104", event);
    }
}
