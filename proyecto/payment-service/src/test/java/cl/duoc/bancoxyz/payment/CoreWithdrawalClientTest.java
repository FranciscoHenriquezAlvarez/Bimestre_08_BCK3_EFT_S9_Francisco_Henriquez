package cl.duoc.bancoxyz.payment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import cl.duoc.bancoxyz.events.WithdrawalRequestedEvent;
import cl.duoc.bancoxyz.payment.CoreOperationClient.CoreRequestException;
import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.*;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class CoreWithdrawalClientTest {
    private final WithdrawalRequestedEvent requested = new WithdrawalRequestedEvent(UUID.randomUUID(),
            UUID.randomUUID(), 101, new BigDecimal("10000"), Instant.parse("2026-01-01T00:00:00Z"));
    private MockRestServiceServer server;
    private OAuth2AuthorizedClientManager tokens;
    private OAuth2AuthorizedClientService clients;
    private CoreOperationClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        tokens = mock(OAuth2AuthorizedClientManager.class);
        clients = mock(OAuth2AuthorizedClientService.class);
        var registration = ClientRegistration.withRegistrationId("core-operations").clientId("payment-client")
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS).scope("account.operations")
                .tokenUri("http://authorization-test/oauth2/token").build();
        var token = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "internal-test-token",
                Instant.now(), Instant.now().plusSeconds(300));
        when(tokens.authorize(any())).thenReturn(new OAuth2AuthorizedClient(registration, "payment-service", token));
        client = new CoreOperationClient(builder, tokens, clients, "http://core-banking-service");
    }

    @AfterEach
    void verifyRequests() {
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"COMPLETED", "REJECTED"})
    void returnsOriginalFinalEventUsingItsOwnInternalIdentity(String status) {
        server.expect(requestTo("http://core-banking-service/api/core/internal/withdrawals"))
                .andExpect(method(HttpMethod.POST)).andExpect(header("Authorization", "Bearer internal-test-token"))
                .andExpect(jsonPath("$.transactionId").value(requested.transactionId().toString()))
                .andExpect(jsonPath("$.eventId").value(requested.eventId().toString()))
                .andRespond(withSuccess(result(status), MediaType.APPLICATION_JSON));
        assertThat(client.withdraw(requested).status()).isEqualTo(status);
        verify(tokens).authorize(argThat(request -> request.getClientRegistrationId().equals("core-operations")
                && request.getPrincipal().getName().equals("payment-service")));
    }

    @Test
    void recoveryQueryUsesTheOriginalTransactionId() {
        server.expect(requestTo("http://core-banking-service/api/core/internal/withdrawals/" + requested.transactionId()))
                .andExpect(method(HttpMethod.GET)).andExpect(header("Authorization", "Bearer internal-test-token"))
                .andRespond(withSuccess(result("COMPLETED"), MediaType.APPLICATION_JSON));
        assertThat(client.withdrawal(requested.transactionId(), requested).completed().transactionId())
                .isEqualTo(requested.transactionId());
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    void rejectedCredentialIsEvictedAndCommandIsNotAutomaticallyRepeated(int status) {
        expectPost().andRespond(withStatus(HttpStatus.valueOf(status)));
        CoreRequestException error = catchThrowableOfType(() -> client.withdraw(requested), CoreRequestException.class);
        assertThat(error.result().status()).isEqualTo("UNKNOWN");
        assertThat(error.status()).isEqualTo(HttpStatus.BAD_GATEWAY);
        verify(clients).removeAuthorizedClient("core-operations", "payment-service");
        verify(tokens, times(1)).authorize(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"account", "amount", "transaction", "pending"})
    void incompatibleOrNonFinalResponseIsUnknown(String mismatch) {
        String body = switch (mismatch) {
            case "account" -> result("COMPLETED").replace("\"accountId\":101", "\"accountId\":102");
            case "amount" -> result("COMPLETED").replace("\"amount\":10000", "\"amount\":20000");
            case "transaction" -> result("COMPLETED").replace(requested.transactionId().toString(), UUID.randomUUID().toString());
            default -> "{\"status\":\"PENDING\"}";
        };
        expectPost().andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertUnknown();
    }

    @Test
    void lostHttpResponsePreservesIdentityAndDoesNotRetryTheCommand() {
        expectPost().andRespond(withException(new SocketTimeoutException("Respuesta de prueba perdida")));
        assertUnknown();
        verify(tokens, times(1)).authorize(any());
    }

    @Test
    void unavailableTokenCannotSendAnUnauthenticatedRequest() {
        when(tokens.authorize(any())).thenReturn(null);
        assertUnknown();
    }

    @Test
    void identifierConflictRemainsAConflictAndDoesNotDiscardCredential() {
        expectPost().andRespond(withStatus(HttpStatus.CONFLICT));
        CoreRequestException error = catchThrowableOfType(() -> client.withdraw(requested), CoreRequestException.class);
        assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(error.result().operationId()).isEqualTo(requested.transactionId());
        verifyNoInteractions(clients);
    }

    private org.springframework.test.web.client.ResponseActions expectPost() {
        return server.expect(requestTo("http://core-banking-service/api/core/internal/withdrawals"));
    }

    private void assertUnknown() {
        CoreRequestException error = catchThrowableOfType(() -> client.withdraw(requested), CoreRequestException.class);
        assertThat(error.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(error.result().operationId()).isEqualTo(requested.transactionId());
        assertThat(error.result().status()).isEqualTo("UNKNOWN");
    }

    private String result(String status) {
        String common = "\"eventId\":\"77777777-7777-7777-7777-777777777777\",\"transactionId\":\""
                + requested.transactionId() + "\",\"accountId\":101,\"amount\":10000,\"occurredAt\":\"2026-01-01T00:00:00Z\"";
        return "COMPLETED".equals(status)
                ? "{\"status\":\"COMPLETED\",\"completed\":{" + common + ",\"finalBalance\":90000},\"rejected\":null}"
                : "{\"status\":\"REJECTED\",\"completed\":null,\"rejected\":{" + common + ",\"reason\":\"Saldo insuficiente\"}}";
    }
}
