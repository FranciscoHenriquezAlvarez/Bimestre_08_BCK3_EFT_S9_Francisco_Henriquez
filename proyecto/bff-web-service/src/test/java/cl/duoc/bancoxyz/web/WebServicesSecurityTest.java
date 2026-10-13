package cl.duoc.bancoxyz.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cl.duoc.bancoxyz.config.SecurityConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(controllers = WebServicesController.class, properties = "spring.cloud.config.enabled=false")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(SecurityConfig.class)
class WebServicesSecurityTest {
    private static final UUID ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    @Autowired MockMvc mvc;
    @MockBean WebServicesClient services;
    @MockBean JwtDecoder decoder;

    @Test
    void matchingWebScopeReachesEveryNewEndpoint() throws Exception {
        var ok = ResponseEntity.ok(new ObjectMapper().readTree("{\"accountId\":101,\"customerId\":17,\"status\":\"COMPLETED\"}"));
        var created = ResponseEntity.status(201).body(ok.getBody());
        when(services.openAccount(any())).thenReturn(created);
        when(services.updateAccount(eq(101),any())).thenReturn(ok);
        when(services.closeAccount(101)).thenReturn(ok);
        when(services.createCustomer(any())).thenReturn(created);
        when(services.customer(17)).thenReturn(ok);
        when(services.updateCustomer(eq(17),any())).thenReturn(ok);
        when(services.profile(17)).thenReturn(ok);
        when(services.updateProfile(eq(17),any())).thenReturn(ok);
        when(services.payment(any())).thenReturn(ok);
        when(services.deposit(any())).thenReturn(ok);
        when(services.transfer(any())).thenReturn(ok);
        when(services.operation(ID)).thenReturn(ok);
        for (Route route : routes()) {
            mvc.perform(request(route).with(jwt().jwt(token -> token.claim("scope","web"))))
                    .andExpect(status().is(route.created ? 201 : 200));
        }
        assertThat(mockingDetails(services).getInvocations()).hasSize(12);
    }

    @Test
    void otherChannelsInternalAndAbsentScopesCannotUseNewEndpoints() throws Exception {
        for (String scope : List.of("mobile","atm","account.operations","other","")) {
            for (Route route : routes()) mvc.perform(request(route).with(jwt().jwt(token -> token.claim("scope",scope))))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(services);
    }

    @Test
    void missingAndInvalidBearerAreUnauthorizedForEveryNewRoute() throws Exception {
        when(decoder.decode("invalid-token")).thenThrow(new BadJwtException("Token invalido de prueba"));
        for (Route route : routes()) {
            mvc.perform(request(route)).andExpect(status().isUnauthorized());
            mvc.perform(request(route).header("Authorization","Bearer invalid-token")).andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(services);
    }

    @Test
    void malformedInputsAndAccountingPathsDoNotInvokeBusinessClients() throws Exception {
        mvc.perform(get("/api/bff/web/payments/operations/invalid").with(jwt().jwt(token -> token.claim("scope","web"))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/bff/web/accounts").contentType(MediaType.APPLICATION_JSON).content("{bad")
                .with(jwt().jwt(token -> token.claim("scope","web")))).andExpect(status().isBadRequest());
        for (String path : List.of("/api/core/internal/operations/credit","/api/bff/web/internal/operations/credit","/api/payments")) {
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}")
                    .with(jwt().jwt(token -> token.claim("scope","web")))).andExpect(status().isNotFound());
        }
        verifyNoInteractions(services);
    }

    private MockHttpServletRequestBuilder request(Route route) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(route.method, "/api/bff/web"+route.path)
                .contentType(MediaType.APPLICATION_JSON).content("{}");
    }

    private List<Route> routes() {
        return List.of(new Route(HttpMethod.POST,"/accounts",true),new Route(HttpMethod.PUT,"/accounts/101",false),
                new Route(HttpMethod.POST,"/accounts/101/close",false),new Route(HttpMethod.POST,"/customers",true),
                new Route(HttpMethod.GET,"/customers/17",false),new Route(HttpMethod.PUT,"/customers/17",false),
                new Route(HttpMethod.GET,"/customers/17/profile",false),new Route(HttpMethod.PUT,"/customers/17/profile",false),
                new Route(HttpMethod.POST,"/payments",false),new Route(HttpMethod.POST,"/payments/deposits",false),
                new Route(HttpMethod.POST,"/payments/transfers",false),new Route(HttpMethod.GET,"/payments/operations/"+ID,false));
    }

    private record Route(HttpMethod method,String path,boolean created) {
    }
}
