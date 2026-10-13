package cl.duoc.bancoxyz.customer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import cl.duoc.bancoxyz.customer.CustomerModels.*;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class CustomerServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final CustomerService customers = new CustomerService(jdbc);

    @ParameterizedTest
    @MethodSource("invalidInformation")
    void invalidPersonalDataCannotReachTheDatabase(PersonalInformation information) {
        assertInvalid(() -> customers.create(new CreateCustomerRequest(information.firstName(), information.lastName(),
                information.email(), information.phone(), Profile.ESTANDAR)));
        assertInvalid(() -> customers.updateInformation(1, information));
        verifyNoInteractions(jdbc);
    }

    static Stream<PersonalInformation> invalidInformation() {
        return Stream.of(
                new PersonalInformation(null, "Prueba", "cliente@example.test", null),
                new PersonalInformation("Nombre", "   ", "cliente@example.test", null),
                new PersonalInformation("n".repeat(101), "Prueba", "cliente@example.test", null),
                new PersonalInformation("Nombre", "Prueba", "correo-invalido", null),
                new PersonalInformation("Nombre", "Prueba", "a".repeat(242) + "@example.test", null),
                new PersonalInformation("Nombre", "Prueba", "cliente@example.test", "abc123"),
                new PersonalInformation("Nombre", "Prueba", "cliente@example.test", "1".repeat(31)),
                new PersonalInformation("Nombre", "Prueba", "cliente@example.test", "12345"));
    }

    @Test
    void registrationRequiresABusinessProfile() {
        assertInvalid(() -> customers.create(new CreateCustomerRequest("Nombre", "Prueba", "cliente@example.test", null, null)));
        verifyNoInteractions(jdbc);
    }

    @Test
    void profileUpdateRequiresAProfile() {
        assertInvalid(() -> customers.updateProfile(1, null));
        assertInvalid(() -> customers.updateProfile(1, new ProfileRequest(null)));
        verifyNoInteractions(jdbc);
    }

    @Test
    void invalidIdentifiersCannotReadOrUpdateCustomers() {
        for (Integer id : Arrays.asList(null, 0, -1)) {
            assertInvalid(() -> customers.find(id));
            assertInvalid(() -> customers.updateProfile(id, new ProfileRequest(Profile.PREFERENTE)));
        }
        verifyNoInteractions(jdbc);
    }

    @Test
    void missingInformationCannotCreateOrUpdateACustomer() {
        assertInvalid(() -> customers.create(null));
        assertInvalid(() -> customers.updateInformation(1, null));
        verifyNoInteractions(jdbc);
    }

    private void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable request) {
        ResponseStatusException error = catchThrowableOfType(request, ResponseStatusException.class);
        assertThat(error.getStatusCode().value()).isEqualTo(400);
    }
}
