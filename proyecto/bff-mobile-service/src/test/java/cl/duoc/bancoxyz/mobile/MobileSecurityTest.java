package cl.duoc.bancoxyz.mobile;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cl.duoc.bancoxyz.config.SecurityConfig;
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

@WebMvcTest(controllers = MobileBffController.class, properties = "spring.cloud.config.enabled=false")
@Import(SecurityConfig.class)
class MobileSecurityTest {
    @Autowired MockMvc mvc;
    @MockBean MobileBankingClient core;
    @MockBean JwtDecoder decoder;

    @Test
    void legacyLoginEndpointIsRemoved() throws Exception {
        mvc.perform(post("/auth/login").with(jwt().jwt(jwt -> jwt.claim("scope", "mobile")))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsRequestsWithoutToken() throws Exception {
        mvc.perform(get("/api/bff/mobile/accounts/104")).andExpect(status().isUnauthorized());
        verifyNoInteractions(core);
    }

    @Test
    void allowsMatchingScope() throws Exception {
        accountExists();
        mvc.perform(get("/api/bff/mobile/accounts/104").with(jwt().jwt(jwt -> jwt.claim("scope", "mobile"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accountId").value(104));
        verify(core).account(104);
    }

    @Test
    void rejectsOtherChannelScopes() throws Exception {
        for (String scope : new String[]{"atm", "web"}) {
            mvc.perform(get("/api/bff/mobile/accounts/104").with(jwt().jwt(jwt -> jwt.claim("scope", scope))))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(core);
    }

    @Test
    void rejectsInternalPaymentScope() throws Exception {
        mvc.perform(get("/api/bff/mobile/accounts/104")
                .with(jwt().jwt(token -> token.claim("scope", "account.operations"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(core);
    }

    @Test
    void rejectsInvalidBearerToken() throws Exception {
        when(decoder.decode("invalid-token")).thenThrow(new BadJwtException("Invalid token"));
        mvc.perform(get("/api/bff/mobile/accounts/104").header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(core);
    }

    @Test
    void convertsBearerTokenScopeUsingResourceServer() throws Exception {
        accountExists();
        when(decoder.decode("valid-token")).thenReturn(Jwt.withTokenValue("valid-token")
                .header("alg", "RS256").subject("mobile-client").claim("scope", "mobile").build());
        mvc.perform(get("/api/bff/mobile/accounts/104").header(HttpHeaders.AUTHORIZATION, "Bearer valid-token"))
                .andExpect(status().isOk());
        verify(decoder).decode("valid-token");
        verify(core).account(104);
    }

    private void accountExists() {
        when(core.account(104)).thenReturn(new MobileBankingClient.Account(104, "Cuenta", "SAVINGS",
                new BigDecimal("10000"), new BigDecimal("0.01"), new BigDecimal("10100")));
    }
}
