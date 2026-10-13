package cl.duoc.bancoxyz.mobile;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cl.duoc.bancoxyz.config.SecurityConfig;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = MobileServicesController.class, properties = "spring.cloud.config.enabled=false")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(SecurityConfig.class)
class MobileServicesSecurityTest {
    private static final UUID ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    @Autowired MockMvc mvc;
    @MockBean MobileServicesClient services;
    @MockBean JwtDecoder decoder;

    @Test
    void mobileScopeReceivesReducedCustomerAndOperationJson() throws Exception {
        doReturn(ResponseEntity.ok(new MobileServicesClient.Customer(17,"Nombre Prueba","ESTANDAR"))).when(services).customer(17);
        doReturn(ResponseEntity.ok(new MobileServicesClient.Operation(ID,"CREDIT","COMPLETED",null))).when(services).operation(ID);
        mvc.perform(get("/api/bff/mobile/customers/17").with(jwt().jwt(token -> token.claim("scope","mobile"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.*",org.hamcrest.Matchers.hasSize(3)))
                .andExpect(jsonPath("$.customerId").value(17)).andExpect(jsonPath("$.name").value("Nombre Prueba"))
                .andExpect(jsonPath("$.profile").value("ESTANDAR")).andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.phone").doesNotExist());
        mvc.perform(get("/api/bff/mobile/payments/operations/"+ID).with(jwt().jwt(token -> token.claim("scope","mobile"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.*",org.hamcrest.Matchers.hasSize(4)))
                .andExpect(jsonPath("$.operationId").value(ID.toString())).andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.sourceBalance").doesNotExist()).andExpect(jsonPath("$.completedAt").doesNotExist());
        verify(services).customer(17);
        verify(services).operation(ID);
    }

    @Test
    void webAtmInternalAndMissingScopesCannotUseMobileRoutes() throws Exception {
        for (String scope : List.of("web","atm","account.operations","other","")) {
            for (String path : paths()) mvc.perform(get(path).with(jwt().jwt(token -> token.claim("scope",scope))))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(services);
    }

    @Test
    void absentOrInvalidBearerCannotReachServices() throws Exception {
        when(decoder.decode("invalid-token")).thenThrow(new BadJwtException("Token invalido de prueba"));
        for (String path : paths()) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).header("Authorization","Bearer invalid-token")).andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(services);
    }

    @Test
    void mobileDoesNotExposeAdministrationOrAccounting() throws Exception {
        for (String path : List.of("/api/bff/mobile/accounts","/api/bff/mobile/customers","/api/bff/mobile/payments/deposits","/api/core/internal/operations/credit")) {
            mvc.perform(post(path).contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{}")
                    .with(jwt().jwt(token -> token.claim("scope","mobile")))).andExpect(status().isNotFound());
        }
        mvc.perform(get("/api/bff/mobile/payments/operations/invalid").with(jwt().jwt(token -> token.claim("scope","mobile"))))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(services);
    }

    private List<String> paths() {
        return List.of("/api/bff/mobile/customers/17","/api/bff/mobile/payments/operations/"+ID);
    }
}
