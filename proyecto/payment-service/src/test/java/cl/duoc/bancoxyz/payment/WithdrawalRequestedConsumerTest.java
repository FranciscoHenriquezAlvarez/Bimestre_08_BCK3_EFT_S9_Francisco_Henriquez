package cl.duoc.bancoxyz.payment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cl.duoc.bancoxyz.events.*;
import cl.duoc.bancoxyz.payment.CoreOperationClient.CoreRequestException;
import cl.duoc.bancoxyz.payment.PaymentModels.OperationError;
import cl.duoc.bancoxyz.payment.PaymentModels.WithdrawalResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.SendResult;

class WithdrawalRequestedConsumerTest {
    private CoreOperationClient core;
    private KafkaTemplate<String, Object> kafka;
    private Acknowledgment acknowledgment;
    private WithdrawalRequestedConsumer consumer;
    private final WithdrawalRequestedEvent requested = new WithdrawalRequestedEvent(UUID.randomUUID(), UUID.randomUUID(),
            101, new BigDecimal("10000"), Instant.parse("2026-01-01T00:00:00Z"));

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        core = mock(CoreOperationClient.class);
        kafka = mock(KafkaTemplate.class);
        acknowledgment = mock(Acknowledgment.class);
        consumer = new WithdrawalRequestedConsumer(core, kafka, 100);
    }

    @Test
    void publishesCompletedOriginalEventBeforeAcknowledging() {
        var result = completed();
        when(core.withdraw(requested)).thenReturn(result);
        when(kafka.send(TopicNames.WITHDRAWAL_COMPLETED, "101", result.completed()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        consumer.consume(record(), acknowledgment);
        var ordered = inOrder(core, kafka, acknowledgment);
        ordered.verify(core).withdraw(requested);
        ordered.verify(kafka).send(TopicNames.WITHDRAWAL_COMPLETED, "101", result.completed());
        ordered.verify(acknowledgment).acknowledge();
    }

    @Test
    void publishesRejectedOriginalEventBeforeAcknowledging() {
        var rejected = new WithdrawalRejectedEvent(UUID.randomUUID(), requested.transactionId(), 101,
                requested.amount(), "Saldo insuficiente", Instant.now());
        when(core.withdraw(requested)).thenReturn(new WithdrawalResult("REJECTED", null, rejected));
        when(kafka.send(TopicNames.WITHDRAWAL_REJECTED, "101", rejected))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        consumer.consume(record(), acknowledgment);
        verify(kafka).send(TopicNames.WITHDRAWAL_REJECTED, "101", rejected);
        verify(acknowledgment).acknowledge();
    }

    @Test
    void acknowledgmentWaitsUntilThePublicationFutureCompletes() throws Exception {
        var result = completed();
        when(core.withdraw(requested)).thenReturn(result);
        var sent = new CountDownLatch(1);
        CompletableFuture<SendResult<String, Object>> future = new CompletableFuture<>();
        when(kafka.send(anyString(), anyString(), any())).thenAnswer(invocation -> {
            sent.countDown();
            return future;
        });
        consumer = new WithdrawalRequestedConsumer(core, kafka, 5000);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var run = executor.submit(() -> consumer.consume(record(), acknowledgment));
            assertThat(sent.await(2, TimeUnit.SECONDS)).isTrue();
            verifyNoInteractions(acknowledgment);
            future.complete(mock(SendResult.class));
            run.get(2, TimeUnit.SECONDS);
            verify(acknowledgment).acknowledge();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void failedPublicationDoesNotAcknowledge() {
        when(core.withdraw(requested)).thenReturn(completed());
        when(kafka.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("Test failure")));
        assertThatThrownBy(() -> consumer.consume(record(), acknowledgment)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(acknowledgment);
        verify(core, times(1)).withdraw(requested);
    }

    @Test
    void publicationTimeoutDoesNotAcknowledge() {
        when(core.withdraw(requested)).thenReturn(completed());
        when(kafka.send(anyString(), anyString(), any())).thenReturn(new CompletableFuture<>());
        assertThatThrownBy(() -> consumer.consume(record(), acknowledgment)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(acknowledgment);
    }

    @Test
    void immediateSendFailureDoesNotAcknowledge() {
        when(core.withdraw(requested)).thenReturn(completed());
        when(kafka.send(anyString(), anyString(), any())).thenThrow(new IllegalStateException("Test failure"));
        assertThatThrownBy(() -> consumer.consume(record(), acknowledgment)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(acknowledgment);
    }

    @Test
    void lostHttpResponseQueriesSameIdentifierAndPublishesStoredEvent() {
        var original = completed();
        when(core.withdraw(requested)).thenThrow(unknown());
        when(core.withdrawal(requested.transactionId(), requested)).thenReturn(original);
        when(kafka.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        consumer.consume(record(), acknowledgment);
        verify(core).withdrawal(requested.transactionId(), requested);
        verify(kafka).send(TopicNames.WITHDRAWAL_COMPLETED, "101", original.completed());
        verify(acknowledgment).acknowledge();
        verify(core, times(1)).withdraw(requested);
    }

    @Test
    void coreUnavailableAfterOneRecoveryQueryDoesNotAcknowledgeOrPublish() {
        when(core.withdraw(requested)).thenThrow(unknown());
        when(core.withdrawal(requested.transactionId(), requested)).thenThrow(unknown());
        assertThatThrownBy(() -> consumer.consume(record(), acknowledgment)).isInstanceOf(CoreRequestException.class);
        verify(core, times(1)).withdraw(requested);
        verify(core, times(1)).withdrawal(requested.transactionId(), requested);
        verifyNoInteractions(kafka, acknowledgment);
    }

    @Test
    void conflictingRequestIsNotConvertedIntoAnotherBankingResult() {
        when(core.withdraw(requested)).thenThrow(new CoreRequestException(HttpStatus.CONFLICT,
                new OperationError(requested.transactionId(), "CONFLICT", "Identificador incompatible")));
        assertThatThrownBy(() -> consumer.consume(record(), acknowledgment)).isInstanceOf(CoreRequestException.class);
        verifyNoInteractions(kafka, acknowledgment);
        verify(core, never()).withdrawal(any(), any());
    }

    @Test
    void inputAcknowledgmentFailureCanOnlyRepublishTheOriginalEventOnRedelivery() {
        var original = completed();
        when(core.withdraw(requested)).thenReturn(original);
        when(kafka.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        doThrow(new IllegalStateException("Test commit failure")).doNothing().when(acknowledgment).acknowledge();
        assertThatThrownBy(() -> consumer.consume(record(), acknowledgment)).isInstanceOf(IllegalStateException.class);
        consumer.consume(record(), acknowledgment);
        verify(kafka, times(2)).send(TopicNames.WITHDRAWAL_COMPLETED, "101", original.completed());
    }

    private WithdrawalResult completed() {
        return new WithdrawalResult("COMPLETED", new WithdrawalCompletedEvent(UUID.randomUUID(), requested.transactionId(),
                101, requested.amount(), new BigDecimal("90000"), Instant.parse("2026-01-01T00:01:00Z")), null);
    }

    private CoreRequestException unknown() {
        return new CoreRequestException(HttpStatus.SERVICE_UNAVAILABLE,
                new OperationError(requested.transactionId(), "UNKNOWN", "Resultado incierto de prueba"));
    }

    private ConsumerRecord<String, WithdrawalRequestedEvent> record() {
        return new ConsumerRecord<>(TopicNames.WITHDRAWAL_REQUESTED, 0, 7, "101", requested);
    }
}
