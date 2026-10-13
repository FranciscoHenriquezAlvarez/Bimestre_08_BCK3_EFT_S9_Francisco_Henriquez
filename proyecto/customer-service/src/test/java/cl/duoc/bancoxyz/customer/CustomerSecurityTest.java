package cl.duoc.bancoxyz.customer;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cl.duoc.bancoxyz.customer.CustomerModels.*;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(controllers = CustomerController.class, properties = "spring.cloud.config.enabled=false")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(CustomerSecurityConfig.class)
class CustomerSecurityTest {
    private static final String CREATE = "{\"firstName\":\"Nombre\",\"lastName\":\"Prueba\",\"email\":\"cliente@example.test\",\"profile\":\"ESTANDAR\"}";
    private static final String INFORMATION = "{\"firstName\":\"Nombre\",\"lastName\":\"Prueba\",\"email\":\"cliente@example.test\"}";
    @Autowired MockMvc mvc;
    @MockBean CustomerService customers;
    @MockBean JwtDecoder decoder;

    @BeforeEach
    void responses() {
        Instant date = Instant.parse("2026-01-01T00:00:00Z");
        var customer = new Customer(1, "Nombre", "Prueba", "cliente@example.test", null, Profile.ESTANDAR, date, date);
        var profile = new CustomerProfile(1, Profile.PREFERENTE, date);
        when(customers.create(any())).thenReturn(customer);
        when(customers.find(1)).thenReturn(customer);
        when(customers.updateInformation(eq(1), any())).thenReturn(customer);
        when(customers.profile(1)).thenReturn(profile);
        when(customers.updateProfile(eq(1), any())).thenReturn(profile);
    }

    @ParameterizedTest
    @ValueSource(strings = {"web", "mobile"})
    void allowedScopesCanUseAllFiveEndpoints(String scope) throws Exception {
        var requests = endpoints();
        for (int index = 0; index < requests.length; index++) {
            mvc.perform(requests[index].with(jwt().jwt(token -> token.claim("scope", scope))))
                    .andExpect(status().is(index == 0 ? 201 : 200));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"atm", "account.operations", "unrelated", ""})
    void otherScopesCannotReachCustomerOperations(String scope) throws Exception {
        for (var request : endpoints()) {
            mvc.perform(request.with(jwt().jwt(token -> token.claim("scope", scope)))).andExpect(status().isForbidden());
        }
        verifyNoInteractions(customers);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "invalid-test-token"})
    void absentOrInvalidSimulatedTokensAreUnauthorized(String token) throws Exception {
        when(decoder.decode(anyString())).thenThrow(new BadJwtException("Token de prueba invalido"));
        for (var request : endpoints()) {
            if (!token.isEmpty()) request.header("Authorization", "Bearer " + token);
            mvc.perform(request).andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(customers);
    }

    @Test
    void undefinedEndpointsAndMethodsAreDenied() throws Exception {
        for (var request : new MockHttpServletRequestBuilder[] {get("/api/customers"), delete("/api/customers/1"),
                get("/api/customers/1/private"), get("/actuator/env"), get("/unknown")}) {
            mvc.perform(request.with(jwt().jwt(token -> token.claim("scope", "web")))).andExpect(status().isForbidden());
        }
        verifyNoInteractions(customers);
    }

    @Test
    void databaseErrorsDoNotExposeInternalDetails() throws Exception {
        when(customers.find(1)).thenThrow(new DataAccessResourceFailureException("Internal PostgreSQL SQL details"));
        mvc.perform(get("/api/customers/1").with(jwt().jwt(token -> token.claim("scope", "web"))))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.message").value("No fue posible acceder a los datos del cliente"));
    }

    private MockHttpServletRequestBuilder[] endpoints() {
        return new MockHttpServletRequestBuilder[] {
                post("/api/customers").contentType(MediaType.APPLICATION_JSON).content(CREATE),
                get("/api/customers/1"),
                put("/api/customers/1").contentType(MediaType.APPLICATION_JSON).content(INFORMATION),
                get("/api/customers/1/profile"),
                put("/api/customers/1/profile").contentType(MediaType.APPLICATION_JSON).content("{\"profile\":\"PREFERENTE\"}")};
    }
}
