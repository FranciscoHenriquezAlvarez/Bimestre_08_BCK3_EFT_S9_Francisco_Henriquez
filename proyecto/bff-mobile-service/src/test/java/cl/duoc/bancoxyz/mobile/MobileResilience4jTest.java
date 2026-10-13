package cl.duoc.bancoxyz.mobile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.springboot3.bulkhead.autoconfigure.BulkheadAutoConfiguration;
import io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import io.github.resilience4j.springboot3.timelimiter.autoconfigure.TimeLimiterAutoConfiguration;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JAutoConfiguration;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;

@SpringJUnitConfig(MobileResilience4jTest.TestConfig.class)
@TestPropertySource(properties = {
        "resilience4j.circuitbreaker.instances.coreMobile.sliding-window-size=2",
        "resilience4j.circuitbreaker.instances.coreMobile.minimum-number-of-calls=2",
        "resilience4j.circuitbreaker.instances.coreMobile.failure-rate-threshold=50",
        "resilience4j.circuitbreaker.instances.coreMobile.wait-duration-in-open-state=1s",
        "resilience4j.circuitbreaker.instances.coreMobile.permitted-number-of-calls-in-half-open-state=2"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MobileResilience4jTest {
    private static final String CORE_URL = "http://core-banking-service/api/core/accounts/104";
    private static final String BFF_URL = "/api/bff/mobile/accounts/104";
    private static final String CORE_RESPONSE = "{\"accountId\":104,\"accountName\":\"Cuenta de prueba\",\"accountType\":\"ahorro\",\"initialBalance\":10200,\"interestRate\":0.02,\"finalBalance\":10200}";

    @Autowired MockRestServiceServer coreServer;
    @Autowired MobileBffController controller;
    @Autowired CircuitBreakerRegistry registry;
    @Autowired Resilience4JCircuitBreakerFactory factory;

    private CircuitBreaker circuitBreaker;
    private MockMvc mvc;
    private final AtomicInteger coreRequests = new AtomicInteger();

    @BeforeEach
    void setUp() {
        coreServer.reset();
        coreRequests.set(0);
        circuitBreaker = registry.circuitBreaker("coreMobile");
        circuitBreaker.reset();
        assertSame(registry, factory.getCircuitBreakerRegistry());
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
        Jwt token = Jwt.withTokenValue("resilience-test-token").header("alg", "RS256")
                .claim("scope", "mobile").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(token));
    }

    @AfterEach
    void tearDown() {
        try {
            coreServer.verify();
        } finally {
            circuitBreaker.reset();
            coreServer.reset();
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void circuitBreakerStartsClosedAndAllowsSuccessfulCall() throws Exception {
        expectSuccesses(1);

        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());
        mvc.perform(get(BFF_URL)).andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(104));

        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());
        assertEquals(1, coreRequests.get());
        assertEquals(1, circuitBreaker.getMetrics().getNumberOfSuccessfulCalls());
    }

    @Test
    void circuitBreakerOpensAfterConfiguredFailuresAndShortCircuits() throws Exception {
        expectFailures();

        mvc.perform(get(BFF_URL)).andExpect(status().isServiceUnavailable());
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());
        assertEquals(1, circuitBreaker.getMetrics().getNumberOfFailedCalls());

        mvc.perform(get(BFF_URL)).andExpect(status().isServiceUnavailable());
        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());
        assertEquals(2, circuitBreaker.getMetrics().getNumberOfFailedCalls());

        mvc.perform(get(BFF_URL)).andExpect(status().isServiceUnavailable());
        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());
        assertEquals(2, coreRequests.get());
        assertEquals(1, circuitBreaker.getMetrics().getNumberOfNotPermittedCalls());
    }

    @Test
    void circuitBreakerRecoversThroughHalfOpenAndReturnsToClosed() throws Exception {
        expectFailures();
        expectSuccesses(3);

        mvc.perform(get(BFF_URL)).andExpect(status().isServiceUnavailable());
        mvc.perform(get(BFF_URL)).andExpect(status().isServiceUnavailable());
        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());

        await().pollInSameThread().pollInterval(Duration.ofMillis(20))
                .atMost(Duration.ofSeconds(5))
                .until(() -> mvc.perform(get(BFF_URL)).andReturn().getResponse().getStatus() == 200);

        assertEquals(CircuitBreaker.State.HALF_OPEN, circuitBreaker.getState());
        assertEquals(3, coreRequests.get());
        assertEquals(1, circuitBreaker.getMetrics().getNumberOfSuccessfulCalls());

        mvc.perform(get(BFF_URL)).andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(104));
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());

        mvc.perform(get(BFF_URL)).andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(104));
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());
        assertEquals(5, coreRequests.get());
    }

    private void expectFailures() {
        coreServer.expect(ExpectedCount.twice(), requestTo(CORE_URL))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer resilience-test-token"))
                .andRespond(request -> {
                    coreRequests.incrementAndGet();
                    return withServerError().createResponse(request);
                });
    }

    private void expectSuccesses(int count) {
        coreServer.expect(ExpectedCount.times(count), requestTo(CORE_URL))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer resilience-test-token"))
                .andRespond(request -> {
                    coreRequests.incrementAndGet();
                    return withSuccess(CORE_RESPONSE, MediaType.APPLICATION_JSON).createResponse(request);
                });
    }

    @TestConfiguration(proxyBeanMethods = false)
    @ImportAutoConfiguration({
            CircuitBreakerAutoConfiguration.class,
            TimeLimiterAutoConfiguration.class,
            BulkheadAutoConfiguration.class,
            Resilience4JAutoConfiguration.class
    })
    static class TestConfig {
        @Bean
        RestClient.Builder restClientBuilder() {
            return RestClient.builder();
        }

        @Bean
        MockRestServiceServer coreServer(RestClient.Builder builder) {
            return MockRestServiceServer.bindTo(builder).build();
        }

        @Bean
        MobileBankingClient bankingClient(RestClient.Builder builder, Resilience4JCircuitBreakerFactory factory,
                MockRestServiceServer coreServer) {
            return new MobileBankingClient(builder, factory);
        }

        @Bean
        MobileBffController controller(MobileBankingClient client) {
            return new MobileBffController(client);
        }
    }
}

