package cl.duoc.bancoxyz.atm;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import cl.duoc.bancoxyz.atm.kafka.AsyncWithdrawalProducer;
import cl.duoc.bancoxyz.atm.kafka.KafkaPublicationException;
import cl.duoc.bancoxyz.events.WithdrawalRequestedEvent;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class AtmBffControllerTest {

    @Test
    void withdrawReturnsServiceUnavailableWhenKafkaPublicationFails() throws Exception {
        assertPublicationFailureResponse(new IllegalStateException("Broker unavailable"));
    }

    @Test
    void withdrawReturnsServiceUnavailableWhenKafkaPublicationTimesOut() throws Exception {
        assertPublicationFailureResponse(new TimeoutException("No ACK"));
    }

    private void assertPublicationFailureResponse(Throwable cause) throws Exception {
        AsyncWithdrawalProducer producer = mock(AsyncWithdrawalProducer.class);
        doAnswer(invocation -> {
            WithdrawalRequestedEvent event = invocation.getArgument(0);
            throw new KafkaPublicationException(event.transactionId(), cause);
        }).when(producer).publish(any(WithdrawalRequestedEvent.class));
        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new AtmBffController(mock(AtmBankingClient.class), producer)).build();

        MvcResult result = mvc.perform(
                post("/api/bff/atm/accounts/104/withdraw")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":10000}"))
                .andExpect(status().isServiceUnavailable())
                .andReturn();

        JsonNode body = new ObjectMapper()
                .readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals("NOT_PUBLISHED", body.get("status").asText());
        assertNotEquals("PENDING", body.get("status").asText());
        assertEquals("No fue posible publicar la solicitud de retiro en Kafka", body.get("message").asText());
        ArgumentCaptor<WithdrawalRequestedEvent> event = ArgumentCaptor.forClass(WithdrawalRequestedEvent.class);
        verify(producer).publish(event.capture());
        assertEquals(event.getValue().transactionId().toString(), body.get("transactionId").asText());
    }

    @Test
    void withdrawPublishesKafkaEventAndAcceptsPendingTransaction() {
        AsyncWithdrawalProducer producer = mock(AsyncWithdrawalProducer.class);
        AtmBffController controller = new AtmBffController(mock(AtmBankingClient.class), producer);

        ResponseEntity<AtmBffController.AsyncWithdrawalAccepted> response = controller.withdraw(104,
                new AtmBffController.WithdrawRequest(new BigDecimal("10000")));

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertNotNull(response.getBody().transactionId());
        assertEquals("PENDING", response.getBody().status());
        ArgumentCaptor<WithdrawalRequestedEvent> event = ArgumentCaptor.forClass(WithdrawalRequestedEvent.class);
        verify(producer).publish(event.capture());
        assertNotNull(event.getValue().eventId());
        assertEquals(response.getBody().transactionId(), event.getValue().transactionId());
        assertEquals(104, event.getValue().accountId());
        assertEquals(new BigDecimal("10000"), event.getValue().amount());
    }
}
