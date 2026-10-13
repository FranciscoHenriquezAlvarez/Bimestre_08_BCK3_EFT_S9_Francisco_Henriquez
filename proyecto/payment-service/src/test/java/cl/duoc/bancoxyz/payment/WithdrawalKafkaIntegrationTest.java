package cl.duoc.bancoxyz.payment;

import static org.assertj.core.api.Assertions.assertThat;

import cl.duoc.bancoxyz.events.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.*;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.AcknowledgingMessageListener;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WithdrawalKafkaIntegrationTest {
    private static final String GROUP = "core-banking-withdrawal-group";
    private static final List<String> TOPICS = List.of(TopicNames.WITHDRAWAL_REQUESTED,
            TopicNames.WITHDRAWAL_COMPLETED, TopicNames.WITHDRAWAL_REJECTED);
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private static final String SECRET = UUID.randomUUID().toString();
    private static final Map<String, String> CHANNEL_SECRETS = Map.of("web", UUID.randomUUID().toString(),
            "mobile", UUID.randomUUID().toString(), "atm", UUID.randomUUID().toString());
    private static final AtomicBoolean CORE_UNAVAILABLE = new AtomicBoolean();
    private static final AtomicBoolean WRONG_SCOPE = new AtomicBoolean();
    private static final AtomicReference<ResponseDelay> DELAY = new AtomicReference<>();
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("eft_withdrawal_kafka_test").withUsername("eft_test")
            .withPassword(UUID.randomUUID().toString());
    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");
    private static TestApplicationProcess authorization;
    private static TestApplicationProcess core;
    private static HttpServer proxy;
    private static ExecutorService proxyThreads;
    private static int authPort;
    private static int corePort;
    private static String webToken;
    private static String atmToken;
    private static AdminClient admin;
    private static KafkaProducer<String, WithdrawalRequestedEvent> requests;
    private ConfigurableApplicationContext payment;
    private KafkaConsumer<String, byte[]> results;

    @BeforeAll
    static void startOnlyIsolatedInfrastructure() throws Exception {
        admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000));
        admin.createTopics(TOPICS.stream().map(topic -> new NewTopic(topic, 3, (short) 1)).toList())
                .all().get(15, TimeUnit.SECONDS);
        Map<TopicPartition, OffsetAndMetadata> initialOffsets = new HashMap<>();
        for (int partition = 0; partition < 3; partition++) {
            initialOffsets.put(new TopicPartition(TopicNames.WITHDRAWAL_REQUESTED, partition), new OffsetAndMetadata(0));
        }
        // Este grupo y estos topics son nuevos en el broker aislado, nunca offsets del proyecto real.
        admin.alterConsumerGroupOffsets(GROUP, initialOffsets).all().get(10, TimeUnit.SECONDS);
        requests = new KafkaProducer<>(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class,
                ProducerConfig.ACKS_CONFIG, "all"));

        authPort = TestApplicationProcess.freePort();
        Map<String, String> environment = new HashMap<>();
        environment.put("OAUTH2_ISSUER", "http://localhost:" + authPort);
        environment.put("OAUTH2_JWK_SET_URI", "http://localhost:" + authPort + "/oauth2/jwks");
        environment.put("OAUTH2_PAYMENT_SECRET", SECRET);
        CHANNEL_SECRETS.forEach((channel, secret) -> environment.put("OAUTH2_" + channel.toUpperCase() + "_SECRET", secret));
        List<String> secrets = new ArrayList<>(CHANNEL_SECRETS.values());
        secrets.add(SECRET);
        secrets.add(POSTGRES.getPassword());
        authorization = new TestApplicationProcess("authorization-server",
                "cl.duoc.bancoxyz.authorization.AuthorizationServerApplication",
                TestApplicationProcess.moduleClasspath("authorization-server",
                        "cl.duoc.bancoxyz.authorization.AuthorizationServerTest"), authPort, environment, List.of(), secrets);
        authorization.awaitHealth(authPort);
        webToken = token("web", CHANNEL_SECRETS.get("web"), "web");
        atmToken = token("atm", CHANNEL_SECRETS.get("atm"), "atm");

        corePort = TestApplicationProcess.freePort();
        Map<String, String> coreEnvironment = new HashMap<>(environment);
        coreEnvironment.put("SPRING_DATASOURCE_URL", POSTGRES.getJdbcUrl());
        coreEnvironment.put("SPRING_DATASOURCE_USERNAME", POSTGRES.getUsername());
        coreEnvironment.put("SPRING_DATASOURCE_PASSWORD", POSTGRES.getPassword());
        coreEnvironment.put("KAFKA_BOOTSTRAP_SERVERS", KAFKA.getBootstrapServers());
        core = new TestApplicationProcess("core-banking-service", "cl.duoc.bancoxyz.BankBatchApplication",
                TestApplicationProcess.moduleClasspath("core-banking-service",
                        "cl.duoc.bancoxyz.core.AccountOperationServiceIntegrationTest"), corePort, coreEnvironment,
                List.of("--spring.kafka.listener.auto-startup=true", "--spring.kafka.admin.auto-create=false",
                        "--bank.config-origin=Prueba Kafka aislada"), secrets);
        core.awaitHealth(corePort);
        proxy = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        proxyThreads = Executors.newCachedThreadPool();
        proxy.setExecutor(proxyThreads);
        proxy.createContext("/", WithdrawalKafkaIntegrationTest::forwardToCore);
        proxy.start();
    }

    @AfterAll
    static void stopOnlyTestProcesses() throws Exception {
        try {
            if (requests != null) requests.close(Duration.ofSeconds(5));
            if (admin != null) admin.close(Duration.ofSeconds(5));
            if (proxy != null) proxy.stop(0);
            if (proxyThreads != null) proxyThreads.shutdownNow();
            if (core != null) core.close();
        } finally {
            if (authorization != null) authorization.close();
        }
    }

    @BeforeEach
    void prepareIsolatedAccountsWithoutResettingKafkaOffsets() throws Exception {
        CORE_UNAVAILABLE.set(false);
        WRONG_SCOPE.set(false);
        DELAY.set(null);
        WithdrawalPublicationFault.ENABLED.set(false);
        WithdrawalPublicationFault.ORIGINAL_EVENT.set(null);
        for (String table : List.of("banking_events", "withdrawal_transactions", "operaciones_cuenta", "cuentas", "intereses_mensuales")) {
            sql("DELETE FROM " + table);
        }
        sql("""
                INSERT INTO cuentas (cuenta_id, nombre, tipo, saldo_inicial, tasa, saldo_operativo)
                VALUES (101, 'Cuenta aislada', 'ahorro', 100000, 0.01, 100000)
                """);
        sql("""
                INSERT INTO intereses_mensuales (cuenta_id, nombre, tipo, saldo_inicial, tasa, saldo_final)
                VALUES (101, 'Dato Batch aislado', 'ahorro', 777777, 0.01, 777777)
                """);
        results = new KafkaConsumer<>(Map.of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "result-observer-" + UUID.randomUUID(),
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class));
        List<TopicPartition> partitions = new ArrayList<>();
        for (String topic : List.of(TopicNames.WITHDRAWAL_COMPLETED, TopicNames.WITHDRAWAL_REJECTED)) {
            for (int partition = 0; partition < 3; partition++) partitions.add(new TopicPartition(topic, partition));
        }
        results.assign(partitions);
        results.seekToEnd(partitions);
        for (TopicPartition partition : partitions) results.position(partition);
        payment = startPayment(SECRET, false);
    }

    @AfterEach
    void closeOnlyThePaymentTestContext() {
        if (payment != null) payment.close();
        if (results != null) results.close(Duration.ofSeconds(5));
        WithdrawalPublicationFault.ENABLED.set(false);
    }

    @Test
    @Order(1)
    void defaultConsumerIsInactiveAndExplicitIsolatedActivationCommitsOnlyAfterPublication() throws Exception {
        var requested = requested("10000");
        RecordMetadata input = send(requested);
        assertThat(listener().isRunning()).isFalse();
        assertThat(committed(input)).isEqualTo(input.offset());
        assertThat(count("operaciones_cuenta")).isZero();
        assertThat(admin.describeConsumerGroups(List.of(GROUP)).all().get(5, TimeUnit.SECONDS).get(GROUP).members()).isEmpty();
        assertThat(listener().getContainerProperties().getAckMode()).isEqualTo(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        assertThat(listener().getContainerProperties().isSyncCommits()).isTrue();
        payment.close();
        payment = startPayment(SECRET, true);
        JsonNode event = resultFor(requested.transactionId(), TopicNames.WITHDRAWAL_COMPLETED);
        awaitCommitted(input);
        assertThat(event.path("finalBalance").decimalValue()).isEqualByComparingTo("90000");
        assertThat(balance("cuentas", "saldo_operativo")).isEqualByComparingTo("90000");
        assertLegacyBalance();
        evidence("Apertura explicita, broker confirma resultado antes de offset=" + committed(input));
    }

    @Test
    @Order(2)
    void insufficientClosedMissingAndInvalidAtmAmountsPublishStableRejections() throws Exception {
        listener().start();
        List<WithdrawalRequestedEvent> rejected = new ArrayList<>();
        rejected.add(requested("110000"));
        rejected.add(requested("0"));
        rejected.add(requested("-10000"));
        rejected.add(requested("5000"));
        rejected.add(new WithdrawalRequestedEvent(UUID.randomUUID(), UUID.randomUUID(), 999, new BigDecimal("10000"), Instant.now()));
        for (WithdrawalRequestedEvent requested : rejected) {
            RecordMetadata input = send(requested);
            JsonNode event = resultFor(requested.transactionId(), TopicNames.WITHDRAWAL_REJECTED);
            assertThat(event.path("reason").asText()).isNotBlank();
            assertThat(event.path("amount").decimalValue()).isEqualByComparingTo(requested.amount());
            awaitCommitted(input);
        }
        sql("UPDATE cuentas SET estado='CERRADA' WHERE cuenta_id=101");
        var closed = requested("10000");
        RecordMetadata input = send(closed);
        assertThat(resultFor(closed.transactionId(), TopicNames.WITHDRAWAL_REJECTED).path("reason").asText()).contains("cerrada");
        awaitCommitted(input);
        assertThat(balance("cuentas", "saldo_operativo")).isEqualByComparingTo("100000");
        assertLegacyBalance();
        evidence("Seis rechazos publicados y reconocidos; saldo operativo y Batch conservados");
    }

    @Test
    @Order(3)
    void differentRequestEventIdRepublishesTheSameOriginalResultWithoutAnotherCharge() throws Exception {
        listener().start();
        var requested = requested("10000");
        RecordMetadata firstInput = send(requested);
        JsonNode original = resultFor(requested.transactionId(), TopicNames.WITHDRAWAL_COMPLETED);
        awaitCommitted(firstInput);
        sql("UPDATE cuentas SET saldo_operativo=140000 WHERE cuenta_id=101");
        var repeated = new WithdrawalRequestedEvent(UUID.randomUUID(), requested.transactionId(), 101,
                new BigDecimal("10000.00"), requested.occurredAt().plusSeconds(30));
        RecordMetadata secondInput = send(repeated);
        assertThat(resultFor(requested.transactionId(), TopicNames.WITHDRAWAL_COMPLETED)).isEqualTo(original);
        awaitCommitted(secondInput);
        assertThat(count("operaciones_cuenta")).isEqualTo(1);
        assertThat(count("banking_events")).isEqualTo(2);
        assertThat(balance("cuentas", "saldo_operativo")).isEqualByComparingTo("140000");
        assertLegacyBalance();
        evidence("Caso D: otro eventId, mismo resultado eventId=" + original.path("eventId").asText());
    }

    @Test
    @Order(4)
    void lostHttpResponseAfterPostgresCommitIsRecoveredByOneQuery() throws Exception {
        var requested = requested("10000");
        ResponseDelay delay = new ResponseDelay(new CountDownLatch(1), new CountDownLatch(1));
        DELAY.set(delay);
        listener().start();
        try {
            RecordMetadata input = send(requested);
            assertThat(delay.committed().await(10, TimeUnit.SECONDS)).isTrue();
            JsonNode event = resultFor(requested.transactionId(), TopicNames.WITHDRAWAL_COMPLETED);
            awaitCommitted(input);
            assertThat(event).isEqualTo(persistedResult(requested.transactionId()));
            assertThat(count("operaciones_cuenta")).isEqualTo(1);
            assertThat(balance("cuentas", "saldo_operativo")).isEqualByComparingTo("90000");
            assertLegacyBalance();
            evidence("Caso A: commit confirmado, respuesta HTTP retenida, consulta recupera evento original y offset=" + committed(input));
        } finally {
            delay.release().countDown();
        }
    }

    @Test
    @Order(5)
    void producerCannotPublishAndRestartRedeliversInputWithSamePersistedResult() throws Exception {
        WithdrawalPublicationFault.ENABLED.set(true);
        var requested = requested("10000");
        RecordMetadata input = send(requested);
        listener().start();
        awaitStopped();
        assertThat(committed(input)).isEqualTo(input.offset());
        assertThat(count("operaciones_cuenta")).isEqualTo(1);
        JsonNode original = persistedResult(requested.transactionId());
        JsonNode attempted = JSON.valueToTree(WithdrawalPublicationFault.ORIGINAL_EVENT.get());
        assertThat(attempted).isEqualTo(original);
        assertThat(results.poll(Duration.ofMillis(300))).isEmpty();
        WithdrawalPublicationFault.ENABLED.set(false);
        listener().start();
        assertThat(resultFor(requested.transactionId(), TopicNames.WITHDRAWAL_COMPLETED)).isEqualTo(original);
        awaitCommitted(input);
        assertThat(count("operaciones_cuenta")).isEqualTo(1);
        assertThat(balance("cuentas", "saldo_operativo")).isEqualByComparingTo("90000");
        assertLegacyBalance();
        evidence("Caso B: fallo real de metadata del productor, offset sin avanzar; reinicio reentrega y confirma el mismo evento");
    }

    @Test
    @Order(6)
    @SuppressWarnings("unchecked")
    void resultConfirmedButInputAcknowledgmentFailsAndRedeliveryPublishesSameEvent() throws Exception {
        Object originalAdapter = listener().getContainerProperties().getMessageListener();
        WithdrawalRequestedConsumer coordinator = payment.getBean(WithdrawalRequestedConsumer.class);
        listener().getContainerProperties().setMessageListener((AcknowledgingMessageListener<String, WithdrawalRequestedEvent>)
                (record, acknowledgment) -> coordinator.consume(record, () -> {
                    throw new IllegalStateException("Fallo aislado al reconocer entrada");
                }));
        var requested = requested("10000");
        RecordMetadata input = send(requested);
        listener().start();
        JsonNode original = resultFor(requested.transactionId(), TopicNames.WITHDRAWAL_COMPLETED);
        awaitStopped();
        assertThat(committed(input)).isEqualTo(input.offset());
        listener().getContainerProperties().setMessageListener(originalAdapter);
        listener().start();
        assertThat(resultFor(requested.transactionId(), TopicNames.WITHDRAWAL_COMPLETED)).isEqualTo(original);
        awaitCommitted(input);
        assertThat(count("operaciones_cuenta")).isEqualTo(1);
        assertThat(balance("cuentas", "saldo_operativo")).isEqualByComparingTo("90000");
        evidence("Caso C: broker confirma resultado; fallo de acknowledge inyectado no confirma entrada; reentrega repite eventId, cargo unico");
    }

    @Test
    @Order(7)
    void coreNetworkFailureStopsWithoutAcknowledgmentAndCanBeRecovered() throws Exception {
        CORE_UNAVAILABLE.set(true);
        var requested = requested("10000");
        RecordMetadata input = send(requested);
        listener().start();
        awaitStopped();
        assertThat(committed(input)).isEqualTo(input.offset());
        assertThat(count("operaciones_cuenta")).isZero();
        CORE_UNAVAILABLE.set(false);
        listener().start();
        resultFor(requested.transactionId(), TopicNames.WITHDRAWAL_COMPLETED);
        awaitCommitted(input);
        assertThat(balance("cuentas", "saldo_operativo")).isEqualByComparingTo("90000");
        evidence("Caso E: conexion HTTP cerrada, entrada conservada y recuperada tras restablecer Core");
    }

    @Test
    @Order(8)
    void unavailableInternalTokenStopsAndCorrectCredentialRecoversTheSameInput() throws Exception {
        payment.close();
        payment = startPayment(UUID.randomUUID().toString(), false);
        var requested = requested("10000");
        RecordMetadata input = send(requested);
        listener().start();
        awaitStopped();
        assertThat(committed(input)).isEqualTo(input.offset());
        assertThat(count("operaciones_cuenta")).isZero();
        payment.close();
        payment = startPayment(SECRET, true);
        resultFor(requested.transactionId(), TopicNames.WITHDRAWAL_COMPLETED);
        awaitCommitted(input);
        assertThat(count("operaciones_cuenta")).isEqualTo(1);
        evidence("Caso F: Authorization Server rechaza credencial de prueba; nueva configuracion recupera entrada sin cargo previo");
    }

    @Test
    @Order(9)
    void rejectedInternalScopeDoesNotChargeAndAtmStatusStillUsesItsExistingContract() throws Exception {
        WRONG_SCOPE.set(true);
        var requested = requested("10000");
        RecordMetadata input = send(requested);
        listener().start();
        awaitStopped();
        assertThat(committed(input)).isEqualTo(input.offset());
        assertThat(count("operaciones_cuenta")).isZero();
        WRONG_SCOPE.set(false);
        listener().start();
        JsonNode event = resultFor(requested.transactionId(), TopicNames.WITHDRAWAL_COMPLETED);
        awaitCommitted(input);
        var response = call("/api/core/accounts/async-withdrawals/" + requested.transactionId(), atmToken);
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode status = JSON.readTree(response.body());
        assertThat(status.path("transactionId").asText()).isEqualTo(requested.transactionId().toString());
        assertThat(status.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(status.path("finalBalance").decimalValue()).isEqualByComparingTo(event.path("finalBalance").decimalValue());
        assertThat(call("/api/core/internal/withdrawals/" + requested.transactionId(), atmToken).statusCode()).isEqualTo(403);
        assertLegacyBalance();
        evidence("Caso F: Core rechaza scope de prueba; recuperacion con identidad propia y consulta ATM compatible");
    }

    @Test
    @Order(10)
    void conflictingTransactionStopsWithoutAnotherChargeOrContradictoryResult() throws Exception {
        listener().start();
        var requested = requested("10000");
        RecordMetadata first = send(requested);
        JsonNode original = resultFor(requested.transactionId(), TopicNames.WITHDRAWAL_COMPLETED);
        awaitCommitted(first);
        var conflicting = new WithdrawalRequestedEvent(UUID.randomUUID(), requested.transactionId(), 101,
                new BigDecimal("20000"), Instant.now());
        RecordMetadata badInput = send(conflicting);
        awaitStopped();
        assertThat(committed(badInput)).isEqualTo(badInput.offset());
        assertThat(persistedResult(requested.transactionId())).isEqualTo(original);
        assertThat(results.poll(Duration.ofMillis(300))).isEmpty();
        assertThat(balance("cuentas", "saldo_operativo")).isEqualByComparingTo("90000");
        assertThat(count("operaciones_cuenta")).isEqualTo(1);
        assertLegacyBalance();
        evidence("Conflicto 409: listener detenido y offset conservado para revision, sin segundo cargo ni evento contradictorio");
    }

    private ConfigurableApplicationContext startPayment(String secret, boolean enabled) {
        Map<String, Object> properties = new HashMap<>();
        properties.put("server.port", 0);
        properties.put("spring.cloud.config.enabled", false);
        properties.put("eureka.client.enabled", false);
        properties.put("debug", false);
        properties.put("trace", false);
        properties.put("logging.level.root", "WARN");
        properties.put("spring.security.oauth2.resourceserver.jwt.issuer-uri", "http://localhost:" + authPort);
        properties.put("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", "http://localhost:" + authPort + "/oauth2/jwks");
        properties.put("spring.security.oauth2.client.provider.bank-authorization.token-uri", "http://localhost:" + authPort + "/oauth2/token");
        properties.put("spring.security.oauth2.client.registration.core-operations.client-secret", secret);
        properties.put("spring.cloud.discovery.client.simple.instances.core-banking-service[0].uri", "http://localhost:" + proxy.getAddress().getPort());
        properties.put("payment.http.read-timeout-ms", 1000);
        properties.put("payment.kafka.withdrawals.enabled", enabled);
        properties.put("payment.kafka.publish-timeout-ms", 2000);
        properties.put("spring.kafka.bootstrap-servers", KAFKA.getBootstrapServers());
        properties.put("spring.kafka.listener.concurrency", 1);
        properties.put("spring.kafka.listener.poll-timeout", "200ms");
        properties.put("spring.kafka.producer.properties.max.block.ms", 1000);
        properties.put("spring.kafka.producer.properties.interceptor.classes", WithdrawalPublicationFault.class.getName());
        SpringApplication application = new SpringApplication(PaymentApplication.class);
        application.addInitializers(context -> context.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("step05-isolated", properties)));
        return application.run("--spring.config.location=classpath:application.properties");
    }

    private MessageListenerContainer listener() {
        return payment.getBean(KafkaListenerEndpointRegistry.class).getListenerContainer("paymentWithdrawals");
    }

    private RecordMetadata send(WithdrawalRequestedEvent requested) throws Exception {
        return requests.send(new ProducerRecord<>(TopicNames.WITHDRAWAL_REQUESTED, requested.accountId().toString(), requested))
                .get(10, TimeUnit.SECONDS);
    }

    private JsonNode resultFor(UUID id, String expectedTopic) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            for (ConsumerRecord<String, byte[]> record : results.poll(Duration.ofMillis(200))) {
                JsonNode event = JSON.readTree(record.value());
                if (id.toString().equals(event.path("transactionId").asText())) {
                    assertThat(record.topic()).isEqualTo(expectedTopic);
                    assertThat(record.key()).isEqualTo(event.path("accountId").asText());
                    return normalizedEvent(event);
                }
            }
        }
        throw new AssertionError("No se recibio resultado Kafka para " + id);
    }

    private long committed(RecordMetadata input) throws Exception {
        var offsets = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
        return offsets.get(new TopicPartition(input.topic(), input.partition())).offset();
    }

    private void awaitCommitted(RecordMetadata input) throws Exception {
        await(() -> {
            try { return committed(input) == input.offset() + 1; }
            catch (Exception error) { throw new IllegalStateException(error); }
        }, "No se confirmo el offset posterior al resultado");
    }

    private void awaitStopped() throws Exception {
        await(() -> !listener().isRunning(), "El listener no se detuvo ante el fallo");
        await(() -> {
            try { return admin.describeConsumerGroups(List.of(GROUP)).all().get(5, TimeUnit.SECONDS).get(GROUP).members().isEmpty(); }
            catch (Exception error) { throw new IllegalStateException(error); }
        }, "El consumidor anterior no abandono el grupo aislado");
    }

    private void await(BooleanSupplier condition, String message) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(100);
        }
        throw new AssertionError(message);
    }

    private JsonNode persistedResult(UUID id) throws Exception {
        try (Connection connection = connection(); var statement = connection.prepareStatement(
                "SELECT payload::text FROM banking_events WHERE transaction_id=? AND event_type IN ('WithdrawalCompleted','WithdrawalRejected')")) {
            statement.setObject(1, id);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return normalizedEvent(JSON.readTree(rows.getString(1)));
            }
        }
    }

    private JsonNode normalizedEvent(JsonNode event) throws Exception {
        // HTTP y el serializador Kafka existente representan Instant de formas distintas.
        Object original = event.has("finalBalance") ? JSON.treeToValue(event, WithdrawalCompletedEvent.class)
                : JSON.treeToValue(event, WithdrawalRejectedEvent.class);
        return JSON.valueToTree(original);
    }

    private static void forwardToCore(HttpExchange exchange) throws java.io.IOException {
        try {
            if (CORE_UNAVAILABLE.get()) return;
            String token = WRONG_SCOPE.get() ? "Bearer " + webToken : exchange.getRequestHeaders().getFirst("Authorization");
            var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + corePort + exchange.getRequestURI()))
                    .timeout(Duration.ofSeconds(10)).header("Authorization", token);
            byte[] body = exchange.getRequestBody().readAllBytes();
            if (body.length > 0) builder.header("Content-Type", "application/json");
            builder.method(exchange.getRequestMethod(), HttpRequest.BodyPublishers.ofByteArray(body));
            var response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            ResponseDelay delay = "POST".equals(exchange.getRequestMethod()) ? DELAY.getAndSet(null) : null;
            if (delay != null && response.statusCode() == 200) {
                delay.committed().countDown();
                delay.release().await(8, TimeUnit.SECONDS);
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(response.statusCode(), response.body().length);
            exchange.getResponseBody().write(response.body());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (java.io.IOException ignored) {
            // El cliente cierra la conexion al perderse la respuesta HTTP de prueba.
        } finally {
            exchange.close();
        }
    }

    private static String token(String channel, String secret, String scope) throws Exception {
        String credential = Base64.getEncoder().encodeToString((channel + "-client:" + secret).getBytes(StandardCharsets.UTF_8));
        var response = HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + authPort + "/oauth2/token"))
                .timeout(Duration.ofSeconds(10)).header("Authorization", "Basic " + credential)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials&scope=" + scope)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return JSON.readTree(response.body()).path("access_token").asText();
    }

    private HttpResponse<String> call(String path, String token) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + corePort + path))
                .timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer " + token).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private WithdrawalRequestedEvent requested(String amount) {
        return new WithdrawalRequestedEvent(UUID.randomUUID(), UUID.randomUUID(), 101,
                new BigDecimal(amount), Instant.parse("2026-01-01T00:00:00Z"));
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private void sql(String sql) throws Exception {
        try (Connection connection = connection(); var statement = connection.createStatement()) { statement.execute(sql); }
    }

    private int count(String table) throws Exception {
        try (Connection connection = connection(); var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private BigDecimal balance(String table, String column) throws Exception {
        try (Connection connection = connection(); var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT " + column + " FROM " + table + " WHERE cuenta_id=101")) {
            rows.next();
            return rows.getBigDecimal(1);
        }
    }

    private void assertLegacyBalance() throws Exception {
        assertThat(balance("intereses_mensuales", "saldo_final")).isEqualByComparingTo("777777");
    }

    private void evidence(String message) {
        System.out.println("EFT05 " + message);
    }

    private record ResponseDelay(CountDownLatch committed, CountDownLatch release) {
    }
}
