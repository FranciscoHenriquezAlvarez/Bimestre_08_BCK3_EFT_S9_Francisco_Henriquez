package cl.duoc.bancoxyz.customer;

import cl.duoc.bancoxyz.customer.CustomerModels.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CustomerService {
    private static final String COLUMNS = "cliente_id, nombre, apellido, correo, telefono, perfil, creado_en, actualizado_en";
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Pattern PHONE = Pattern.compile("\\+?[0-9][0-9 ()-]{5,28}");
    private final JdbcTemplate jdbc;

    public CustomerService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Customer create(CreateCustomerRequest requested) {
        if (requested == null || requested.profile() == null) {
            throw invalid("El perfil es obligatorio");
        }
        PersonalInformation information = validate(new PersonalInformation(requested.firstName(), requested.lastName(),
                requested.email(), requested.phone()));
        try {
            return jdbc.queryForObject("""
                    INSERT INTO clientes (nombre, apellido, correo, telefono, perfil)
                    VALUES (?, ?, ?, ?, ?) RETURNING
                    """ + COLUMNS, this::customer, information.firstName(), information.lastName(), information.email(),
                    information.phone(), requested.profile().name());
        } catch (DuplicateKeyException error) {
            // El indice unico resuelve tambien registros concurrentes con el mismo correo.
            throw duplicateEmail();
        }
    }

    public Customer find(Integer id) {
        validateId(id);
        return required(jdbc.query("SELECT " + COLUMNS + " FROM clientes WHERE cliente_id=?", this::customer, id));
    }

    @Transactional
    public Customer updateInformation(Integer id, PersonalInformation requested) {
        validateId(id);
        PersonalInformation information = validate(requested);
        try {
            // UPDATE verifica la existencia y conserva perfil, identificador y fecha de registro.
            return required(jdbc.query("""
                    UPDATE clientes SET nombre=?, apellido=?, correo=?, telefono=?, actualizado_en=CURRENT_TIMESTAMP
                    WHERE cliente_id=? RETURNING
                    """ + COLUMNS, this::customer, information.firstName(), information.lastName(), information.email(),
                    information.phone(), id));
        } catch (DuplicateKeyException error) {
            throw duplicateEmail();
        }
    }

    public CustomerProfile profile(Integer id) {
        return profileOf(find(id));
    }

    @Transactional
    public CustomerProfile updateProfile(Integer id, ProfileRequest requested) {
        validateId(id);
        if (requested == null || requested.profile() == null) {
            throw invalid("El perfil es obligatorio");
        }
        Customer updated = required(jdbc.query("""
                UPDATE clientes SET perfil=?, actualizado_en=CURRENT_TIMESTAMP WHERE cliente_id=? RETURNING
                """ + COLUMNS, this::customer, requested.profile().name(), id));
        return profileOf(updated);
    }

    private PersonalInformation validate(PersonalInformation requested) {
        if (requested == null) {
            throw invalid("La informacion personal es obligatoria");
        }
        String firstName = text(requested.firstName());
        String lastName = text(requested.lastName());
        if (firstName == null || firstName.isEmpty() || firstName.length() > 100) {
            throw invalid("El nombre es obligatorio y admite hasta 100 caracteres");
        }
        if (lastName == null || lastName.isEmpty() || lastName.length() > 100) {
            throw invalid("El apellido es obligatorio y admite hasta 100 caracteres");
        }
        String email = text(requested.email());
        if (email == null || email.length() > 254 || !EMAIL.matcher(email).matches()) {
            throw invalid("El correo debe tener un formato valido y hasta 254 caracteres");
        }
        String phone = text(requested.phone());
        if (phone != null && phone.isEmpty()) {
            phone = null;
        }
        if (phone != null && (phone.length() > 30 || !PHONE.matcher(phone).matches())) {
            throw invalid("El telefono debe tener entre 6 y 30 caracteres, con digitos y separadores habituales");
        }
        return new PersonalInformation(firstName, lastName, email.toLowerCase(Locale.ROOT), phone);
    }

    private void validateId(Integer id) {
        if (id == null || id <= 0) {
            throw invalid("El identificador debe ser un entero positivo");
        }
    }

    private String text(String value) {
        return value == null ? null : value.strip();
    }

    private Customer customer(ResultSet row, int index) throws SQLException {
        return new Customer(row.getInt("cliente_id"), row.getString("nombre"), row.getString("apellido"),
                row.getString("correo"), row.getString("telefono"), Profile.valueOf(row.getString("perfil")),
                row.getTimestamp("creado_en").toInstant(), row.getTimestamp("actualizado_en").toInstant());
    }

    private Customer required(List<Customer> rows) {
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cliente no encontrado");
        }
        return rows.get(0);
    }

    private CustomerProfile profileOf(Customer customer) {
        return new CustomerProfile(customer.customerId(), customer.profile(), customer.updatedAt());
    }

    private ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseStatusException duplicateEmail() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "El correo ya pertenece a otro cliente");
    }
}
