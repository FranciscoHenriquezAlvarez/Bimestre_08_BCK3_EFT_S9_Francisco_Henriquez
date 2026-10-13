package cl.duoc.bancoxyz.payment;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import cl.duoc.bancoxyz.payment.CoreOperationClient.CoreRequestException;
import cl.duoc.bancoxyz.payment.PaymentModels.*;

@WebMvcTest(properties = "spring.cloud.config.enabled=false")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ContextConfiguration(classes = {PaymentController.class, PaymentService.class, PaymentSecurityConfig.class})
class PaymentControllerTest {
    private static final UUID ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final String BODY = "{\"operationId\":\"" + ID + "\",\"accountId\":101,\"amount\":10}";
    @Autowired MockMvc mvc;
    @MockBean CoreOperationClient core;
    @MockBean JwtDecoder decoder;

    @Test
    void paymentSelectsDebitAndPreservesIdentifier() throws Exception {
        when(core.execute(eq(ID), eq("debit"), eq("DEBIT"), any())).thenReturn(result("DEBIT", "COMPLETED"));
        mvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON).content(BODY)
                .with(jwt().jwt(token -> token.claim("scope", "web"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.operationId").value(ID.toString()))
                .andExpect(jsonPath("$.type").value("DEBIT")).andExpect(jsonPath("$.status").value("COMPLETED"));
        verify(core).execute(ID, "debit", "DEBIT", new AccountCommand(ID, 101, new BigDecimal("10")));
    }

    @Test
    void mobileDepositSelectsCredit() throws Exception {
        when(core.execute(eq(ID), eq("credit"), eq("CREDIT"), any())).thenReturn(result("CREDIT", "COMPLETED"));
        mvc.perform(post("/api/payments/deposits").contentType(MediaType.APPLICATION_JSON).content(BODY)
                .with(jwt().jwt(token -> token.claim("scope", "mobile"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.type").value("CREDIT"));
        verify(core).execute(ID, "credit", "CREDIT", new AccountCommand(ID, 101, new BigDecimal("10")));
    }

    @Test
    void transferSelectsTransferAndPreservesBothAccounts() throws Exception {
        when(core.execute(eq(ID), eq("transfer"), eq("TRANSFER"), any()))
                .thenReturn(result("TRANSFER", "COMPLETED"));
        mvc.perform(post("/api/payments/transfers").contentType(MediaType.APPLICATION_JSON)
                .content("{\"operationId\":\"" + ID + "\",\"sourceAccountId\":101,\"targetAccountId\":102,\"amount\":10}")
                .with(jwt().jwt(token -> token.claim("scope", "web"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.type").value("TRANSFER"));
        verify(core).execute(ID, "transfer", "TRANSFER", new TransferCommand(ID, 101, 102, new BigDecimal("10")));
    }

    @Test
    void businessRejectionRemainsARejectedResult() throws Exception {
        when(core.execute(eq(ID), eq("debit"), eq("DEBIT"), any())).thenReturn(result("DEBIT", "REJECTED"));
        mvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON).content(BODY)
                .with(jwt().jwt(token -> token.claim("scope", "web"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectionReason").value("Saldo insuficiente"));
    }

    @Test
    void missingRequiredDataAndMalformedJsonNeverCallCore() throws Exception {
        for (String body : new String[] {"{}", "{\"accountId\":101,\"amount\":10}",
                "{\"operationId\":\"" + ID + "\",\"amount\":10}",
                "{\"operationId\":\"" + ID + "\",\"accountId\":101}",
                "{\"operationId\":\"invalid\",\"accountId\":101,\"amount\":10}",
                "{\"operationId\":\"" + ID + "\",\"accountId\":101,\"amount\":\"invalid\"}"}) {
            mvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON).content(body)
                    .with(jwt().jwt(token -> token.claim("scope", "web"))))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/payments/transfers").contentType(MediaType.APPLICATION_JSON).content(BODY)
                .with(jwt().jwt(token -> token.claim("scope", "mobile")))).andExpect(status().isBadRequest());
        verifyNoInteractions(core);
    }

    @Test
    void retriesSendTheSameIdentifierAndData() throws Exception {
        when(core.execute(eq(ID), eq("debit"), eq("DEBIT"), any())).thenReturn(result("DEBIT", "COMPLETED"));
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON).content(BODY)
                    .with(jwt().jwt(token -> token.claim("scope", "web")))).andExpect(status().isOk());
        }
        verify(core, times(2)).execute(ID, "debit", "DEBIT", new AccountCommand(ID, 101, new BigDecimal("10")));
    }

    @Test
    void queryReturnsPersistedOperation() throws Exception {
        when(core.operation(ID)).thenReturn(result("DEBIT", "COMPLETED"));
        mvc.perform(get("/api/payments/operations/" + ID).with(jwt().jwt(token -> token.claim("scope", "mobile"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.operationId").value(ID.toString()));
        verify(core).operation(ID);
    }

    @Test
    void conflictAndUnknownResultsKeepTheirIdentifierAndHttpStatus() throws Exception {
        for (HttpStatus status : new HttpStatus[] {HttpStatus.CONFLICT, HttpStatus.SERVICE_UNAVAILABLE,
                HttpStatus.BAD_GATEWAY}) {
            String state = status == HttpStatus.CONFLICT ? "CONFLICT" : "UNKNOWN";
            when(core.execute(eq(ID), eq("debit"), eq("DEBIT"), any())).thenThrow(
                    new CoreRequestException(status, new OperationError(ID, state, "Conserve el identificador")));
            mvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON).content(BODY)
                    .with(jwt().jwt(token -> token.claim("scope", "web"))))
                    .andExpect(status().is(status.value())).andExpect(jsonPath("$.operationId").value(ID.toString()))
                    .andExpect(jsonPath("$.status").value(state));
        }
    }

    @Test
    void atmInternalAndMissingScopesCannotUseAnyPublicOperation() throws Exception {
        for (String scope : new String[] {"atm", "account.operations", "other", ""}) {
            for (String path : new String[] {"/api/payments", "/api/payments/deposits", "/api/payments/transfers"}) {
                mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .with(jwt().jwt(token -> token.claim("scope", scope)))).andExpect(status().isForbidden());
            }
            mvc.perform(get("/api/payments/operations/" + ID).with(jwt().jwt(token -> token.claim("scope", scope))))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(core);
    }

    @Test
    void noTokenAndInvalidTokenAreUnauthorizedOnEveryPublicRoute() throws Exception {
        when(decoder.decode("invalid-token")).thenThrow(new BadJwtException("Invalid token"));
        for (String path : new String[] {"/api/payments", "/api/payments/deposits", "/api/payments/transfers"}) {
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isUnauthorized());
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(BODY)
                    .header("Authorization", "Bearer invalid-token")).andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/api/payments/operations/" + ID)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/payments/operations/" + ID).header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(core);
    }

    @Test
    void unlistedMethodsAndPathsAreDenied() throws Exception {
        mvc.perform(delete("/api/payments/operations/" + ID).with(jwt().jwt(token -> token.claim("scope", "web"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/payments").with(jwt().jwt(token -> token.claim("scope", "mobile"))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/payments/other").with(jwt().jwt(token -> token.claim("scope", "web"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(core);
    }

    private OperationResult result(String type, String status) {
        return new OperationResult(ID, type, status, new BigDecimal("90"), null,
                "REJECTED".equals(status) ? "Saldo insuficiente" : null, Instant.parse("2026-01-01T00:00:00Z"));
    }
}
