package cl.duoc.bancoxyz.core;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cl.duoc.bancoxyz.core.AccountOperationService.OperationRequest;
import cl.duoc.bancoxyz.core.AccountOperationService.OperationType;
import cl.duoc.bancoxyz.core.kafka.AsyncWithdrawalService;
import cl.duoc.bancoxyz.events.WithdrawalRequestedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@TestPropertySource(properties = "spring.kafka.listener.auto-startup=true")
class OperationalWithdrawalIntegrationTest extends OperationalAccountsIntegrationSupport {
    @Autowired OperationalWithdrawalService withdrawals;
    @Autowired AsyncWithdrawalService legacy;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    @Autowired KafkaListenerEndpointRegistry listeners;
    @MockBean JwtDecoder decoder;

    @Test
    void completesAndAtomicallyStoresMonetaryOperationStatusAndOriginalEvent() {
        account(101, "100000");
        legacyAccount();
        var requested = requested("10000");
        var result = withdrawals.process(requested);
        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.completed().finalBalance()).isEqualByComparingTo("90000");
        assertThat(result.completed().transactionId()).isEqualTo(requested.transactionId());
        assertThat(result.completed().occurredAt()).isEqualTo(operations.operation(requested.transactionId()).completedAt());
        assertThat(result).isEqualTo(withdrawals.result(requested.transactionId()));
        assertThat(balance(101)).isEqualByComparingTo("90000");
        assertThat(operationCount()).isEqualTo(1);
        assertThat(count("withdrawal_transactions")).isEqualTo(1);
        assertThat(count("banking_events")).isEqualTo(2);
        assertThat(legacyBalance()).isEqualByComparingTo("777777");
    }

    @Test
    void insufficientFundsPersistRejectionWithoutChangingEitherBalanceModel() {
        account(101, "1000");
        legacyAccount();
        var requested = requested("10000");
        var result = withdrawals.process(requested);
        assertThat(result.status()).isEqualTo("REJECTED");
        assertThat(result.rejected().reason()).isEqualTo("Saldo insuficiente");
        assertThat(operations.operation(requested.transactionId()).status()).isEqualTo("REJECTED");
        assertThat(balance(101)).isEqualByComparingTo("1000");
        assertThat(legacyBalance()).isEqualByComparingTo("777777");
    }

    @Test
    void closedAccountIsRejected() {
        account(101, "100000");
        jdbc.update("UPDATE cuentas SET estado='CERRADA' WHERE cuenta_id=101");
        assertThat(withdrawals.process(requested("10000")).rejected().reason()).contains("cerrada");
        assertThat(balance(101)).isEqualByComparingTo("100000");
    }

    @Test
    void missingAccountIsRejectedAndAuditable() {
        var requested = requested("10000");
        assertThat(withdrawals.process(requested).rejected().reason()).contains("no encontrada");
        assertThat(withdrawals.result(requested.transactionId()).status()).isEqualTo("REJECTED");
        assertThat(operationCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-10000", "5000"})
    void invalidAtmAmountsReserveTheMonetaryIdentifierWithoutCharging(String amount) {
        account(101, "100000");
        var requested = requested(amount);
        var result = withdrawals.process(requested);
        assertThat(result.status()).isEqualTo("REJECTED");
        assertThat(result.rejected().amount()).isEqualByComparingTo(amount);
        assertThat(operations.execute(new OperationRequest(requested.transactionId(), OperationType.DEBIT,
                101, null, new BigDecimal(amount))).status()).isEqualTo("REJECTED");
        assertThat(balance(101)).isEqualByComparingTo("100000");
        assertThat(operationCount()).isEqualTo(1);
    }

    @Test
    void duplicateWithNewEnvelopeKeepsEventIdentifierDateAndOriginalBalance() {
        account(101, "100000");
        var requested = requested("10000");
        var original = withdrawals.process(requested);
        operations.execute(new OperationRequest(UUID.randomUUID(), OperationType.CREDIT, null, 101, new BigDecimal("50000")));
        var repeated = new WithdrawalRequestedEvent(UUID.randomUUID(), requested.transactionId(), 101,
                new BigDecimal("10000.00"), requested.occurredAt().plusSeconds(60));
        assertThat(withdrawals.process(repeated)).isEqualTo(original);
        assertThat(balance(101)).isEqualByComparingTo("140000");
        assertThat(original.completed().finalBalance()).isEqualByComparingTo("90000");
        assertThat(count("banking_events")).isEqualTo(2);
    }

    @Test
    void differentAmountOrAccountWithSameTransactionIdConflicts() {
        account(101, "100000");
        var requested = requested("10000");
        var original = withdrawals.process(requested);
        for (var incompatible : new WithdrawalRequestedEvent[] {
                new WithdrawalRequestedEvent(UUID.randomUUID(), requested.transactionId(), 101, new BigDecimal("20000"), Instant.now()),
                new WithdrawalRequestedEvent(UUID.randomUUID(), requested.transactionId(), 102, new BigDecimal("10000"), Instant.now())}) {
            assertThatThrownBy(() -> withdrawals.process(incompatible)).isInstanceOf(ResponseStatusException.class)
                    .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value()).isEqualTo(409));
        }
        assertThat(withdrawals.result(requested.transactionId())).isEqualTo(original);
        assertThat(balance(101)).isEqualByComparingTo("90000");
        assertThat(operationCount()).isEqualTo(1);
    }

    @Test
    void concurrentRequestsCreateOnlyOneOriginalResult() throws Exception {
        account(101, "100000");
        var requested = requested("10000");
        var executor = Executors.newFixedThreadPool(6);
        try {
            var tasks = new ArrayList<Callable<OperationalWithdrawalService.WithdrawalResult>>();
            for (int index = 0; index < 6; index++) {
                tasks.add(() -> withdrawals.process(new WithdrawalRequestedEvent(UUID.randomUUID(),
                        requested.transactionId(), 101, requested.amount(), Instant.now())));
            }
            var results = executor.invokeAll(tasks);
            var original = results.get(0).get();
            for (var result : results) assertThat(result.get()).isEqualTo(original);
        } finally {
            executor.shutdownNow();
        }
        assertThat(balance(101)).isEqualByComparingTo("90000");
        assertThat(operationCount()).isEqualTo(1);
        assertThat(count("withdrawal_transactions")).isEqualTo(1);
        assertThat(count("banking_events")).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"withdrawal_transactions", "banking_events"})
    void persistenceFailureAfterDebitRollsBackAllEffectsAndRetryKeepsTheIdentifier(String table) {
        account(101, "100000");
        var requested = requested("10000");
        jdbc.execute("""
                CREATE FUNCTION step05_fail_insert() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'Fallo aislado posterior al debito'; END $$
                """);
        jdbc.execute("CREATE TRIGGER step05_fail BEFORE INSERT ON " + table + " FOR EACH ROW EXECUTE FUNCTION step05_fail_insert()");
        try {
            assertThatThrownBy(() -> withdrawals.process(requested)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(balance(101)).isEqualByComparingTo("100000");
            assertThat(operationCount()).isZero();
            assertThat(count("withdrawal_transactions")).isZero();
            assertThat(count("banking_events")).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER step05_fail ON " + table);
            jdbc.execute("DROP FUNCTION step05_fail_insert()");
        }
        assertThat(withdrawals.process(requested).status()).isEqualTo("COMPLETED");
        assertThat(balance(101)).isEqualByComparingTo("90000");
    }

    @Test
    void malformedMonetaryValuesRollBackWithoutPersistentWithdrawal() {
        account(101, "100000");
        for (String amount : new String[] {"1.001", "1000000000000"}) {
            assertThatThrownBy(() -> withdrawals.process(requested(amount))).isInstanceOf(ResponseStatusException.class);
        }
        assertThat(operationCount()).isZero();
        assertThat(count("withdrawal_transactions")).isZero();
        assertThat(balance(101)).isEqualByComparingTo("100000");
    }

    @Test
    void historicalResultCanBeRecoveredWithoutChargingTheOperationalAccount() {
        account(101, "100000");
        legacyAccount();
        var requested = requested("10000");
        var historical = legacy.process(requested).resultEvent();
        assertThat(withdrawals.process(requested).completed()).isEqualTo(historical);
        assertThat(balance(101)).isEqualByComparingTo("100000");
        assertThat(operationCount()).isZero();
    }

    @Test
    void missingOrIncompletePersistentResultRequiresReview() {
        var requested = requested("10000");
        assertThatThrownBy(() -> withdrawals.result(requested.transactionId())).isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value()).isEqualTo(404));
        account(101, "100000");
        withdrawals.process(requested);
        jdbc.update("DELETE FROM banking_events WHERE event_type='WithdrawalCompleted'");
        assertThatThrownBy(() -> withdrawals.process(requested)).isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value()).isEqualTo(409));
        assertThat(balance(101)).isEqualByComparingTo("90000");
    }

    @Test
    void internalHttpScopeAndAtmStatusContractRemainSeparate() throws Exception {
        account(101, "100000");
        var requested = requested("10000");
        String path = "/api/core/internal/withdrawals";
        for (String scope : new String[] {"web", "mobile", "atm", ""}) {
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(requested))
                    .with(jwt().jwt(token -> token.claim("scope", scope)))).andExpect(status().isForbidden());
            mvc.perform(get(path + "/" + requested.transactionId()).with(jwt().jwt(token -> token.claim("scope", scope))))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(requested)))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(requested))
                .with(jwt().jwt(token -> token.claim("scope", "account.operations"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
        mvc.perform(get(path + "/" + requested.transactionId()).with(jwt().jwt(token -> token.claim("scope", "account.operations"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.completed.finalBalance").value(90000));
        mvc.perform(get("/api/core/accounts/async-withdrawals/" + requested.transactionId())
                .with(jwt().jwt(token -> token.claim("scope", "atm"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.transactionId").value(requested.transactionId().toString()))
                .andExpect(jsonPath("$.amount").value(10000)).andExpect(jsonPath("$.finalBalance").value(90000));
    }

    @Test
    void legacyListenerCannotStartThroughTheGenericKafkaProperty() {
        assertThat(listeners.getListenerContainers()).isNotEmpty();
        assertThat(listeners.getListenerContainers()).allMatch(container -> !container.isRunning());
    }

    private WithdrawalRequestedEvent requested(String amount) {
        return new WithdrawalRequestedEvent(UUID.randomUUID(), UUID.randomUUID(), 101,
                new BigDecimal(amount), Instant.parse("2026-01-01T00:00:00Z"));
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private void legacyAccount() {
        jdbc.update("""
                INSERT INTO intereses_mensuales (cuenta_id, nombre, tipo, saldo_inicial, tasa, saldo_final)
                VALUES (101, 'Dato historico de prueba', 'ahorro', 777777, 0.01, 777777)
                """);
    }

    private BigDecimal legacyBalance() {
        return jdbc.queryForObject("SELECT saldo_final FROM intereses_mensuales WHERE cuenta_id=101", BigDecimal.class);
    }
}
