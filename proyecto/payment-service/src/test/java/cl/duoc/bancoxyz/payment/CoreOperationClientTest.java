package cl.duoc.bancoxyz.payment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.*;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import cl.duoc.bancoxyz.payment.CoreOperationClient.CoreRequestException;
import cl.duoc.bancoxyz.payment.PaymentModels.AccountCommand;

class CoreOperationClientTest {
    private static final UUID ID = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private MockRestServiceServer server;
    private OAuth2AuthorizedClientManager tokens;
    private CoreOperationClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        tokens = mock(OAuth2AuthorizedClientManager.class);
        ClientRegistration registration = ClientRegistration.withRegistrationId("core-operations")
                .clientId("payment-client").authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .tokenUri("http://authorization-test/oauth2/token").scope("account.operations").build();
        OAuth2AccessToken token = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "internal-test-token",
                Instant.now(), Instant.now().plusSeconds(300));
        when(tokens.authorize(any())).thenReturn(new OAuth2AuthorizedClient(registration, "payment-service", token));
        client = new CoreOperationClient(builder, tokens, mock(OAuth2AuthorizedClientService.class), "http://core-banking-service");
    }

    @AfterEach
    void verifyRequestsAndClearContext() {
        server.verify();
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @CsvSource({"debit,DEBIT", "credit,CREDIT", "transfer,TRANSFER"})
    void sendsMatchingInternalRouteAndInternalCredential(String action, String type) {
        Jwt caller = Jwt.withTokenValue("caller-web-token").header("alg", "RS256").claim("scope", "web").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(caller));
        server.expect(requestTo("http://core-banking-service/api/core/internal/operations/" + action))
                .andExpect(header("Authorization", "Bearer internal-test-token"))
                .andExpect(jsonPath("$.operationId").value(ID.toString()))
                .andRespond(withSuccess(result(type, "COMPLETED"), MediaType.APPLICATION_JSON));
        assertThat(client.execute(ID, action, type, command()).type()).isEqualTo(type);
        verify(tokens).authorize(argThat(request -> request.getClientRegistrationId().equals("core-operations")
                && request.getPrincipal().getName().equals("payment-service")));
    }

    @Test
    void businessRejectionIsReturnedWithoutRetry() {
        expectPost().andRespond(withSuccess(result("DEBIT", "REJECTED"), MediaType.APPLICATION_JSON));
        assertThat(client.execute(ID, "debit", "DEBIT", command()).status()).isEqualTo("REJECTED");
        verify(tokens, times(1)).authorize(any());
    }

    @ParameterizedTest
    @CsvSource({"400,INVALID_REQUEST", "404,NOT_FOUND", "409,CONFLICT"})
    void preservesKnownHttpErrors(int status, String state) {
        expectPost().andRespond(withStatus(HttpStatus.valueOf(status)));
        CoreRequestException error = catchThrowableOfType(() -> client.execute(ID, "debit", "DEBIT", command()),
                CoreRequestException.class);
        assertThat(error.status().value()).isEqualTo(status);
        assertThat(error.result().operationId()).isEqualTo(ID);
        assertThat(error.result().status()).isEqualTo(state);
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    void internalAuthorizationErrorsNeverLookLikeCallerAuthenticationErrors(int status) {
        expectPost().andRespond(withStatus(HttpStatus.valueOf(status)));
        CoreRequestException error = catchThrowableOfType(() -> client.execute(ID, "debit", "DEBIT", command()),
                CoreRequestException.class);
        assertThat(error.status()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(error.result().status()).isEqualTo("UNKNOWN");
    }

    @Test
    void serverErrorLeavesTheOperationUnknown() {
        expectPost().andRespond(withServerError());
        assertUnknown(() -> client.execute(ID, "debit", "DEBIT", command()));
    }

    @Test
    void timeoutDoesNotRetryOrReportRejection() {
        expectPost().andRespond(withException(new SocketTimeoutException("Test timeout")));
        assertUnknown(() -> client.execute(ID, "debit", "DEBIT", command()));
        verify(tokens, times(1)).authorize(any());
    }

    @Test
    void connectionFailureKeepsTheIdentifier() {
        expectPost().andRespond(withException(new IOException("Test connection failure")));
        assertUnknown(() -> client.execute(ID, "debit", "DEBIT", command()));
    }

    @Test
    void queryRetrievesTheResultUsingTheSameIdentifier() {
        server.expect(requestTo("http://core-banking-service/api/core/internal/operations/" + ID))
                .andExpect(header("Authorization", "Bearer internal-test-token"))
                .andRespond(withSuccess(result("DEBIT", "COMPLETED"), MediaType.APPLICATION_JSON));
        assertThat(client.operation(ID).operationId()).isEqualTo(ID);
    }

    @Test
    void unavailableTokenDoesNotCallCore() {
        when(tokens.authorize(any())).thenThrow(new OAuth2AuthorizationException(new OAuth2Error("invalid_client")));
        assertUnknown(() -> client.execute(ID, "debit", "DEBIT", command()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{}", "{\"status\":\"COMPLETED\"}", "invalid json"})
    void missingOrMalformedResponseNeverReportsSuccess(String body) {
        expectPost().andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertUnknown(() -> client.execute(ID, "debit", "DEBIT", command()));
    }

    @Test
    void wrongIdentifierWrongTypeAndPendingResultsAreUnknown() {
        for (String body : new String[] {result("DEBIT", "PENDING"), result("CREDIT", "COMPLETED"),
                result("DEBIT", "COMPLETED").replace(ID.toString(), UUID.randomUUID().toString())}) {
            server.reset();
            expectPost().andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
            assertUnknown(() -> client.execute(ID, "debit", "DEBIT", command()));
            server.verify();
        }
    }

    private org.springframework.test.web.client.ResponseActions expectPost() {
        return server.expect(requestTo("http://core-banking-service/api/core/internal/operations/debit"));
    }

    private void assertUnknown(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        CoreRequestException error = catchThrowableOfType(call, CoreRequestException.class);
        assertThat(error.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(error.result().operationId()).isEqualTo(ID);
        assertThat(error.result().status()).isEqualTo("UNKNOWN");
    }

    private AccountCommand command() {
        return new AccountCommand(ID, 101, new BigDecimal("10"));
    }

    private String result(String type, String status) {
        return "{\"operationId\":\"" + ID + "\",\"type\":\"" + type + "\",\"status\":\"" + status
                + "\",\"sourceBalance\":90,\"completedAt\":\"2026-01-01T00:00:00Z\"}";
    }
}
