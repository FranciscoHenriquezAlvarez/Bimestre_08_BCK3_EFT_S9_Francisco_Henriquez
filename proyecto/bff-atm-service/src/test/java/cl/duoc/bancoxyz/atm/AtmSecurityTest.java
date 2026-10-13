package cl.duoc.bancoxyz.atm;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cl.duoc.bancoxyz.config.SecurityConfig;
import cl.duoc.bancoxyz.atm.kafka.AsyncWithdrawalProducer;
import cl.duoc.bancoxyz.atm.kafka.KafkaPublicationException;
import cl.duoc.bancoxyz.events.WithdrawalRequestedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.TimeoutException;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = AtmBffController.class, properties = "spring.cloud.config.enabled=false")
@Import(SecurityConfig.class)
class AtmSecurityTest {
    @Autowired MockMvc mvc;
    @MockBean AtmBankingClient core;
    @MockBean JwtDecoder decoder;
    @MockBean AsyncWithdrawalProducer producer;

    @Test
    void legacyLoginEndpointIsRemoved() throws Exception {
        mvc.perform(post("/auth/login").with(jwt().jwt(jwt -> jwt.claim("scope", "atm")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsRequestsWithoutToken() throws Exception {
        mvc.perform(get("/api/bff/atm/accounts/104/balance")).andExpect(status().isUnauthorized());
        verifyNoInteractions(core);
    }

    @Test
    void allowsMatchingScope() throws Exception {
        balanceExists();
        mvc.perform(get("/api/bff/atm/accounts/104/balance").with(jwt().jwt(jwt -> jwt.claim("scope", "atm"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accountId").value(104));
        verify(core).balance(104);
    }

    @Test
    void rejectsOtherChannelScopes() throws Exception {
        for (String scope : new String[]{"web", "mobile"}) {
            mvc.perform(get("/api/bff/atm/accounts/104/balance").with(jwt().jwt(jwt -> jwt.claim("scope", scope))))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(core);
    }

    @Test
    void rejectsInternalPaymentScope() throws Exception {
        mvc.perform(get("/api/bff/atm/accounts/104/balance")
                .with(jwt().jwt(token -> token.claim("scope", "account.operations"))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/bff/atm/accounts/104/withdraw")
                .with(jwt().jwt(token -> token.claim("scope", "account.operations")))
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":10000}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(core, producer);
    }

    @Test
    void rejectsInvalidBearerToken() throws Exception {
        when(decoder.decode("invalid-token")).thenThrow(new BadJwtException("Invalid token"));
        mvc.perform(get("/api/bff/atm/accounts/104/balance").header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(core);
    }

    @Test
    void convertsBearerTokenScopeUsingResourceServer() throws Exception {
        balanceExists();
        when(decoder.decode("valid-token")).thenReturn(Jwt.withTokenValue("valid-token")
                .header("alg", "RS256").subject("atm-client").claim("scope", "atm").build());
        mvc.perform(get("/api/bff/atm/accounts/104/balance").header(HttpHeaders.AUTHORIZATION, "Bearer valid-token"))
                .andExpect(status().isOk());
        verify(decoder).decode("valid-token");
        verify(core).balance(104);
    }

    @Test
    void authorizedWithdrawalKeepsAcceptedPendingResponse() throws Exception {
        mvc.perform(post("/api/bff/atm/accounts/104/withdraw")
                .with(jwt().jwt(jwt -> jwt.claim("scope", "atm")))
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":10000}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.transactionId").isNotEmpty());
        verify(producer).publish(any(WithdrawalRequestedEvent.class));
    }

    @Test
    void authorizedWithdrawalKeepsKafkaFailureResponse() throws Exception {
        assertWithdrawalFailure(new IllegalStateException("Broker unavailable"));
    }

    @Test
    void authorizedWithdrawalKeepsKafkaTimeoutResponse() throws Exception {
        assertWithdrawalFailure(new TimeoutException("No ACK"));
    }

    private void assertWithdrawalFailure(Throwable cause) throws Exception {
        doAnswer(invocation -> {
            WithdrawalRequestedEvent event = invocation.getArgument(0);
            throw new KafkaPublicationException(event.transactionId(), cause);
        }).when(producer).publish(any(WithdrawalRequestedEvent.class));
        String response = mvc.perform(post("/api/bff/atm/accounts/104/withdraw")
                .with(jwt().jwt(jwt -> jwt.claim("scope", "atm")))
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":10000}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.status").value("NOT_PUBLISHED"))
                .andExpect(jsonPath("$.message").value("No fue posible publicar la solicitud de retiro en Kafka"))
                .andReturn().getResponse().getContentAsString();
        ArgumentCaptor<WithdrawalRequestedEvent> event =
                ArgumentCaptor.forClass(WithdrawalRequestedEvent.class);
        verify(producer).publish(event.capture());
        assertEquals(event.getValue().transactionId().toString(),
                new ObjectMapper().readTree(response).get("transactionId").asText());
    }

    private void balanceExists() {
        when(core.balance(104)).thenReturn(new AtmBankingClient.Balance(104, new BigDecimal("10100")));
    }
}
