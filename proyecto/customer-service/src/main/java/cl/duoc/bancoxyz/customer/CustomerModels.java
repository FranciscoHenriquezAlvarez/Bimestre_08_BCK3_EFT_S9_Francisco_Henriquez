package cl.duoc.bancoxyz.customer;

import java.time.Instant;

public final class CustomerModels {
    private CustomerModels() {
    }

    public enum Profile {
        ESTANDAR, PREFERENTE
    }

    public record CreateCustomerRequest(String firstName, String lastName, String email, String phone, Profile profile) {
    }

    public record PersonalInformation(String firstName, String lastName, String email, String phone) {
    }

    public record ProfileRequest(Profile profile) {
    }

    public record Customer(Integer customerId, String firstName, String lastName, String email, String phone,
            Profile profile, Instant createdAt, Instant updatedAt) {
    }

    public record CustomerProfile(Integer customerId, Profile profile, Instant updatedAt) {
    }

    public record CustomerError(int status, String message) {
    }
}
