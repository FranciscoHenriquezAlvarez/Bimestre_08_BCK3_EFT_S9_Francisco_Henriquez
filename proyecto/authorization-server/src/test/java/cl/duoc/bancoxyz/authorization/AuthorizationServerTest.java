package cl.duoc.bancoxyz.authorization;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.nimbusds.jose.jwk.RSAKey;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "app.oauth2.clients.web-secret=test-web-secret",
        "app.oauth2.clients.mobile-secret=test-mobile-secret",
        "app.oauth2.clients.atm-secret=test-atm-secret",
        "app.oauth2.clients.payment-secret=test-payment-secret"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class AuthorizationServerTest {
    @Autowired MockMvc mvc;
    @Autowired RegisteredClientRepository clients;
    @Autowired JwtDecoder decoder;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder encoder;

    @Test
    void contextLoads() {
        assertNotNull(mvc);
        assertNotNull(decoder);
    }

    @Test
    void registersChannelClientsWithExclusiveScopes() {
        for (String channel : Set.of("web", "mobile", "atm")) {
            RegisteredClient client = clients.findByClientId(channel + "-client");
            assertNotNull(client);
            assertEquals(Set.of(channel), client.getScopes());
            assertEquals(Set.of(AuthorizationGrantType.CLIENT_CREDENTIALS), client.getAuthorizationGrantTypes());
            assertEquals(Set.of(ClientAuthenticationMethod.CLIENT_SECRET_BASIC), client.getClientAuthenticationMethods());
            assertFalse(client.getClientSecret().contains("test-"));
        }
    }

    @Test
    void clientCredentialsIssuesSignedJwtAccessToken() throws Exception {
        for (String channel : Set.of("web", "mobile", "atm")) {
            Jwt token = decoder.decode(accessToken(channel + "-client", "test-" + channel + "-secret", channel));
            assertEquals("RS256", token.getHeaders().get("alg"));
            assertEquals("http://localhost:9000", token.getIssuer().toString());
            assertEquals(channel + "-client", token.getSubject());
            assertEquals(Set.of(channel), Set.copyOf(token.getClaimAsStringList("scope")));
            assertTrue(token.getExpiresAt().isAfter(token.getIssuedAt()));
        }
    }

    @Test
    void publishedJwkValidatesAccessTokenForResourceServer() throws Exception {
        NimbusJwtDecoder resourceDecoder = publicKeyDecoder();
        resourceDecoder.setJwtValidator(JwtValidators.createDefaultWithIssuer("http://localhost:9000"));
        Jwt token = resourceDecoder.decode(webAccessToken());
        assertEquals("web-client", token.getSubject());
        assertEquals(Set.of("web"), Set.copyOf(token.getClaimAsStringList("scope")));
    }

    @Test
    void resourceServerRejectsWrongIssuerAndTamperedSignature() throws Exception {
        String token = webAccessToken();
        NimbusJwtDecoder resourceDecoder = publicKeyDecoder();
        resourceDecoder.setJwtValidator(JwtValidators.createDefaultWithIssuer("http://different-issuer"));
        assertThrows(JwtException.class, () -> resourceDecoder.decode(token));
        resourceDecoder.setJwtValidator(JwtValidators.createDefaultWithIssuer("http://localhost:9000"));
        String tampered = token.substring(0, token.lastIndexOf('.') + 1) + "invalid";
        assertThrows(JwtException.class, () -> resourceDecoder.decode(tampered));
    }

    private NimbusJwtDecoder publicKeyDecoder() throws Exception {
        String response = mvc.perform(get("/oauth2/jwks")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        RSAKey key = RSAKey.parse(json.readTree(response).get("keys").get(0).toString());
        return NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build();
    }

    private String webAccessToken() throws Exception {
        String response = mvc.perform(post("/oauth2/token").with(httpBasic("web-client", "test-web-secret"))
                .param("grant_type", "client_credentials").param("scope", "web"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("access_token").asText();
    }

    @Test
    void tokenEndpointRejectsInvalidClientSecret() throws Exception {
        mvc.perform(post("/oauth2/token").with(httpBasic("web-client", "wrong-secret"))
                .param("grant_type", "client_credentials"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenEndpointRejectsScopeNotRegisteredForClient() throws Exception {
        mvc.perform(post("/oauth2/token").with(httpBasic("web-client", "test-web-secret"))
                .param("grant_type", "client_credentials").param("scope", "atm"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_scope"));
    }

    @Test
    void jwksEndpointExposesOnlyPublicRsaKey() throws Exception {
        String response = mvc.perform(get("/oauth2/jwks")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode key = json.readTree(response).get("keys").get(0);
        assertEquals("RSA", key.get("kty").asText());
        assertNotNull(key.get("n"));
        assertFalse(key.has("d"));
        assertFalse(key.has("p"));
        assertFalse(key.has("q"));
    }

    @Test
    void registersInternalClientWithExclusiveScopeAndEncodedCredential() {
        RegisteredClient client = clients.findByClientId("payment-client");
        assertNotNull(client);
        assertEquals(Set.of("account.operations"), client.getScopes());
        assertEquals(Set.of(AuthorizationGrantType.CLIENT_CREDENTIALS), client.getAuthorizationGrantTypes());
        assertEquals(Set.of(ClientAuthenticationMethod.CLIENT_SECRET_BASIC), client.getClientAuthenticationMethods());
        assertTrue(encoder.matches("test-payment-secret", client.getClientSecret()));
        assertFalse(client.getClientSecret().contains("test-"));
    }

    @Test
    void clientCredentialsIssuesSignedJwtForPayments() throws Exception {
        String token = accessToken("payment-client", "test-payment-secret", "account.operations");
        NimbusJwtDecoder resourceDecoder = publicKeyDecoder();
        resourceDecoder.setJwtValidator(JwtValidators.createDefaultWithIssuer("http://localhost:9000"));
        Jwt jwt = resourceDecoder.decode(token);
        assertEquals("RS256", jwt.getHeaders().get("alg"));
        assertEquals("payment-client", jwt.getSubject());
        assertEquals(Set.of("account.operations"), Set.copyOf(jwt.getClaimAsStringList("scope")));
        assertTrue(jwt.getExpiresAt().isAfter(jwt.getIssuedAt()));
    }

    @Test
    void internalClientCannotObtainChannelScopes() throws Exception {
        for (String scope : Set.of("web", "mobile", "atm", "account.operations web")) {
            mvc.perform(post("/oauth2/token").with(httpBasic("payment-client", "test-payment-secret"))
                    .param("grant_type", "client_credentials").param("scope", scope))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_scope"));
        }
    }

    @Test
    void channelClientsCannotObtainInternalScope() throws Exception {
        for (String channel : Set.of("web", "mobile", "atm")) {
            mvc.perform(post("/oauth2/token").with(httpBasic(channel + "-client", "test-" + channel + "-secret"))
                    .param("grant_type", "client_credentials").param("scope", "account.operations"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_scope"));
        }
    }

    @Test
    void internalTokenWithoutRequestedScopeDoesNotGainPermissions() throws Exception {
        String response = mvc.perform(post("/oauth2/token").with(httpBasic("payment-client", "test-payment-secret"))
                .param("grant_type", "client_credentials"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.scope").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        Jwt jwt = decoder.decode(json.readTree(response).get("access_token").asText());
        assertFalse(jwt.hasClaim("scope"));
    }

    @Test
    void internalClientRejectsIncorrectCredential() throws Exception {
        mvc.perform(post("/oauth2/token").with(httpBasic("payment-client", "incorrect-test-credential"))
                .param("grant_type", "client_credentials"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void internalClientIsDisabledWhenCredentialIsNotConfigured() {
        for (String absent : new String[] {"", " "}) {
            var repository = new AuthorizationSecurityConfig().registeredClients(encoder,
                    "test-web-secret", "test-mobile-secret", "test-atm-secret", absent);
            assertNull(repository.findByClientId("payment-client"));
            for (String channel : Set.of("web", "mobile", "atm")) {
                assertEquals(Set.of(channel), repository.findByClientId(channel + "-client").getScopes());
            }
        }
    }

    private String accessToken(String clientId, String secret, String scope) throws Exception {
        String response = mvc.perform(post("/oauth2/token").with(httpBasic(clientId, secret))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "client_credentials").param("scope", scope))
                .andExpect(status().isOk()).andExpect(jsonPath("$.token_type").value("Bearer"))
                .andExpect(jsonPath("$.scope").value(scope)).andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("access_token").asText();
    }
}
