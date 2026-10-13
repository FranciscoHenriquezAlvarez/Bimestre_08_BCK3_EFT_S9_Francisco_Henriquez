package cl.duoc.bancoxyz.core;

import static cl.duoc.bancoxyz.core.AccountOperationService.OperationType.*;
import static org.assertj.core.api.Assertions.*;

import cl.duoc.bancoxyz.core.AccountOperationService.*;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

class AccountClosingConcurrencyIntegrationTest extends OperationalAccountsIntegrationSupport {
    @Autowired AccountManagementService management;
    @Autowired PlatformTransactionManager transactions;

    @ParameterizedTest
    @ValueSource(strings = {"CREDIT", "DEBIT", "TRANSFER_IN", "TRANSFER_OUT"})
    void closingFirstBlocksAndRejectsTheConcurrentMonetaryOperation(String direction) throws Exception {
        account(1, "0");
        account(2, "100");
        OperationRequest request = request(direction);
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        try {
            var closing = executor.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                var result = management.close(1);
                locked.countDown();
                await(commit);
                return result;
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var monetary = executor.submit(() -> operations.execute(request));
            awaitDatabaseLock();
            assertThat(monetary.isDone()).isFalse();
            commit.countDown();
            assertThat(closing.get(10, TimeUnit.SECONDS).state()).isEqualTo("CERRADA");
            OperationResult rejected = monetary.get(10, TimeUnit.SECONDS);
            assertThat(rejected.status()).isEqualTo("REJECTED");
            assertThat(rejected.rejectionReason()).contains("cerrada");
            assertThat(balance(1)).isEqualByComparingTo("0");
            assertThat(balance(2)).isEqualByComparingTo("100");
            assertThat(operations.execute(request)).isEqualTo(rejected);
            assertThat(operationCount()).isEqualTo(1);
        } finally {
            commit.countDown();
            executor.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"CREDIT", "DEBIT", "TRANSFER_IN", "TRANSFER_OUT"})
    void monetaryOperationFirstMakesClosingReadTheCommittedBalance(String direction) throws Exception {
        boolean emptiesAccount = direction.equals("DEBIT") || direction.equals("TRANSFER_OUT");
        account(1, emptiesAccount ? "10" : "0");
        account(2, "100");
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        try {
            var monetary = executor.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                OperationResult result = operations.execute(request(direction));
                locked.countDown();
                await(commit);
                return result;
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var closing = executor.submit(() -> {
                try {
                    return management.close(1).state();
                } catch (ResponseStatusException error) {
                    assertThat(error.getStatusCode().value()).isEqualTo(409);
                    return "CONFLICT";
                }
            });
            awaitDatabaseLock();
            assertThat(closing.isDone()).isFalse();
            commit.countDown();
            assertThat(monetary.get(10, TimeUnit.SECONDS).status()).isEqualTo("COMPLETED");
            assertThat(closing.get(10, TimeUnit.SECONDS)).isEqualTo(emptiesAccount ? "CERRADA" : "CONFLICT");
            assertThat(balance(1)).isEqualByComparingTo(emptiesAccount ? "0" : "10");
            assertThat(balance(2)).isEqualByComparingTo(direction.equals("TRANSFER_IN") ? "90" : direction.equals("TRANSFER_OUT") ? "110" : "100");
            assertThat(operationCount()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT estado FROM cuentas WHERE cuenta_id=1", String.class))
                    .isEqualTo(emptiesAccount ? "CERRADA" : "ACTIVA");
        } finally {
            commit.countDown();
            executor.shutdownNow();
        }
    }

    private OperationRequest request(String direction) {
        return switch (direction) {
            case "CREDIT" -> new OperationRequest(UUID.randomUUID(), CREDIT, null, 1, BigDecimal.TEN);
            case "DEBIT" -> new OperationRequest(UUID.randomUUID(), DEBIT, 1, null, BigDecimal.TEN);
            case "TRANSFER_IN" -> new OperationRequest(UUID.randomUUID(), TRANSFER, 2, 1, BigDecimal.TEN);
            case "TRANSFER_OUT" -> new OperationRequest(UUID.randomUUID(), TRANSFER, 1, 2, BigDecimal.TEN);
            default -> throw new IllegalArgumentException("Direccion de prueba desconocida");
        };
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("No se libero la transaccion aislada");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Se interrumpio la prueba de concurrencia", error);
        }
    }

    private void awaitDatabaseLock() throws Exception {
        // Comprueba la espera real en PostgreSQL antes de liberar la transaccion ganadora.
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM pg_stat_activity
                    WHERE datname=current_database() AND wait_event_type='Lock'
                        AND query LIKE '%FROM cuentas%' AND pid<>pg_backend_pid()
                    """, Integer.class);
            if (waiting != null && waiting > 0) return;
            Thread.sleep(20);
        }
        throw new AssertionError("La operacion no espero el bloqueo de la cuenta");
    }
}
