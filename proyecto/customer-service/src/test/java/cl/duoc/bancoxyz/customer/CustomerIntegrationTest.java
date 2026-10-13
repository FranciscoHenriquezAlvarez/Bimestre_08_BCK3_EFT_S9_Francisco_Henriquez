package cl.duoc.bancoxyz.customer;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cl.duoc.bancoxyz.customer.CustomerModels.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {"spring.cloud.config.enabled=false", "eureka.client.enabled=false", "spring.sql.init.mode=always"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CustomerIntegrationTest {
    private static final List<String> OTHER_TABLES = List.of("cuentas", "operaciones_cuenta", "withdrawal_transactions",
            "banking_events", "intereses_mensuales", "transacciones_procesadas", "movimientos_anuales", "resumen_transacciones_diarias");
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("eft_customer_test").withUsername("eft_test").withPassword(UUID.randomUUID().toString())
            .withInitScript("isolation-fixture.sql");
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired CustomerService customers;
    @MockBean JwtDecoder decoder;

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void registersAndReadsThePersistedCustomerWithGeneratedIdentityAndNormalizedData() throws Exception {
        String email = email();
        var request = new CreateCustomerRequest("  Nombre  ", "  Prueba  ", "  " + email.toUpperCase() + "  ", "  ", Profile.ESTANDAR);
        var response = mvc.perform(post("/api/customers").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request))
                .with(jwt().jwt(token -> token.claim("scope", "web")))).andExpect(status().isCreated()).andReturn().getResponse();
        Customer created = json.readValue(response.getContentAsString(), Customer.class);
        assertThat(created.customerId()).isPositive();
        assertThat(response.getHeader("Location")).isEqualTo("/api/customers/" + created.customerId());
        assertThat(created.firstName()).isEqualTo("Nombre");
        assertThat(created.lastName()).isEqualTo("Prueba");
        assertThat(created.email()).isEqualTo(email);
        assertThat(created.phone()).isNull();
        assertThat(created.createdAt()).isEqualTo(created.updatedAt());
        assertThat(customers.find(created.customerId())).isEqualTo(created);
        mvc.perform(get("/api/customers/" + created.customerId()).with(jwt().jwt(token -> token.claim("scope", "mobile"))))
                .andExpect(status().isOk()).andExpect(content().json(response.getContentAsString()));
        assertThat(jdbc.queryForObject("SELECT data_type FROM information_schema.columns WHERE table_name='clientes' AND column_name='cliente_id'", String.class))
                .isEqualTo("integer");
    }

    @Test
    void unknownCustomersReturn404ForEveryReadAndUpdate() throws Exception {
        int id = Integer.MAX_VALUE;
        for (var request : new org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder[] {
                get("/api/customers/" + id), get("/api/customers/" + id + "/profile"),
                put("/api/customers/" + id).contentType(MediaType.APPLICATION_JSON).content(informationBody(email())),
                put("/api/customers/" + id + "/profile").contentType(MediaType.APPLICATION_JSON).content("{\"profile\":\"PREFERENTE\"}")}) {
            mvc.perform(request.with(jwt().jwt(token -> token.claim("scope", "web")))).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Cliente no encontrado"));
        }
    }

    @Test
    void personalUpdateChangesOnlyAuthorizedFieldsAndKeepsCreationDateAndProfile() throws Exception {
        Customer original = create();
        String nextEmail = email();
        var response = mvc.perform(put("/api/customers/" + original.customerId()).contentType(MediaType.APPLICATION_JSON)
                .content(informationBody(nextEmail)).with(jwt().jwt(token -> token.claim("scope", "mobile"))))
                .andExpect(status().isOk()).andReturn().getResponse();
        Customer updated = json.readValue(response.getContentAsString(), Customer.class);
        assertThat(updated.customerId()).isEqualTo(original.customerId());
        assertThat(updated.firstName()).isEqualTo("Nuevo nombre");
        assertThat(updated.lastName()).isEqualTo("Nuevo apellido");
        assertThat(updated.email()).isEqualTo(nextEmail);
        assertThat(updated.phone()).isEqualTo("+56 9 1234 5678");
        assertThat(updated.profile()).isEqualTo(original.profile());
        assertThat(updated.createdAt()).isEqualTo(original.createdAt());
        assertThat(updated.updatedAt()).isAfterOrEqualTo(original.updatedAt());
        assertThat(customers.find(original.customerId())).isEqualTo(updated);
    }

    @Test
    void profileReadAndUpdatePreservePersonalInformation() throws Exception {
        Customer original = create();
        String path = "/api/customers/" + original.customerId() + "/profile";
        mvc.perform(get(path).with(jwt().jwt(token -> token.claim("scope", "web"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.profile").value("ESTANDAR"))
                .andExpect(jsonPath("$.email").doesNotExist());
        mvc.perform(put(path).contentType(MediaType.APPLICATION_JSON).content("{\"profile\":\"PREFERENTE\"}")
                .with(jwt().jwt(token -> token.claim("scope", "mobile"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.customerId").value(original.customerId()))
                .andExpect(jsonPath("$.profile").value("PREFERENTE"));
        Customer updated = customers.find(original.customerId());
        assertThat(updated.profile()).isEqualTo(Profile.PREFERENTE);
        assertThat(updated.firstName()).isEqualTo(original.firstName());
        assertThat(updated.lastName()).isEqualTo(original.lastName());
        assertThat(updated.email()).isEqualTo(original.email());
        assertThat(updated.phone()).isEqualTo(original.phone());
        assertThat(updated.createdAt()).isEqualTo(original.createdAt());
    }

    @Test
    void duplicateEmailIgnoresCaseAndReturnsASafe409() throws Exception {
        Customer original = create();
        var request = new CreateCustomerRequest("Otro", "Cliente", original.email().toUpperCase(), null, Profile.PREFERENTE);
        mvc.perform(post("/api/customers").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request))
                .with(jwt().jwt(token -> token.claim("scope", "web")))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("El correo ya pertenece a otro cliente"));
        assertThat(emailCount(original.email())).isEqualTo(1);
    }

    @Test
    void duplicateEmailDuringUpdateLeavesAllOriginalFieldsUnchanged() throws Exception {
        Customer first = create();
        Customer second = create();
        mvc.perform(put("/api/customers/" + second.customerId()).contentType(MediaType.APPLICATION_JSON)
                .content(informationBody(first.email().toUpperCase())).with(jwt().jwt(token -> token.claim("scope", "web"))))
                .andExpect(status().isConflict());
        assertThat(customers.find(second.customerId())).isEqualTo(second);
        assertThat(customers.find(first.customerId())).isEqualTo(first);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"VIP\"", "\"web\"", "\"\"", "null", "1"})
    void unsupportedProfilesAndEnumOrdinalsReturn400(String profile) throws Exception {
        String body = json.writeValueAsString(new CreateCustomerRequest("Nombre", "Prueba", email(), null, Profile.ESTANDAR))
                .replace("\"ESTANDAR\"", profile);
        mvc.perform(post("/api/customers").contentType(MediaType.APPLICATION_JSON).content(body)
                .with(jwt().jwt(token -> token.claim("scope", "web")))).andExpect(status().isBadRequest());
        Customer original = create();
        mvc.perform(put("/api/customers/" + original.customerId() + "/profile").contentType(MediaType.APPLICATION_JSON)
                .content("{\"profile\":" + profile + "}").with(jwt().jwt(token -> token.claim("scope", "web"))))
                .andExpect(status().isBadRequest());
        assertThat(customers.find(original.customerId())).isEqualTo(original);
    }

    @Test
    void immutableAndUnrelatedFieldsCannotBeAssignedThroughJson() throws Exception {
        Customer original = create();
        String path = "/api/customers/" + original.customerId();
        for (String extra : new String[] {"\"customerId\":123", "\"createdAt\":\"2020-01-01T00:00:00Z\"", "\"profile\":\"PREFERENTE\""}) {
            String body = informationBody(email());
            body = body.substring(0, body.length() - 1) + "," + extra + "}";
            mvc.perform(put(path).contentType(MediaType.APPLICATION_JSON).content(body)
                    .with(jwt().jwt(token -> token.claim("scope", "web")))).andExpect(status().isBadRequest());
        }
        mvc.perform(put(path + "/profile").contentType(MediaType.APPLICATION_JSON)
                .content("{\"profile\":\"PREFERENTE\",\"email\":\"otro@example.test\"}")
                .with(jwt().jwt(token -> token.claim("scope", "web")))).andExpect(status().isBadRequest());
        String register = json.writeValueAsString(new CreateCustomerRequest("Nombre", "Prueba", email(), null, Profile.ESTANDAR));
        register = register.substring(0, register.length() - 1) + ",\"customerId\":123}";
        mvc.perform(post("/api/customers").contentType(MediaType.APPLICATION_JSON).content(register)
                .with(jwt().jwt(token -> token.claim("scope", "web")))).andExpect(status().isBadRequest());
        assertThat(customers.find(original.customerId())).isEqualTo(original);
    }

    @Test
    void malformedJsonIdentifiersAndMissingNamesReturn400() throws Exception {
        for (String id : new String[] {"abc", "0", "-1", "2147483648"}) {
            mvc.perform(get("/api/customers/" + id).with(jwt().jwt(token -> token.claim("scope", "web"))))
                    .andExpect(status().isBadRequest());
        }
        for (String body : new String[] {"{", "{}", "{\"firstName\":\"\",\"lastName\":\"Prueba\",\"email\":\"cliente@example.test\",\"profile\":\"ESTANDAR\"}",
                "{\"firstName\":\"Nombre\",\"email\":\"cliente@example.test\",\"profile\":\"ESTANDAR\"}"}) {
            mvc.perform(post("/api/customers").contentType(MediaType.APPLICATION_JSON).content(body)
                    .with(jwt().jwt(token -> token.claim("scope", "web")))).andExpect(status().isBadRequest());
        }
    }

    @Test
    void httpValidationRejectsInvalidPersonalInformationWithoutChangingTheCustomer() throws Exception {
        Customer original = create();
        for (PersonalInformation invalid : CustomerServiceTest.invalidInformation().toList()) {
            var registration = new CreateCustomerRequest(invalid.firstName(), invalid.lastName(), invalid.email(), invalid.phone(), Profile.ESTANDAR);
            mvc.perform(post("/api/customers").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(registration))
                    .with(jwt().jwt(token -> token.claim("scope", "web")))).andExpect(status().isBadRequest());
            mvc.perform(put("/api/customers/" + original.customerId()).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(invalid)).with(jwt().jwt(token -> token.claim("scope", "mobile"))))
                    .andExpect(status().isBadRequest());
        }
        assertThat(customers.find(original.customerId())).isEqualTo(original);
    }

    @Test
    void realSchemaConstraintsProtectTheTableAndCaseInsensitiveUniqueness() {
        for (String[] invalid : new String[][] {{"", "Prueba", email(), "ESTANDAR"}, {"Nombre", "", email(), "ESTANDAR"},
                {"Nombre", "Prueba", "correo-invalido", "ESTANDAR"}, {"Nombre", "Prueba", email(), "ADMIN"}}) {
            assertThatThrownBy(() -> jdbc.update("INSERT INTO clientes (nombre, apellido, correo, perfil) VALUES (?, ?, ?, ?)", (Object[]) invalid))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        Customer registered = customers.create(new CreateCustomerRequest("Nombre", "Prueba", email(), null, Profile.ESTANDAR));
        assertThatThrownBy(() -> jdbc.update("INSERT INTO clientes (nombre, apellido, correo, perfil) VALUES ('Otro', 'Prueba', ?, 'ESTANDAR')",
                registered.email().toUpperCase())).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(emailCount(registered.email())).isEqualTo(1);
    }

    @Test
    void initializationAndCustomerWritesPreserveExistingDataAndForeignTables() throws Exception {
        Customer customer = create();
        var initializer = new ResourceDatabasePopulator(new ClassPathResource("schema.sql"));
        initializer.execute(jdbc.getDataSource());
        initializer.execute(jdbc.getDataSource());
        assertThat(customers.find(customer.customerId())).isEqualTo(customer);
        customers.updateInformation(customer.customerId(), new PersonalInformation("Nuevo", "Nombre", email(), null));
        customers.updateProfile(customer.customerId(), new ProfileRequest(Profile.PREFERENTE));
        // Las ocho tablas centinela existian antes de inicializar la aplicacion, solo en este PostgreSQL aislado.
        for (String table : OTHER_TABLES) {
            assertThat(jdbc.queryForObject("SELECT dato FROM " + table + " WHERE id=1", String.class)).isEqualTo("Dato aislado previo");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='public' AND table_name=?", Integer.class, table))
                    .isEqualTo(2);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public'", Integer.class))
                .isEqualTo(OTHER_TABLES.size() + 1);
        System.out.println("EFT06 PostgreSQL: esquema repetible, cliente preservado y ocho tablas centinela intactas");
    }

    @Test
    void concurrentRegistrationsWithTheSameEmailCreateOnlyOneCustomer() throws Exception {
        String email = email();
        var barrier = new CyclicBarrier(6);
        var executor = Executors.newFixedThreadPool(6);
        try {
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int index = 0; index < 6; index++) {
                tasks.add(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    String body = json.writeValueAsString(new CreateCustomerRequest("Nombre", "Prueba", email, null, Profile.ESTANDAR));
                    return mvc.perform(post("/api/customers").contentType(MediaType.APPLICATION_JSON).content(body)
                            .with(jwt().jwt(token -> token.claim("scope", "web")))).andReturn().getResponse().getStatus();
                });
            }
            var statuses = new ArrayList<Integer>();
            for (var result : executor.invokeAll(tasks)) statuses.add(result.get());
            assertThat(statuses.stream().filter(status -> status == 201).count()).isEqualTo(1);
            assertThat(statuses.stream().filter(status -> status == 409).count()).isEqualTo(5);
        } finally {
            executor.shutdownNow();
        }
        assertThat(emailCount(email)).isEqualTo(1);
        System.out.println("EFT06 Concurrencia: seis registros, un 201 y cinco 409, un unico cliente persistido");
    }

    @Test
    void concurrentPersonalAndProfileUpdatesPreserveBothChanges() throws Exception {
        Customer original = create();
        String nextEmail = email();
        var barrier = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Object> information = () -> {
                barrier.await(5, TimeUnit.SECONDS);
                return customers.updateInformation(original.customerId(), new PersonalInformation("Nuevo", "Apellido", nextEmail, null));
            };
            Callable<Object> profile = () -> {
                barrier.await(5, TimeUnit.SECONDS);
                return customers.updateProfile(original.customerId(), new ProfileRequest(Profile.PREFERENTE));
            };
            for (var result : executor.invokeAll(List.of(information, profile))) result.get();
        } finally {
            executor.shutdownNow();
        }
        Customer updated = customers.find(original.customerId());
        assertThat(updated.firstName()).isEqualTo("Nuevo");
        assertThat(updated.lastName()).isEqualTo("Apellido");
        assertThat(updated.email()).isEqualTo(nextEmail);
        assertThat(updated.profile()).isEqualTo(Profile.PREFERENTE);
        assertThat(updated.createdAt()).isEqualTo(original.createdAt());
    }

    @Test
    void healthIsPublicWithTheRealDatasource() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }

    private Customer create() throws Exception {
        var request = new CreateCustomerRequest("Nombre", "Prueba", email(), null, Profile.ESTANDAR);
        var response = mvc.perform(post("/api/customers").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request))
                .with(jwt().jwt(token -> token.claim("scope", "web")))).andExpect(status().isCreated()).andReturn().getResponse();
        return json.readValue(response.getContentAsString(), Customer.class);
    }

    private String informationBody(String email) throws Exception {
        return json.writeValueAsString(new PersonalInformation("Nuevo nombre", "Nuevo apellido", email, "+56 9 1234 5678"));
    }

    private String email() {
        return "cliente-" + UUID.randomUUID() + "@example.test";
    }

    private int emailCount(String email) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM clientes WHERE lower(correo)=lower(?)", Integer.class, email);
    }
}
