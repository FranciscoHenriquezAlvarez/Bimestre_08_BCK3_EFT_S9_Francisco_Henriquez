package cl.duoc.bancoxyz.core;

import static cl.duoc.bancoxyz.core.AccountOperationService.OperationType.CREDIT;
import static cl.duoc.bancoxyz.core.AccountOperationService.OperationType.DEBIT;
import static cl.duoc.bancoxyz.core.AccountOperationService.OperationType.TRANSFER;
import static org.junit.jupiter.api.Assertions.*;

import cl.duoc.bancoxyz.core.AccountOperationService.OperationRequest;
import cl.duoc.bancoxyz.core.AccountOperationService.OperationResult;
import cl.duoc.bancoxyz.core.AccountOperationService.OperationType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.web.server.ResponseStatusException;

class AccountOperationServiceIntegrationTest extends OperationalAccountsIntegrationSupport {
    @Test
    void debitAndCreditPersistFinalBalances() {
        account(1, "100.00");
        OperationResult debit = operations.execute(request(DEBIT, 1, null, "30.25"));
        assertEquals("COMPLETED", debit.status());
        assertEquals(new BigDecimal("69.75"), debit.sourceBalance());
        assertNull(debit.targetBalance());
        OperationResult credit = operations.execute(request(CREDIT, null, 1, "10.50"));
        assertEquals("COMPLETED", credit.status());
        assertEquals(new BigDecimal("80.25"), credit.targetBalance());
        assertEquals(new BigDecimal("80.25"), balance(1));
        assertEquals(2, operationCount());
        assertEquals(credit, operations.operation(credit.operationId()));
    }

    @Test
    void transferPreservesTotalBalance() {
        account(1, "100.00");
        account(2, "20.00");
        OperationResult result = operations.execute(request(TRANSFER, 1, 2, "35.50"));
        assertEquals("COMPLETED", result.status());
        assertEquals(new BigDecimal("64.50"), result.sourceBalance());
        assertEquals(new BigDecimal("55.50"), result.targetBalance());
        assertEquals(new BigDecimal("120.00"), balance(1).add(balance(2)));
        assertEquals(1, operationCount());
    }

    @Test
    void insufficientFundsRejectWithoutUpdatingEitherAccount() {
        account(1, "10.00");
        account(2, "20.00");
        OperationResult result = operations.execute(request(TRANSFER, 1, 2, "11.00"));
        assertEquals("REJECTED", result.status());
        assertEquals("Saldo insuficiente", result.rejectionReason());
        assertEquals(new BigDecimal("10.00"), balance(1));
        assertEquals(new BigDecimal("20.00"), balance(2));
        assertNotNull(result.completedAt());
    }

    @Test
    void closedSourceRejectsDebitAndTransfer() {
        account(1, "100.00");
        account(2, "20.00");
        jdbc.update("UPDATE cuentas SET estado='CERRADA' WHERE cuenta_id=1");
        assertEquals("REJECTED", operations.execute(request(DEBIT, 1, null, "10")).status());
        assertEquals("REJECTED", operations.execute(request(TRANSFER, 1, 2, "10")).status());
        assertEquals(new BigDecimal("100.00"), balance(1));
        assertEquals(new BigDecimal("20.00"), balance(2));
    }

    @Test
    void closedTargetRejectsCreditAndTransfer() {
        account(1, "100.00");
        account(2, "20.00");
        jdbc.update("UPDATE cuentas SET estado='CERRADA' WHERE cuenta_id=2");
        assertEquals("REJECTED", operations.execute(request(CREDIT, null, 2, "10")).status());
        assertEquals("REJECTED", operations.execute(request(TRANSFER, 1, 2, "10")).status());
        assertEquals(new BigDecimal("100.00"), balance(1));
        assertEquals(new BigDecimal("20.00"), balance(2));
    }

    @Test
    void missingAccountsLeaveAnAuditableRejection() {
        account(1, "100.00");
        assertEquals("REJECTED", operations.execute(request(DEBIT, 99, null, "10")).status());
        assertEquals("REJECTED", operations.execute(request(CREDIT, null, 99, "10")).status());
        assertEquals("REJECTED", operations.execute(request(TRANSFER, 1, 99, "10")).status());
        assertEquals(new BigDecimal("100.00"), balance(1));
        assertEquals(3, operationCount());
    }

    @Test
    void nonPositiveAmountsPersistRejections() {
        account(1, "100.00");
        for (String amount : new String[] {"0", "-10"}) {
            OperationRequest request = request(DEBIT, 1, null, amount);
            OperationResult result = operations.execute(request);
            assertEquals("REJECTED", result.status());
            assertEquals(result, operations.execute(request));
        }
        assertEquals(new BigDecimal("100.00"), balance(1));
        assertEquals(2, operationCount());
    }

    @Test
    void invalidRequestsAreRejectedBeforeAnyWrite() {
        account(1, "100.00");
        List<OperationRequest> invalid = new ArrayList<>();
        invalid.add(null);
        invalid.add(new OperationRequest(null, DEBIT, 1, null, BigDecimal.ONE));
        invalid.add(new OperationRequest(UUID.randomUUID(), null, 1, null, BigDecimal.ONE));
        invalid.add(new OperationRequest(UUID.randomUUID(), DEBIT, 1, null, null));
        invalid.add(request(DEBIT, null, null, "1"));
        invalid.add(request(DEBIT, 1, 2, "1"));
        invalid.add(request(CREDIT, 1, 2, "1"));
        invalid.add(request(TRANSFER, 1, 1, "1"));
        invalid.add(request(TRANSFER, 1, null, "1"));
        invalid.add(request(DEBIT, 1, null, "0.001"));
        invalid.add(request(DEBIT, 1, null, "1000000000000"));
        for (OperationRequest request : invalid) {
            ResponseStatusException error = assertThrows(ResponseStatusException.class,
                    () -> operations.execute(request));
            assertEquals(400, error.getStatusCode().value());
        }
        assertEquals(0, operationCount());
        assertEquals(new BigDecimal("100.00"), balance(1));
    }

    @Test
    void overflowingTargetRejectsCreditAndTransfer() {
        account(1, "100.00");
        account(2, "999999999999.99");
        assertEquals("REJECTED", operations.execute(request(CREDIT, null, 2, "0.01")).status());
        assertEquals("REJECTED", operations.execute(request(TRANSFER, 1, 2, "0.01")).status());
        assertEquals(new BigDecimal("100.00"), balance(1));
        assertEquals(new BigDecimal("999999999999.99"), balance(2));
    }

    @Test
    void repeatedRequestReturnsOriginalResultAfterLaterOperations() {
        account(1, "100.00");
        OperationRequest request = request(DEBIT, 1, null, "10");
        OperationResult original = operations.execute(request);
        operations.execute(request(CREDIT, null, 1, "50"));
        OperationRequest normalized = new OperationRequest(request.operationId(), DEBIT, 1, null,
                new BigDecimal("10.000"));
        assertEquals(original, operations.execute(normalized));
        assertEquals(new BigDecimal("90.00"), original.sourceBalance());
        assertEquals(new BigDecimal("140.00"), balance(1));
        assertEquals(2, operationCount());
    }

    @Test
    void repeatedRejectionRemainsRejectedAfterAccountReceivesFunds() {
        account(1, "5.00");
        OperationRequest request = request(DEBIT, 1, null, "10");
        OperationResult original = operations.execute(request);
        operations.execute(request(CREDIT, null, 1, "50"));
        assertEquals("REJECTED", original.status());
        assertEquals(original, operations.execute(request));
        assertEquals(new BigDecimal("55.00"), balance(1));
    }

    @Test
    void repeatedIdentifierWithDifferentAmountTypeOrAccountsReturnsConflict() {
        account(1, "100.00");
        account(2, "100.00");
        OperationRequest original = request(DEBIT, 1, null, "10");
        operations.execute(original);
        for (OperationRequest altered : List.of(
                new OperationRequest(original.operationId(), DEBIT, 1, null, new BigDecimal("20")),
                new OperationRequest(original.operationId(), DEBIT, 2, null, new BigDecimal("10")),
                new OperationRequest(original.operationId(), CREDIT, null, 1, new BigDecimal("10")),
                new OperationRequest(original.operationId(), TRANSFER, 1, 2, new BigDecimal("10")))) {
            ResponseStatusException error = assertThrows(ResponseStatusException.class,
                    () -> operations.execute(altered));
            assertEquals(409, error.getStatusCode().value());
        }
        assertEquals(new BigDecimal("90.00"), balance(1));
        assertEquals(new BigDecimal("100.00"), balance(2));
        assertEquals(1, operationCount());
    }

    @Test
    void concurrentDebitsCannotSpendTheSameFunds() throws Exception {
        account(1, "100.00");
        List<Callable<OperationResult>> calls = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            calls.add(() -> operations.execute(request(DEBIT, 1, null, "80")));
        }
        List<OperationResult> results = concurrent(calls);
        assertEquals(1, results.stream().filter(result -> "COMPLETED".equals(result.status())).count());
        assertEquals(5, results.stream().filter(result -> "REJECTED".equals(result.status())).count());
        assertEquals(new BigDecimal("20.00"), balance(1));
        assertEquals(6, operationCount());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM operaciones_cuenta WHERE estado='PENDING'",
                Integer.class));
    }

    @Test
    void concurrentIdenticalRequestsChargeOnceAndReturnIdenticalResults() throws Exception {
        account(1, "100.00");
        OperationRequest request = request(DEBIT, 1, null, "10");
        List<Callable<OperationResult>> calls = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            calls.add(() -> operations.execute(request));
        }
        List<OperationResult> results = concurrent(calls);
        results.forEach(result -> assertEquals(results.get(0), result));
        assertEquals(new BigDecimal("90.00"), balance(1));
        assertEquals(1, operationCount());
    }

    @Test
    void concurrentIdentifierCollisionDoesNotApplyBothRequests() throws Exception {
        account(1, "100.00");
        UUID id = UUID.randomUUID();
        List<String> results = concurrent(List.of(
                () -> executeOrConflict(new OperationRequest(id, DEBIT, 1, null, new BigDecimal("10"))),
                () -> executeOrConflict(new OperationRequest(id, DEBIT, 1, null, new BigDecimal("20")))));
        assertEquals(1, results.stream().filter("COMPLETED"::equals).count());
        assertEquals(1, results.stream().filter("CONFLICT"::equals).count());
        assertTrue(List.of(new BigDecimal("90.00"), new BigDecimal("80.00")).contains(balance(1)));
        assertEquals(1, operationCount());
    }

    @Test
    void oppositeConcurrentTransfersCompleteWithoutDeadlock() throws Exception {
        account(1, "1000.00");
        account(2, "1000.00");
        List<Callable<OperationResult>> calls = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            int source = index % 2 == 0 ? 1 : 2;
            int target = source == 1 ? 2 : 1;
            calls.add(() -> {
                OperationResult result = null;
                for (int transfer = 0; transfer < 5; transfer++) {
                    result = operations.execute(request(TRANSFER, source, target, "10"));
                    assertEquals("COMPLETED", result.status());
                }
                return result;
            });
        }
        concurrent(calls);
        assertEquals(new BigDecimal("1000.00"), balance(1));
        assertEquals(new BigDecimal("1000.00"), balance(2));
        assertEquals(30, operationCount());
    }

    @Test
    void operationWaitsForAccountLockAndReadsCommittedState() throws Exception {
        account(1, "100.00");
        var executor = Executors.newSingleThreadExecutor();
        try (var connection = isolatedConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.executeQuery("SELECT * FROM cuentas WHERE cuenta_id=1 FOR UPDATE").close();
                CountDownLatch started = new CountDownLatch(1);
                Future<OperationResult> pending = executor.submit(() -> {
                    started.countDown();
                    return operations.execute(request(DEBIT, 1, null, "10"));
                });
                assertTrue(started.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> pending.get(300, TimeUnit.MILLISECONDS));
                statement.executeUpdate("UPDATE cuentas SET estado='CERRADA' WHERE cuenta_id=1");
                connection.commit();
                assertEquals("REJECTED", pending.get(10, TimeUnit.SECONDS).status());
            }
        } finally {
            executor.shutdownNow();
        }
        assertEquals(new BigDecimal("100.00"), balance(1));
    }

    @Test
    void failureAfterSourceUpdateRollsBackBothBalancesAndOperationReservation() {
        account(1, "100.00");
        account(2, "20.00");
        OperationRequest request = request(TRANSFER, 1, 2, "10");
        // El fallo se provoca en PostgreSQL despues del debito y antes de confirmar el credito.
        jdbc.execute("""
                CREATE FUNCTION fail_target_update() RETURNS TRIGGER LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'Fallo aislado de prueba en cuenta destino'; END; $$
                """);
        jdbc.execute("""
                CREATE TRIGGER fail_target_update BEFORE UPDATE ON cuentas
                FOR EACH ROW WHEN (OLD.cuenta_id = 2) EXECUTE FUNCTION fail_target_update()
                """);
        try {
            assertThrows(DataAccessException.class, () -> operations.execute(request));
            assertEquals(new BigDecimal("100.00"), balance(1));
            assertEquals(new BigDecimal("20.00"), balance(2));
            assertEquals(0, operationCount());
        } finally {
            jdbc.execute("DROP TRIGGER fail_target_update ON cuentas");
            jdbc.execute("DROP FUNCTION fail_target_update()");
        }
        assertEquals("COMPLETED", operations.execute(request).status());
        assertEquals(new BigDecimal("90.00"), balance(1));
        assertEquals(new BigDecimal("30.00"), balance(2));
    }

    @Test
    void queryValidatesIdentifierAndMissingOperation() {
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> operations.operation(null)).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> operations.operation(UUID.randomUUID())).getStatusCode().value());
    }

    private OperationRequest request(OperationType type, Integer source, Integer target, String amount) {
        return new OperationRequest(UUID.randomUUID(), type, source, target, new BigDecimal(amount));
    }

    private String executeOrConflict(OperationRequest request) {
        try {
            return operations.execute(request).status();
        } catch (ResponseStatusException error) {
            assertEquals(409, error.getStatusCode().value());
            return "CONFLICT";
        }
    }

    private <T> List<T> concurrent(List<Callable<T>> calls) throws Exception {
        var executor = Executors.newFixedThreadPool(calls.size());
        CountDownLatch ready = new CountDownLatch(calls.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> pending = new ArrayList<>();
            for (Callable<T> call : calls) {
                pending.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return call.call();
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : pending) {
                results.add(future.get(20, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }
}
