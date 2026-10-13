package cl.duoc.bancoxyz.batch;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class MonthlyInterestDeterminismIntegrationTest extends BatchIntegrationSupport {

    @Autowired
    @Qualifier("monthlyInterestJob")
    private Job monthlyInterestJob;

    @Value("${bank.interest.savings-rate}")
    private BigDecimal savingsRate;

    @Value("${bank.interest.loan-rate}")
    private BigDecimal loanRate;

    @Value("${bank.batch.chunk-size}")
    private int chunkSize;

    @Test
    void debeConservarUltimoRegistroValidoYSaldosEnDosEjecuciones() throws Exception {
        CsvExpectation expected = expectedFromOriginalCsv();
        assertThat(expected.validRows()).isEqualTo(263);
        assertThat(expected.rows()).hasSize(50);
        assertThat(expected.conflictingAccounts()).isEqualTo(48);
        assertThat(expected.duplicateWithinChunk()).isTrue();

        // Los saldos sinteticos son independientes de los resultados historicos.
        for (MonthlyRow row : expected.rows()) {
            jdbcTemplate.update("""
                    INSERT INTO cuentas (cuenta_id, nombre, tipo, saldo_inicial, tasa, saldo_operativo)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, row.accountId(), "Cuenta sintetica " + row.accountId(), "ahorro",
                    BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("12345.67").add(
                            BigDecimal.valueOf(row.accountId())));
        }
        List<Map<String, Object>> operationalBefore = operationalBalances();

        JobExecution firstExecution = runJob(1L);
        assertHistoricalCounts(firstExecution);
        List<MonthlyRow> first = monthlyRows();
        assertThat(first).containsExactlyElementsOf(expected.rows());
        assertThat(operationalBalances()).isEqualTo(operationalBefore);

        JobExecution secondExecution = runJob(2L);
        assertHistoricalCounts(secondExecution);
        List<MonthlyRow> second = monthlyRows();
        assertThat(secondExecution.getJobInstance().getInstanceId())
                .isNotEqualTo(firstExecution.getJobInstance().getInstanceId());
        assertThat(second).containsExactlyElementsOf(first);
        assertThat(second).containsExactlyElementsOf(expected.rows());
        assertThat(operationalBalances()).isEqualTo(operationalBefore);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(DISTINCT cuenta_id) FROM intereses_mensuales",
                Integer.class)).isEqualTo(50);
    }

    private JobExecution runJob(long run) throws Exception {
        return jobLauncher.run(monthlyInterestJob, new JobParametersBuilder()
                .addString("test", "monthly-determinism")
                .addLong("test-run", run)
                .toJobParameters());
    }

    private void assertHistoricalCounts(JobExecution execution) {
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getStepExecutions()).hasSize(1);
        StepExecution step = execution.getStepExecutions().iterator().next();
        assertThat(step.getReadCount()).isEqualTo(1_000);
        assertThat(step.getWriteCount()).isEqualTo(263);
        assertThat(step.getSkipCount()).isEqualTo(737);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM intereses_mensuales", Integer.class))
                .isEqualTo(50);
    }

    private List<Map<String, Object>> operationalBalances() {
        return jdbcTemplate.queryForList("SELECT cuenta_id, saldo_operativo FROM cuentas ORDER BY cuenta_id");
    }

    private List<MonthlyRow> monthlyRows() {
        return jdbcTemplate.query("""
                SELECT cuenta_id, nombre, tipo, saldo_inicial, tasa, saldo_final
                FROM intereses_mensuales ORDER BY cuenta_id
                """, (rs, number) -> new MonthlyRow(rs.getInt("cuenta_id"), rs.getString("nombre"),
                rs.getString("tipo"), rs.getBigDecimal("saldo_inicial"), rs.getBigDecimal("tasa"),
                rs.getBigDecimal("saldo_final")));
    }

    private CsvExpectation expectedFromOriginalCsv() throws Exception {
        Map<Integer, MonthlyRow> lastValid = new TreeMap<>();
        Map<Integer, Set<MonthlyRow>> alternatives = new HashMap<>();
        Map<Integer, Set<Integer>> accountsByChunk = new HashMap<>();
        int validRows = 0;
        boolean duplicateWithinChunk = false;
        // El oraculo lee el CSV directamente, sin invocar el procesador ni consultar resultados previos.
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource("input/intereses.csv").getInputStream(), StandardCharsets.UTF_8))) {
            List<String> lines = reader.lines().skip(1).toList();
            assertThat(lines).hasSize(1_000);
            for (int index = 0; index < lines.size(); index++) {
                String[] fields = lines.get(index).split(",", -1);
                if (fields.length != 5) {
                    continue;
                }
                boolean missingField = false;
                for (int column = 0; column < fields.length; column++) {
                    fields[column] = fields[column].trim();
                    missingField |= fields[column].isEmpty();
                }
                if (missingField) {
                    continue;
                }
                try {
                    int accountId = Integer.parseInt(fields[0]);
                    BigDecimal balance = new BigDecimal(fields[2]);
                    int age = Integer.parseInt(fields[3]);
                    String type = fields[4].toLowerCase(Locale.ROOT);
                    if (balance.signum() <= 0 || age < 18 || age > 100
                            || !(type.equals("ahorro") || type.equals("prestamo"))) {
                        continue;
                    }
                    BigDecimal rate = type.equals("ahorro") ? savingsRate : loanRate;
                    BigDecimal interest = balance.multiply(rate);
                    MonthlyRow row = new MonthlyRow(accountId, fields[1], type,
                            balance.setScale(2, RoundingMode.HALF_UP), rate.setScale(4, RoundingMode.HALF_UP),
                            balance.add(interest).setScale(2, RoundingMode.HALF_UP));
                    lastValid.put(accountId, row);
                    alternatives.computeIfAbsent(accountId, key -> new HashSet<>()).add(row);
                    duplicateWithinChunk |= !accountsByChunk.computeIfAbsent(index / chunkSize,
                            key -> new HashSet<>()).add(accountId);
                    validRows++;
                } catch (NumberFormatException ex) {
                    // Los campos numericos invalidos no generan un resultado esperado.
                }
            }
        }
        long conflictingAccounts = alternatives.values().stream().filter(rows -> rows.size() > 1).count();
        return new CsvExpectation(new ArrayList<>(lastValid.values()), validRows, conflictingAccounts,
                duplicateWithinChunk);
    }

    private record MonthlyRow(int accountId, String name, String type, BigDecimal initialBalance,
            BigDecimal rate, BigDecimal finalBalance) {
    }

    private record CsvExpectation(List<MonthlyRow> rows, int validRows, long conflictingAccounts,
            boolean duplicateWithinChunk) {
    }
}
