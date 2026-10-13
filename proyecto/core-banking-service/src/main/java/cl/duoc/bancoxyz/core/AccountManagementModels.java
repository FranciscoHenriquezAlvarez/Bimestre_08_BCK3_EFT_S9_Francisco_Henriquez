package cl.duoc.bancoxyz.core;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.math.BigDecimal;
import java.time.Instant;

public final class AccountManagementModels {
    private AccountManagementModels() {
    }

    public record OpenAccountRequest(Integer customerId, String accountName, String accountType,
            BigDecimal interestRate) {
        @JsonAnySetter
        public void rejectUnknownField(String field, Object value) {
            throw new IllegalArgumentException("La apertura solo admite los campos administrativos definidos");
        }
    }

    public record UpdateAccountRequest(String accountName, String accountType, BigDecimal interestRate) {
        @JsonAnySetter
        public void rejectUnknownField(String field, Object value) {
            throw new IllegalArgumentException("El mantenimiento solo admite nombre, tipo y tasa");
        }
    }

    public record ManagedAccount(Integer accountId, Integer customerId, String accountName, String accountType,
            BigDecimal initialBalance, BigDecimal interestRate, BigDecimal finalBalance, String state,
            Instant createdAt, Instant updatedAt) {
    }

    public record AccountError(int status, String message) {
    }
}
