package cl.duoc.bancoxyz.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class InternalOperationControllerIntegrationTest extends OperationalAccountsIntegrationSupport {
    private static final String ROOT = "/api/core/internal/operations";
    private static final UUID ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    @Autowired MockMvc mvc;
    @MockBean JwtDecoder decoder;

    @Test
    void debitUsesRealTransactionalService() throws Exception {
        account(101, "100");
        execute("debit", accountBody("10"), 200, "COMPLETED");
        assertThat(balance(101)).isEqualByComparingTo("90");
        assertThat(operationCount()).isEqualTo(1);
        assertThat(operations.operation(ID).type()).isEqualTo(AccountOperationService.OperationType.DEBIT);
    }

    @Test
    void creditUsesRealTransactionalService() throws Exception {
        account(101, "100");
        execute("credit", accountBody("10"), 200, "COMPLETED");
        assertThat(balance(101)).isEqualByComparingTo("110");
        assertThat(operations.operation(ID).type()).isEqualTo(AccountOperationService.OperationType.CREDIT);
    }

    @Test
    void transferUpdatesBothAccountsAndPersistsResult() throws Exception {
        account(101, "100");
        account(102, "20");
        execute("transfer", transferBody("30"), 200, "COMPLETED");
        assertThat(balance(101)).isEqualByComparingTo("70");
        assertThat(balance(102)).isEqualByComparingTo("50");
        mvc.perform(get(ROOT + "/" + ID).with(jwt().jwt(token -> token.claim("scope", "account.operations"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.type").value("TRANSFER"))
                .andExpect(jsonPath("$.sourceBalance").value(70)).andExpect(jsonPath("$.targetBalance").value(50));
    }

    @Test
    void repeatedHttpCommandReturnsOriginalResultAndDifferentDataConflicts() throws Exception {
        account(101, "100");
        execute("debit", accountBody("10"), 200, "COMPLETED");
        execute("debit", accountBody("10.00"), 200, "COMPLETED");
        execute("debit", accountBody("11"), 409, null);
        assertThat(balance(101)).isEqualByComparingTo("90");
        assertThat(operationCount()).isEqualTo(1);
    }

    @Test
    void accountAndAmountBusinessRejectionsArePersisted() throws Exception {
        execute("debit", accountBody("10"), 200, "REJECTED");
        assertThat(operations.operation(ID).rejectionReason()).contains("no encontrada");
        jdbc.update("DELETE FROM operaciones_cuenta");
        account(101, "100");
        execute("credit", accountBody("0"), 200, "REJECTED");
        assertThat(balance(101)).isEqualByComparingTo("100");
        assertThat(operations.operation(ID).rejectionReason()).contains("mayor a cero");
    }

    @Test
    void malformedAndIncompleteCommandsDoNotWriteOperations() throws Exception {
        for (String body : new String[] {"{}", "{\"operationId\":\"invalid\",\"accountId\":101,\"amount\":10}",
                accountBody("1.001")}) {
            execute("debit", body, 400, null);
        }
        assertThat(operationCount()).isZero();
    }

    @Test
    void queriesReturnNotFoundOrBadRequest() throws Exception {
        mvc.perform(get(ROOT + "/" + ID).with(jwt().jwt(token -> token.claim("scope", "account.operations"))))
                .andExpect(status().isNotFound());
        mvc.perform(get(ROOT + "/invalid").with(jwt().jwt(token -> token.claim("scope", "account.operations"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void channelAndMissingScopesCannotExecuteOrReadInternalOperations() throws Exception {
        for (String scope : new String[] {"web", "mobile", "atm", "other", ""}) {
            for (String action : new String[] {"debit", "credit", "transfer"}) {
                mvc.perform(post(ROOT + "/" + action).contentType(MediaType.APPLICATION_JSON)
                        .content(accountBody("10")).with(jwt().jwt(token -> token.claim("scope", scope))))
                        .andExpect(status().isForbidden());
            }
            mvc.perform(get(ROOT + "/" + ID).with(jwt().jwt(token -> token.claim("scope", scope))))
                    .andExpect(status().isForbidden());
        }
        assertThat(operationCount()).isZero();
    }

    @Test
    void requestsWithoutTokenAndInvalidTokenAreUnauthorized() throws Exception {
        when(decoder.decode("invalid-token")).thenThrow(new BadJwtException("Invalid token"));
        for (String action : new String[] {"debit", "credit", "transfer"}) {
            mvc.perform(post(ROOT + "/" + action).contentType(MediaType.APPLICATION_JSON).content(accountBody("10")))
                    .andExpect(status().isUnauthorized());
            mvc.perform(post(ROOT + "/" + action).contentType(MediaType.APPLICATION_JSON).content(accountBody("10"))
                    .header("Authorization", "Bearer invalid-token")).andExpect(status().isUnauthorized());
        }
        mvc.perform(get(ROOT + "/" + ID)).andExpect(status().isUnauthorized());
        mvc.perform(get(ROOT + "/" + ID).header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isUnauthorized());
        assertThat(operationCount()).isZero();
    }

    @Test
    void internalScopeCannotUseOtherMethodsOrUnlistedPaths() throws Exception {
        mvc.perform(get(ROOT + "/debit").with(jwt().jwt(token -> token.claim("scope", "account.operations"))))
                .andExpect(status().isBadRequest());
        mvc.perform(post(ROOT + "/" + ID).with(jwt().jwt(token -> token.claim("scope", "account.operations"))))
                .andExpect(status().isForbidden());
        mvc.perform(post(ROOT + "/other").with(jwt().jwt(token -> token.claim("scope", "account.operations"))))
                .andExpect(status().isForbidden());
        assertThat(operationCount()).isZero();
    }

    private void execute(String action, String body, int expectedStatus, String expectedState) throws Exception {
        var response = mvc.perform(post(ROOT + "/" + action).contentType(MediaType.APPLICATION_JSON).content(body)
                .with(jwt().jwt(token -> token.claim("scope", "account.operations"))))
                .andExpect(status().is(expectedStatus));
        if (expectedState != null) {
            response.andExpect(jsonPath("$.operationId").value(ID.toString()))
                    .andExpect(jsonPath("$.status").value(expectedState));
        }
    }

    private String accountBody(String amount) {
        return "{\"operationId\":\"" + ID + "\",\"accountId\":101,\"amount\":" + amount + "}";
    }

    private String transferBody(String amount) {
        return "{\"operationId\":\"" + ID + "\",\"sourceAccountId\":101,\"targetAccountId\":102,\"amount\":"
                + amount + "}";
    }
}
