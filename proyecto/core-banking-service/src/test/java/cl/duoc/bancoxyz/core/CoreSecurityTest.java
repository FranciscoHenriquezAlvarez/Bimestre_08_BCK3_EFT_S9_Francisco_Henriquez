package cl.duoc.bancoxyz.core;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cl.duoc.bancoxyz.core.kafka.AsyncWithdrawalService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(properties = {"spring.cloud.config.enabled=false", "bank.config-origin=Configuracion de prueba local"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ContextConfiguration(classes = {CoreBankingController.class, ConfigOriginController.class, CoreSecurityConfig.class})
class CoreSecurityTest {
    private static final UUID TRANSACTION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String ASYNC_STATUS_PATH = "/api/core/accounts/async-withdrawals/" + TRANSACTION_ID;
    @Autowired MockMvc mvc;
    @MockBean BankingService service;
    @MockBean AsyncWithdrawalService withdrawals;
    @MockBean JwtDecoder decoder;

    @Test
    void rejectsDirectBankingAccessWithoutToken() throws Exception {
        for (String path : readPermissions().keySet()) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(service, withdrawals);
    }

    @Test
    void allowsAuthenticatedOAuth2Token() throws Exception {
        accountExists();
        mvc.perform(get("/api/core/accounts/104/balance").with(jwt().jwt(jwt -> jwt.claim("scope", "atm"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accountId").value(104));
        verify(service).account(104);
    }

    @Test
    void rejectsInvalidBearerToken() throws Exception {
        when(decoder.decode("invalid-token")).thenThrow(new BadJwtException("Invalid token"));
        mvc.perform(get("/api/core/accounts/104/balance").header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void acceptsBearerTokenPropagatedByBff() throws Exception {
        accountExists();
        for (String channel : Set.of("web", "mobile", "atm")) {
            String token = "propagated-" + channel + "-token";
            when(decoder.decode(token)).thenReturn(Jwt.withTokenValue(token)
                    .header("alg", "RS256").subject(channel + "-client").claim("scope", channel).build());
            String path = "/api/core/accounts/104" + ("atm".equals(channel) ? "/balance" : "");
            mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk());
            verify(decoder).decode(token);
        }
        verify(service, times(3)).account(104);
    }

    @Test
    void webAndMobileCanReadTheSharedAccountQuery() throws Exception {
        accountExists();
        for (String channel : Set.of("web", "mobile")) {
            mvc.perform(get("/api/core/accounts/104").with(jwt().jwt(token -> token.claim("scope", channel))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.accountId").value(104))
                    .andExpect(jsonPath("$.accountName").value("Cuenta"))
                    .andExpect(jsonPath("$.finalBalance").value(10100));
        }
        verify(service, times(2)).account(104);
    }

    @Test
    void webCanReadAnnualStatement() throws Exception {
        when(service.annualStatement(104)).thenReturn(new BankingService.WebStatement(104, 1,
                new BigDecimal("100"), BigDecimal.ZERO, new BigDecimal("100")));
        mvc.perform(get("/api/core/accounts/104/annual-statement")
                .with(jwt().jwt(token -> token.claim("scope", "web"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accountId").value(104))
                .andExpect(jsonPath("$.movementCount").value(1)).andExpect(jsonPath("$.netBalance").value(100));
        verify(service).annualStatement(104);
    }

    @Test
    void mobileCanReadLatestMovements() throws Exception {
        when(service.latestMovements(104)).thenReturn(List.of(new BankingService.Movement(
                LocalDate.of(2026, 1, 1), "DEPOSITO", new BigDecimal("100"))));
        mvc.perform(get("/api/core/accounts/104/movements/latest")
                .with(jwt().jwt(token -> token.claim("scope", "mobile"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].date").value("2026-01-01"))
                .andExpect(jsonPath("$[0].transaction").value("DEPOSITO"));
        verify(service).latestMovements(104);
    }

    @Test
    void atmCanReadAsyncWithdrawalStatus() throws Exception {
        when(withdrawals.asyncWithdrawalStatus(TRANSACTION_ID)).thenReturn(
                new AsyncWithdrawalService.TransactionStatus(TRANSACTION_ID, 104, new BigDecimal("10000"),
                        "COMPLETED", new BigDecimal("50000"), null, Instant.EPOCH));
        mvc.perform(get(ASYNC_STATUS_PATH).with(jwt().jwt(token -> token.claim("scope", "atm"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.transactionId").value(TRANSACTION_ID.toString()))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        verify(withdrawals).asyncWithdrawalStatus(TRANSACTION_ID);
    }

    @Test
    void existingQueriesRejectEveryInsufficientScope() throws Exception {
        for (var permission : readPermissions().entrySet()) {
            for (String scope : Set.of("web", "mobile", "atm", "other")) {
                if (!permission.getValue().contains(scope)) {
                    mvc.perform(get(permission.getKey()).with(jwt().jwt(token -> token.claim("scope", scope))))
                            .andExpect(status().isForbidden());
                }
            }
        }
        verifyNoInteractions(service, withdrawals);
    }

    @Test
    void internalScopeDoesNotAuthorizeChannelQueries() throws Exception {
        for (String path : readPermissions().keySet()) {
            mvc.perform(get(path).with(jwt().jwt(token -> token.claim("scope", "account.operations"))))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(service, withdrawals);
    }

    @Test
    void tokenWithoutScopeCannotReadExistingQueries() throws Exception {
        for (String path : readPermissions().keySet()) {
            mvc.perform(get(path).with(jwt().jwt(token -> token.claims(claims -> claims.remove("scope")))))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(service, withdrawals);
    }

    @Test
    void asyncWithdrawalPrefixCannotFallThroughToAccountPermissions() throws Exception {
        for (String scope : Set.of("web", "mobile", "atm", "account.operations")) {
            mvc.perform(get("/api/core/accounts/async-withdrawals")
                    .with(jwt().jwt(token -> token.claim("scope", scope))))
                    .andExpect(status().isForbidden());
            mvc.perform(post(ASYNC_STATUS_PATH).with(jwt().jwt(token -> token.claim("scope", scope))))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(service, withdrawals);
    }

    @Test
    void unlistedPathsAreDeniedEvenWithValidScopes() throws Exception {
        for (String scope : Set.of("web", "mobile", "atm", "account.operations")) {
            mvc.perform(get("/api/core/unlisted").with(jwt().jwt(token -> token.claim("scope", scope))))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(service, withdrawals);
    }

    @Test
    void queryPermissionsDoNotAuthorizeOtherHttpMethods() throws Exception {
        for (String path : readPermissions().keySet()) {
            mvc.perform(post(path).with(jwt().jwt(token -> token.claim("scope", "web mobile atm account.operations"))))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(service, withdrawals);
    }

    @Test
    void configurationOriginRemainsAvailableToExistingChannels() throws Exception {
        for (String scope : Set.of("web", "mobile", "atm")) {
            mvc.perform(get("/api/config/origin").with(jwt().jwt(token -> token.claim("scope", scope))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.configOrigin").value("Configuracion de prueba local"));
        }
    }

    private Map<String, Set<String>> readPermissions() {
        return Map.of(
                "/api/core/accounts/104", Set.of("web", "mobile"),
                "/api/core/accounts/104/annual-statement", Set.of("web"),
                "/api/core/accounts/104/movements/latest", Set.of("mobile"),
                "/api/core/accounts/104/balance", Set.of("atm"),
                ASYNC_STATUS_PATH, Set.of("atm"),
                "/api/config/origin", Set.of("web", "mobile", "atm"));
    }

    private void accountExists() {
        when(service.account(104)).thenReturn(new BankingService.Account(104, "Cuenta", "SAVINGS",
                new BigDecimal("10000"), new BigDecimal("0.01"), new BigDecimal("10100")));
    }
}
