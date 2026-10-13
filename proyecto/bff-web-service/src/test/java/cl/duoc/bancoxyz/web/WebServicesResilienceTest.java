package cl.duoc.bancoxyz.web;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.http.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.web.client.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

@SpringJUnitConfig(classes={WebResilience4jTest.TestConfig.class,WebServicesResilienceTest.NewConfig.class})
@TestPropertySource(properties={
 "resilience4j.circuitbreaker.instances.customerWeb.sliding-window-size=2",
 "resilience4j.circuitbreaker.instances.customerWeb.minimum-number-of-calls=2",
 "resilience4j.circuitbreaker.instances.paymentWeb.sliding-window-size=2",
 "resilience4j.circuitbreaker.instances.paymentWeb.minimum-number-of-calls=2",
 "resilience4j.circuitbreaker.instances.coreWeb.sliding-window-size=2",
 "resilience4j.circuitbreaker.instances.coreWeb.minimum-number-of-calls=2",
 "resilience4j.circuitbreaker.instances.coreWeb.ignore-exceptions[0]=org.springframework.web.client.HttpClientErrorException"
})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class WebServicesResilienceTest {
    private static final UUID ID=UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final String CUSTOMER="{\"customerId\":17,\"firstName\":\"Nombre\",\"lastName\":\"Prueba\",\"profile\":\"ESTANDAR\"}";
    @Autowired WebServicesClient services;
    @Autowired WebBankingClient core;
    @Autowired MockRestServiceServer coreServer;
    @Autowired CircuitBreakerRegistry registry;

    @BeforeEach
    void setup() {
        coreServer.reset();
        registry.getAllCircuitBreakers().forEach(circuit -> circuit.reset());
        var jwt=Jwt.withTokenValue("web-resilience-token").header("alg","RS256").claim("scope","web").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void cleanup() {
        try { coreServer.verify(); } finally { coreServer.reset(); SecurityContextHolder.clearContext(); }
    }

    @Test
    void customerFunctionalErrorsDoNotOpenCircuitOrAffectCore() {
        expect("customer-service/api/customers/17",2,404,"{}");
        for(int count=0;count<2;count++) assertThat(services.customer(17).getStatusCode().value()).isEqualTo(404);
        assertThat(registry.circuitBreaker("customerWeb").getState().name()).isEqualTo("CLOSED");
        assertThat(registry.circuitBreaker("customerWeb").getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    void customerOutageOpensOnlyItsCircuitAndStopsRemoteCalls() {
        expect("customer-service/api/customers/17",2,503,"{}");
        expect("core-banking-service/api/core/accounts/101",1,200,"{\"accountId\":101,\"finalBalance\":20}");
        expect("payment-service/api/payments/operations/"+ID,1,200,"{\"operationId\":\""+ID+"\",\"type\":\"CREDIT\",\"status\":\"COMPLETED\"}");
        for(int count=0;count<3;count++) assertThat(services.customer(17).getStatusCode().value()).isEqualTo(503);
        assertThat(registry.circuitBreaker("customerWeb").getState().name()).isEqualTo("OPEN");
        assertThat(core.account(101).finalBalance()).isEqualByComparingTo("20");
        assertThat(services.operation(ID).getStatusCode().value()).isEqualTo(200);
        assertThat(registry.circuitBreaker("coreWeb").getState().name()).isEqualTo("CLOSED");
        assertThat(registry.circuitBreaker("paymentWeb").getState().name()).isEqualTo("CLOSED");
    }

    @Test
    void existingQueriesPreserve404WithoutRecordingInfrastructureFailures() {
        expect("core-banking-service/api/core/accounts/101",2,404,"{}");
        for(int count=0;count<2;count++) assertThatThrownBy(() -> core.account(101))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException)error).getStatusCode().value()).isEqualTo(404));
        assertThat(registry.circuitBreaker("coreWeb").getState().name()).isEqualTo("CLOSED");
        assertThat(registry.circuitBreaker("coreWeb").getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    void paymentFailureKeepsUuidUnknownAndStopsRepeatedRemoteCalls() {
        expect("payment-service/api/payments/operations/"+ID,2,503,"{}");
        expect("customer-service/api/customers/17",1,200,CUSTOMER);
        for(int count=0;count<3;count++) {
            var result=services.operation(ID);
            assertThat(result.getStatusCode().value()).isEqualTo(503);
            assertThat(result.getBody().path("operationId").asText()).isEqualTo(ID.toString());
            assertThat(result.getBody().path("status").asText()).isEqualTo("UNKNOWN");
        }
        assertThat(registry.circuitBreaker("paymentWeb").getState().name()).isEqualTo("OPEN");
        assertThat(services.customer(17).getStatusCode().value()).isEqualTo(200);
    }

    private void expect(String path,int count,int status,String body) {
        coreServer.expect(ExpectedCount.times(count),requestTo("http://"+path))
                .andExpect(header("Authorization","Bearer web-resilience-token"))
                .andRespond(withStatus(HttpStatus.valueOf(status)).contentType(MediaType.APPLICATION_JSON).body(body));
    }

    @TestConfiguration(proxyBeanMethods=false)
    static class NewConfig {
        @Bean
        WebServicesClient services(RestClient.Builder builder,Resilience4JCircuitBreakerFactory factory,MockRestServiceServer server) {
            return new WebServicesClient(builder,factory);
        }
    }
}
