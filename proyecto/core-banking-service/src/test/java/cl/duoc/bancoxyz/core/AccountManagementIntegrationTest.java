package cl.duoc.bancoxyz.core;

import static cl.duoc.bancoxyz.core.AccountOperationService.OperationType.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cl.duoc.bancoxyz.core.AccountManagementModels.*;
import cl.duoc.bancoxyz.core.AccountOperationService.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class AccountManagementIntegrationTest extends OperationalAccountsIntegrationSupport {
    private static final String ROOT = "/api/core/accounts";
    private static final String OPEN = "{\"customerId\":17,\"accountName\":\"Cuenta nueva\",\"accountType\":\"ahorro\",\"interestRate\":0.01}";
    private static final String UPDATE = "{\"accountName\":\"Nombre actualizado\",\"accountType\":\"prestamo\",\"interestRate\":0.02}";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AccountManagementService management;
    @Autowired BankingService banking;
    @MockBean CustomerAccountClient customers;
    @MockBean JwtDecoder decoder;

    @Test
    void webAndMobileCanOpenMaintainAndCloseWithZeroInitialFunds() throws Exception {
        for (String channel : List.of("web", "mobile")) {
            var result = mvc.perform(post(ROOT).contentType(MediaType.APPLICATION_JSON).content(OPEN)
                    .with(jwt().jwt(token -> token.claim("scope", channel))))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.customerId").value(17))
                    .andExpect(jsonPath("$.state").value("ACTIVA"))
                    .andExpect(jsonPath("$.initialBalance").value(0)).andExpect(jsonPath("$.finalBalance").value(0))
                    .andExpect(jsonPath("$.createdAt").isNotEmpty()).andExpect(jsonPath("$.updatedAt").isNotEmpty())
                    .andReturn();
            int id = json.readTree(result.getResponse().getContentAsString()).path("accountId").asInt();
            assertThat(result.getResponse().getHeader("Location")).isEqualTo(ROOT + "/" + id);
            mvc.perform(put(ROOT + "/" + id).contentType(MediaType.APPLICATION_JSON).content(UPDATE)
                    .with(jwt().jwt(token -> token.claim("scope", channel))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.accountName").value("Nombre actualizado"))
                    .andExpect(jsonPath("$.customerId").value(17));
            mvc.perform(post(ROOT + "/" + id + "/close").with(jwt().jwt(token -> token.claim("scope", channel))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("CERRADA"));
        }
        verify(customers, times(2)).requireExistingCustomer(17, "token");
        assertThat(operationCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cuentas WHERE cliente_id=17 AND estado='CERRADA'", Integer.class)).isEqualTo(2);
    }

    @Test
    void invalidOpeningDataNeverCallsCustomersOrWritesAccounts() throws Exception {
        for (String body : List.of("{}", OPEN.replace("17", "0"), OPEN.replace("17", "-1"),
                OPEN.replace("Cuenta nueva", " "), OPEN.replace("Cuenta nueva", "x".repeat(101)),
                OPEN.replace("ahorro", "otro"), OPEN.replace("0.01", "-0.01"),
                OPEN.replace("0.01", "1.0001"), OPEN.replace("0.01", "0.00001"),
                OPEN.replace("0.01", "null"), OPEN.replace("17", "null"), "null", "{malformed")) {
            mvc.perform(post(ROOT).contentType(MediaType.APPLICATION_JSON).content(body)
                    .with(jwt().jwt(token -> token.claim("scope", "web"))))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(customers);
        assertThat(accountCount()).isZero();
    }

    @Test
    void immutableAndBalanceFieldsAreRejectedInOpeningAndMaintenance() throws Exception {
        ManagedAccount created = open();
        var before = jdbc.queryForMap("SELECT * FROM cuentas WHERE cuenta_id=?", created.accountId());
        for (String field : List.of("accountId", "customerId", "initialBalance", "finalBalance", "saldo_operativo", "state", "createdAt")) {
            String update = UPDATE.substring(0, UPDATE.length() - 1) + ",\"" + field + "\":900}";
            mvc.perform(put(ROOT + "/" + created.accountId()).contentType(MediaType.APPLICATION_JSON).content(update)
                    .with(jwt().jwt(token -> token.claim("scope", "web"))))
                    .andExpect(status().isBadRequest());
            if (!field.equals("customerId")) {
                String opening = OPEN.substring(0, OPEN.length() - 1) + ",\"" + field + "\":900}";
                mvc.perform(post(ROOT).contentType(MediaType.APPLICATION_JSON).content(opening)
                        .with(jwt().jwt(token -> token.claim("scope", "mobile"))))
                        .andExpect(status().isBadRequest());
            }
        }
        assertThat(jdbc.queryForMap("SELECT * FROM cuentas WHERE cuenta_id=?", created.accountId())).isEqualTo(before);
        assertThat(accountCount()).isEqualTo(1);
    }

    @Test
    void customerFailuresLeaveNeitherPartialAccountsNorSequenceReservations() throws Exception {
        Long before = jdbc.queryForObject("SELECT last_value FROM cuentas_id_seq", Long.class);
        for (HttpStatus status : List.of(HttpStatus.NOT_FOUND, HttpStatus.SERVICE_UNAVAILABLE, HttpStatus.FORBIDDEN)) {
            doThrow(new ResponseStatusException(status, "Fallo controlado de validacion")).when(customers).requireExistingCustomer(17, "token");
            mvc.perform(post(ROOT).contentType(MediaType.APPLICATION_JSON).content(OPEN)
                    .with(jwt().jwt(token -> token.claim("scope", "web"))))
                    .andExpect(status().is(status.value()));
            assertThat(accountCount()).isZero();
            assertThat(operationCount()).isZero();
            assertThat(jdbc.queryForObject("SELECT last_value FROM cuentas_id_seq", Long.class)).isEqualTo(before);
        }
    }

    @Test
    void concurrentOpeningsAvoidBothOperativeAndHistoricalIdentifiers() throws Exception {
        account(800000, "0");
        history(700000, "100");
        jdbc.update("INSERT INTO movimientos_anuales (cuenta_id,fecha,transaccion,monto,descripcion) VALUES (900000,CURRENT_DATE,'DEPOSITO',10,'Prueba aislada')");
        var historyBefore = jdbc.queryForList("SELECT * FROM intereses_mensuales");
        var executor = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<ManagedAccount>> pending = new ArrayList<>();
            for (int index = 0; index < 4; index++) pending.add(executor.submit(() -> { start.await(); return open(); }));
            start.countDown();
            Set<Integer> ids = new HashSet<>();
            for (var future : pending) {
                ManagedAccount result = future.get(15, TimeUnit.SECONDS);
                assertThat(result.accountId()).isGreaterThan(900000);
                assertThat(result.customerId()).isEqualTo(17);
                ids.add(result.accountId());
            }
            assertThat(ids).hasSize(4);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
        assertThat(accountCount()).isEqualTo(5);
        assertThat(jdbc.queryForList("SELECT * FROM intereses_mensuales")).isEqualTo(historyBefore);
        assertThat(jdbc.queryForObject("SELECT cliente_id FROM cuentas WHERE cuenta_id=800000", Integer.class)).isNull();
    }

    @Test
    void maintenancePreservesClientCreationBalancesLedgerAndBatch() throws Exception {
        ManagedAccount created = open();
        int id = created.accountId();
        operations.execute(request(CREDIT, null, id, "25"));
        history(id, "987");
        var ledger = jdbc.queryForList("SELECT * FROM operaciones_cuenta");
        var history = jdbc.queryForList("SELECT * FROM intereses_mensuales");
        mvc.perform(put(ROOT + "/" + id).contentType(MediaType.APPLICATION_JSON).content(UPDATE)
                .with(jwt().jwt(token -> token.claim("scope", "mobile"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accountId").value(id))
                .andExpect(jsonPath("$.customerId").value(17)).andExpect(jsonPath("$.accountType").value("prestamo"))
                .andExpect(jsonPath("$.interestRate").value(0.02)).andExpect(jsonPath("$.finalBalance").value(25));
        assertThat(balance(id)).isEqualByComparingTo("25");
        assertThat(jdbc.queryForObject("SELECT creada_en FROM cuentas WHERE cuenta_id=?", java.sql.Timestamp.class, id).toInstant()).isEqualTo(created.createdAt());
        assertThat(jdbc.queryForObject("SELECT actualizada_en FROM cuentas WHERE cuenta_id=?", java.sql.Timestamp.class, id).toInstant()).isAfter(created.updatedAt());
        assertThat(jdbc.queryForList("SELECT * FROM operaciones_cuenta")).isEqualTo(ledger);
        assertThat(jdbc.queryForList("SELECT * FROM intereses_mensuales")).isEqualTo(history);
    }

    @Test
    void invalidOrMissingAdministrativeTargetsReturnClearErrors() throws Exception {
        for (String id : List.of("0", "-1", "invalid", "2147483648")) {
            mvc.perform(put(ROOT + "/" + id).contentType(MediaType.APPLICATION_JSON).content(UPDATE)
                    .with(jwt().jwt(token -> token.claim("scope", "web")))).andExpect(status().isBadRequest());
            mvc.perform(post(ROOT + "/" + id + "/close").with(jwt().jwt(token -> token.claim("scope", "mobile"))))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(put(ROOT + "/123").contentType(MediaType.APPLICATION_JSON).content(UPDATE)
                .with(jwt().jwt(token -> token.claim("scope", "web")))).andExpect(status().isNotFound());
        mvc.perform(post(ROOT + "/123/close").with(jwt().jwt(token -> token.claim("scope", "mobile"))))
                .andExpect(status().isNotFound());
        assertThat(accountCount()).isZero();
    }

    @Test
    void positiveBalanceCannotCloseAndInvalidMaintenanceCannotChangeTheRow() throws Exception {
        ManagedAccount created = open();
        operations.execute(request(CREDIT, null, created.accountId(), "10"));
        var before = jdbc.queryForMap("SELECT * FROM cuentas WHERE cuenta_id=?", created.accountId());
        mvc.perform(post(ROOT + "/" + created.accountId() + "/close")
                .with(jwt().jwt(token -> token.claim("scope", "web")))).andExpect(status().isConflict());
        for (String body : List.of("{}", UPDATE.replace("0.02", "null"), UPDATE.replace("prestamo", "otro"))) {
            mvc.perform(put(ROOT + "/" + created.accountId()).contentType(MediaType.APPLICATION_JSON).content(body)
                    .with(jwt().jwt(token -> token.claim("scope", "web")))).andExpect(status().isBadRequest());
        }
        assertThat(jdbc.queryForMap("SELECT * FROM cuentas WHERE cuenta_id=?", created.accountId())).isEqualTo(before);
    }

    @Test
    void logicalClosePreservesAssociationAndHistoryAndRepeatedCloseConflicts() throws Exception {
        ManagedAccount created = open();
        history(created.accountId(), "400");
        jdbc.update("INSERT INTO movimientos_anuales (cuenta_id,fecha,transaccion,monto,descripcion) VALUES (?,CURRENT_DATE,'DEPOSITO',10,'Historico aislado')", created.accountId());
        var historical = jdbc.queryForList("SELECT * FROM movimientos_anuales");
        ManagedAccount closed = management.close(created.accountId());
        assertThat(closed.state()).isEqualTo("CERRADA");
        assertThat(closed.customerId()).isEqualTo(created.customerId());
        assertThat(closed.createdAt()).isEqualTo(created.createdAt());
        assertThat(accountCount()).isEqualTo(1);
        mvc.perform(post(ROOT + "/" + created.accountId() + "/close").with(jwt().jwt(token -> token.claim("scope", "web"))))
                .andExpect(status().isConflict());
        mvc.perform(put(ROOT + "/" + created.accountId()).contentType(MediaType.APPLICATION_JSON).content(UPDATE)
                .with(jwt().jwt(token -> token.claim("scope", "mobile")))).andExpect(status().isConflict());
        mvc.perform(get(ROOT + "/" + created.accountId() + "/annual-statement").with(jwt().jwt(token -> token.claim("scope", "web"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.movementCount").value(1));
        mvc.perform(get(ROOT + "/" + created.accountId() + "/movements/latest").with(jwt().jwt(token -> token.claim("scope", "mobile"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].amount").value(10));
        assertThat(banking.account(created.accountId()).finalBalance()).isEqualByComparingTo("0");
        assertThat(jdbc.queryForList("SELECT * FROM movimientos_anuales")).isEqualTo(historical);
    }

    @Test
    void closedAccountRejectsEveryNewMonetaryDirectionWithoutPartialTransfers() {
        int closed = open().accountId();
        account(101, "100");
        management.close(closed);
        for (OperationRequest request : List.of(request(CREDIT, null, closed, "10"), request(DEBIT, closed, null, "10"),
                request(TRANSFER, 101, closed, "10"), request(TRANSFER, closed, 101, "10"))) {
            OperationResult rejected = operations.execute(request);
            assertThat(rejected.status()).isEqualTo("REJECTED");
            assertThat(rejected.rejectionReason()).contains("cerrada");
        }
        assertThat(balance(closed)).isEqualByComparingTo("0");
        assertThat(balance(101)).isEqualByComparingTo("100");
        assertThat(operationCount()).isEqualTo(4);
    }

    @Test
    void newAccountsExposeThePreviousQueryContractsAndEmptyHistory() throws Exception {
        int id = open().accountId();
        operations.execute(request(CREDIT, null, id, "23"));
        var result = mvc.perform(get(ROOT + "/" + id).with(jwt().jwt(token -> token.claim("scope", "web"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.finalBalance").value(23)).andReturn();
        assertThat(json.readTree(result.getResponse().getContentAsString()).properties()).extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("accountId", "accountName", "accountType", "initialBalance", "interestRate", "finalBalance");
        mvc.perform(get(ROOT + "/" + id + "/balance").with(jwt().jwt(token -> token.claim("scope", "atm"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.balance").value(23));
        mvc.perform(get(ROOT + "/" + id + "/annual-statement").with(jwt().jwt(token -> token.claim("scope", "web"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.movementCount").value(0))
                .andExpect(jsonPath("$.totalDeposits").value(0)).andExpect(jsonPath("$.totalCharges").value(0))
                .andExpect(jsonPath("$.netBalance").value(0));
        mvc.perform(get(ROOT + "/" + id + "/movements/latest").with(jwt().jwt(token -> token.claim("scope", "mobile"))))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM movimientos_anuales", Integer.class)).isZero();
    }

    @Test
    void operativeQueriesTakePriorityAndLegacyFallbackNeverImportsAccounts() throws Exception {
        history(55, "900");
        assertThat(banking.account(55).finalBalance()).isEqualByComparingTo("900");
        assertThat(accountCount()).isZero();
        account(55, "20");
        operations.execute(request(CREDIT, null, 55, "10"));
        for (String path : List.of(ROOT + "/55", ROOT + "/55/balance")) {
            String scope = path.endsWith("balance") ? "atm" : "mobile";
            mvc.perform(get(path).with(jwt().jwt(token -> token.claim("scope", scope))))
                    .andExpect(status().isOk()).andExpect(jsonPath(path.endsWith("balance") ? "$.balance" : "$.finalBalance").value(30));
        }
        assertThat(jdbc.queryForObject("SELECT saldo_final FROM intereses_mensuales WHERE cuenta_id=55", BigDecimal.class)).isEqualByComparingTo("900");
        assertThat(banking.account(55).initialBalance()).isEqualByComparingTo("20");
        mvc.perform(get(ROOT + "/404").with(jwt().jwt(token -> token.claim("scope", "web")))).andExpect(status().isNotFound());
    }

    @Test
    void schemaReinitializationPreservesAccountsLedgerAndSequence() {
        ManagedAccount created = open();
        operations.execute(request(CREDIT, null, created.accountId(), "12"));
        var rows = jdbc.queryForList("SELECT * FROM cuentas");
        var ledger = jdbc.queryForList("SELECT * FROM operaciones_cuenta");
        Long sequence = jdbc.queryForObject("SELECT last_value FROM cuentas_id_seq", Long.class);
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(Objects.requireNonNull(jdbc.getDataSource()));
        assertThat(jdbc.queryForList("SELECT * FROM cuentas")).isEqualTo(rows);
        assertThat(jdbc.queryForList("SELECT * FROM operaciones_cuenta")).isEqualTo(ledger);
        assertThat(jdbc.queryForObject("SELECT last_value FROM cuentas_id_seq", Long.class)).isEqualTo(sequence);
        assertThat(open().accountId()).isGreaterThan(created.accountId());
    }

    @Test
    void exhaustedIntegerIdentifiersRejectOpeningWithoutChangingExistingData() throws Exception {
        account(Integer.MAX_VALUE, "10");
        mvc.perform(post(ROOT).contentType(MediaType.APPLICATION_JSON).content(OPEN)
                .with(jwt().jwt(token -> token.claim("scope", "web")))).andExpect(status().isConflict());
        assertThat(accountCount()).isEqualTo(1);
        assertThat(balance(Integer.MAX_VALUE)).isEqualByComparingTo("10");
    }

    @Test
    void failedInsertRollsBackOpeningAndDoesNotReuseTheReservedIdentifier() {
        Long before = jdbc.queryForObject("SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM cuentas_id_seq", Long.class);
        jdbc.execute("CREATE FUNCTION fail_account_open() RETURNS TRIGGER LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Fallo aislado de apertura'; END; $$");
        jdbc.execute("CREATE TRIGGER fail_account_open BEFORE INSERT ON cuentas FOR EACH ROW EXECUTE FUNCTION fail_account_open()");
        try {
            assertThatThrownBy(this::open).isInstanceOf(DataAccessException.class);
            assertThat(accountCount()).isZero();
            assertThat(operationCount()).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER fail_account_open ON cuentas");
            jdbc.execute("DROP FUNCTION fail_account_open()");
        }
        assertThat(open().accountId()).isGreaterThan(before.intValue() + 1);
    }

    @Test
    void closingPreservesOriginalMonetaryResultsAndTheirIdempotency() {
        int id = open().accountId();
        OperationRequest credit = request(CREDIT, null, id, "10");
        OperationResult original = operations.execute(credit);
        operations.execute(request(DEBIT, id, null, "10"));
        management.close(id);
        assertThat(operations.execute(credit)).isEqualTo(original);
        assertThat(balance(id)).isEqualByComparingTo("0");
        assertThat(operationCount()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT estado FROM cuentas WHERE cuenta_id=?", String.class, id)).isEqualTo("CERRADA");
    }

    @Test
    void administrativeEndpointsRejectAtmInternalAndInsufficientScopesWithoutWrites() throws Exception {
        for (String scope : List.of("atm", "account.operations", "other", "")) {
            mvc.perform(post(ROOT).contentType(MediaType.APPLICATION_JSON).content(OPEN).with(jwt().jwt(token -> token.claim("scope", scope))))
                    .andExpect(status().isForbidden());
            mvc.perform(put(ROOT + "/1").contentType(MediaType.APPLICATION_JSON).content(UPDATE).with(jwt().jwt(token -> token.claim("scope", scope))))
                    .andExpect(status().isForbidden());
            mvc.perform(post(ROOT + "/1/close").with(jwt().jwt(token -> token.claim("scope", scope))))
                    .andExpect(status().isForbidden());
        }
        assertThat(accountCount()).isZero();
        verifyNoInteractions(customers);
    }

    @Test
    void administrativeEndpointsRequireAValidBearerToken() throws Exception {
        when(decoder.decode("invalid-token")).thenThrow(new BadJwtException("Token invalido de prueba"));
        for (String token : List.of("", "Bearer invalid-token")) {
            mvc.perform(post(ROOT).contentType(MediaType.APPLICATION_JSON).content(OPEN).header("Authorization", token)).andExpect(status().isUnauthorized());
            mvc.perform(put(ROOT + "/1").contentType(MediaType.APPLICATION_JSON).content(UPDATE).header("Authorization", token)).andExpect(status().isUnauthorized());
            mvc.perform(post(ROOT + "/1/close").header("Authorization", token)).andExpect(status().isUnauthorized());
        }
        assertThat(accountCount()).isZero();
        verifyNoInteractions(customers);
    }

    private ManagedAccount open() {
        return management.open(new OpenAccountRequest(17, "Cuenta nueva", "ahorro", new BigDecimal("0.01")), "token");
    }

    private int accountCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM cuentas", Integer.class);
    }

    private void history(int id, String balance) {
        jdbc.update("INSERT INTO intereses_mensuales VALUES (?, 'Cuenta historica', 'ahorro', 100, 0.01, ?)", id, new BigDecimal(balance));
    }

    private OperationRequest request(OperationType type, Integer source, Integer target, String amount) {
        return new OperationRequest(UUID.randomUUID(), type, source, target, new BigDecimal(amount));
    }
}
