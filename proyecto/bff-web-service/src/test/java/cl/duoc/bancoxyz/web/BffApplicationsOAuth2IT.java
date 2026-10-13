package cl.duoc.bancoxyz.web;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

// Se ejecuta tras el reactor; todos los procesos, bases y broker pertenecen a esta prueba.
@Testcontainers
class BffApplicationsOAuth2IT {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private static final Map<String,String> SECRETS = Map.of("web",UUID.randomUUID().toString(),"mobile",UUID.randomUUID().toString(),
            "atm",UUID.randomUUID().toString(),"payment",UUID.randomUUID().toString());
    private static final Map<String,String> TOKENS = new HashMap<>();
    private static final Map<String,Integer> PORTS = new HashMap<>();
    private static final Map<String,BffTestApplicationProcess> APPS = new LinkedHashMap<>();
    private static final AtomicBoolean DROP_PAYMENT_RESPONSE = new AtomicBoolean();
    private static final AtomicInteger PAYMENT_COMMANDS = new AtomicInteger();
    private static final ExecutorService PROXY_THREADS = Executors.newCachedThreadPool();
    private static HttpServer paymentProxy;
    @Container
    static final PostgreSQLContainer<?> CORE_DB = database("eft_bff_core");
    @Container
    static final PostgreSQLContainer<?> CUSTOMER_DB = database("eft_bff_customer");
    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");

    @BeforeAll
    static void startOnlyIsolatedApplications() throws Exception {
        for(String app : List.of("auth","core","customer","payment","web","mobile","atm")) PORTS.put(app,BffTestApplicationProcess.freePort());
        Map<String,String> environment = new HashMap<>(jwtEnvironment());
        SECRETS.forEach((name,secret) -> environment.put("OAUTH2_"+name.toUpperCase(Locale.ROOT)+"_SECRET",secret));
        start("auth","authorization-server","cl.duoc.bancoxyz.authorization.AuthorizationServerApplication",
                "cl.duoc.bancoxyz.authorization.AuthorizationServerTest",environment,List.of());
        startCustomer();
        start("core","core-banking-service","cl.duoc.bancoxyz.BankBatchApplication","cl.duoc.bancoxyz.core.CoreSecurityTest",
                databaseEnvironment(CORE_DB),List.of("--bank.config-origin=Prueba BFF aislada","--spring.sql.init.mode=always",
                        "--spring.batch.job.enabled=false","--spring.kafka.admin.auto-create=false","--spring.kafka.listener.auto-startup=false",
                        "--spring.cloud.discovery.client.simple.instances.customer-service[0].uri="+url("customer")));
        startPayment();
        startPaymentProxy();
        for(String channel : List.of("web","mobile","atm")) {
            String title=channel.equals("web")?"Web":channel.equals("mobile")?"Mobile":"Atm";
            String report=channel.equals("web")?"WebSecurityTest":channel.equals("mobile")?"MobileSecurityTest":"AtmBffControllerTest";
            List<String> properties = new ArrayList<>(List.of(
                    "--spring.cloud.discovery.client.simple.instances.core-banking-service[0].uri="+url("core"),
                    "--spring.cloud.discovery.client.simple.instances.customer-service[0].uri="+url("customer"),
                    "--spring.cloud.discovery.client.simple.instances.payment-service[0].uri=http://localhost:"+paymentProxy.getAddress().getPort()));
            Map<String,String> env=new HashMap<>(jwtEnvironment());
            env.put("KAFKA_BOOTSTRAP_SERVERS",KAFKA.getBootstrapServers());
            start(channel,"bff-"+channel+"-service","cl.duoc.bancoxyz."+title+"BffApplication",
                    "cl.duoc.bancoxyz."+channel+"."+report,env,properties);
        }
        for(String client : SECRETS.keySet()) TOKENS.put(client,token(client));
    }

    @AfterAll
    static void stopOnlyOwnedApplications() throws Exception {
        List<BffTestApplicationProcess> processes = new ArrayList<>(APPS.values());
        Collections.reverse(processes);
        Exception failure=null;
        for(var process : processes) {
            try { process.close(); } catch(Exception error) { if(failure==null) failure=error; else failure.addSuppressed(error); }
        }
        if(paymentProxy!=null) paymentProxy.stop(0);
        PROXY_THREADS.shutdownNow();
        if(failure!=null) throw failure;
    }

    @Test
    void webIntegratesCustomerAdministrationAccountsAndAllMonetaryOperations() throws Exception {
        int customer=registerCustomer();
        assertThat(call("web","GET","/api/bff/web/customers/"+customer,null,"web").statusCode()).isEqualTo(200);
        var information=Map.of("firstName","Nombre actualizado","lastName","Prueba","email","actualizado-"+UUID.randomUUID()+"@example.test","phone","12345678");
        assertThat(call("web","PUT","/api/bff/web/customers/"+customer,information,"web").statusCode()).isEqualTo(200);
        assertThat(call("web","GET","/api/bff/web/customers/"+customer+"/profile",null,"web").statusCode()).isEqualTo(200);
        var profile=call("web","PUT","/api/bff/web/customers/"+customer+"/profile",Map.of("profile","PREFERENTE"),"web");
        assertThat(profile.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(profile.body()).path("profile").asText()).isEqualTo("PREFERENTE");
        int source=openAccount(customer);
        int target=openAccount(customer);
        var updated=call("web","PUT","/api/bff/web/accounts/"+source,Map.of("accountName","Nombre actualizado","accountType","ahorro","interestRate",0.02),"web");
        assertThat(updated.statusCode()).isEqualTo(200);
        UUID deposit=UUID.randomUUID();
        JsonNode original=money("deposits",Map.of("operationId",deposit.toString(),"accountId",source,"amount",100));
        assertThat(money("deposits",Map.of("operationId",deposit.toString(),"accountId",source,"amount",100))).isEqualTo(original);
        UUID payment=UUID.randomUUID();
        assertThat(money("",Map.of("operationId",payment.toString(),"accountId",source,"amount",10)).path("status").asText()).isEqualTo("COMPLETED");
        assertThat(money("transfers",Map.of("operationId",UUID.randomUUID().toString(),"sourceAccountId",source,"targetAccountId",target,"amount",20)).path("status").asText()).isEqualTo("COMPLETED");
        var result=call("web","GET","/api/bff/web/payments/operations/"+payment,null,"web");
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(result.body()).path("operationId").asText()).isEqualTo(payment.toString());
        var detail=call("web","GET","/api/bff/web/accounts/"+source,null,"web");
        assertThat(detail.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(detail.body()).path("finalBalance").decimalValue()).isEqualByComparingTo("70");
        assertThat(JSON.readTree(detail.body()).path("calculatedInterest").decimalValue()).isEqualByComparingTo("0");
        int empty=openAccount(customer);
        assertThat(call("web","POST","/api/bff/web/accounts/"+empty+"/close",null,"web").statusCode()).isEqualTo(200);
        assertThat(call("web","POST","/api/bff/web/accounts/"+empty+"/close",null,"web").statusCode()).isEqualTo(409);
        System.out.println("EFT08 HTTP/OAuth2 real Web: cinco funciones Clientes, apertura 201 con Location publico, mantenimiento/cierre, pago/deposito/transferencia/consulta e idempotencia sin cambiar UUID");
    }

    @Test
    void mobileUsesReducedDtosAndKeepsExistingAccountAndHistoryContracts() throws Exception {
        int customer=registerCustomer();
        int account=openAccount(customer);
        UUID operation=UUID.randomUUID();
        money("deposits",Map.of("operationId",operation.toString(),"accountId",account,"amount",25));
        JsonNode fullCustomer=JSON.readTree(call("web","GET","/api/bff/web/customers/"+customer,null,"web").body());
        var mobileCustomer=call("mobile","GET","/api/bff/mobile/customers/"+customer,null,"mobile");
        assertThat(mobileCustomer.statusCode()).isEqualTo(200);
        JsonNode reducedCustomer=JSON.readTree(mobileCustomer.body());
        assertThat(reducedCustomer.properties()).extracting(Map.Entry::getKey).containsExactlyInAnyOrder("customerId","name","profile");
        assertThat(fullCustomer.size()).isGreaterThan(reducedCustomer.size());
        JsonNode fullOperation=JSON.readTree(call("web","GET","/api/bff/web/payments/operations/"+operation,null,"web").body());
        var mobileResult=call("mobile","GET","/api/bff/mobile/payments/operations/"+operation,null,"mobile");
        assertThat(mobileResult.statusCode()).isEqualTo(200);
        JsonNode reducedOperation=JSON.readTree(mobileResult.body());
        assertThat(reducedOperation.properties()).extracting(Map.Entry::getKey).containsExactlyInAnyOrder("operationId","type","status","rejectionReason");
        assertThat(reducedOperation.path("status")).isEqualTo(fullOperation.path("status"));
        assertThat(fullOperation.size()).isGreaterThan(reducedOperation.size());
        JsonNode web=JSON.readTree(call("web","GET","/api/bff/web/accounts/"+account,null,"web").body());
        JsonNode mobile=JSON.readTree(call("mobile","GET","/api/bff/mobile/accounts/"+account,null,"mobile").body());
        assertThat(web.size()).isEqualTo(7);
        assertThat(mobile.size()).isEqualTo(4);
        assertThat(web.path("finalBalance")).isEqualTo(mobile.path("finalBalance"));
        assertThat(mobile.path("finalBalance").decimalValue()).isEqualByComparingTo("25");
        seedHistoricalMovement(account);
        var statement=call("web","GET","/api/bff/web/accounts/"+account+"/annual-statement",null,"web");
        assertThat(statement.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(statement.body()).path("movementCount").asInt()).isEqualTo(1);
        assertThat(JSON.readTree(statement.body()).path("netBalance").decimalValue()).isEqualByComparingTo("5");
        var movements=call("mobile","GET","/api/bff/mobile/accounts/"+account+"/transactions",null,"mobile");
        assertThat(movements.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(movements.body()).get(0).path("amount").decimalValue()).isEqualByComparingTo("5");
        System.out.println("EFT08 comparacion real: cliente Web 8 campos/Mobile 3; operacion Web 7/Mobile 4; cuenta Web 7/Mobile 4; historia y saldo Core conservados");
    }

    @Test
    void signedTokensEnforceChannelSeparationAndRejectMissingOrInvalidSignatures() throws Exception {
        int customer=registerCustomer();
        int account=openAccount(customer);
        for(String channel : List.of("mobile","atm","payment")) {
            assertThat(call("web","POST","/api/bff/web/accounts",opening(customer),channel).statusCode()).isEqualTo(403);
            assertThat(call("web","POST","/api/bff/web/customers",Map.of(),channel).statusCode()).isEqualTo(403);
            assertThat(call("web","POST","/api/bff/web/payments/deposits",Map.of(),channel).statusCode()).isEqualTo(403);
        }
        for(String channel : List.of("web","atm","payment")) {
            assertThat(call("mobile","GET","/api/bff/mobile/customers/"+customer,null,channel).statusCode()).isEqualTo(403);
            assertThat(call("mobile","GET","/api/bff/mobile/payments/operations/"+UUID.randomUUID(),null,channel).statusCode()).isEqualTo(403);
        }
        for(String channel : List.of("web","mobile","payment")) {
            assertThat(call("atm","GET","/api/bff/atm/accounts/"+account+"/balance",null,channel).statusCode()).isEqualTo(403);
            assertThat(call("atm","POST","/api/bff/atm/accounts/"+account+"/withdraw",Map.of("amount",1),channel).statusCode()).isEqualTo(403);
        }
        String web=TOKENS.get("web");
        int signature=web.lastIndexOf('.')+1;
        String tampered=web.substring(0,signature)+(web.charAt(signature)=='A'?'B':'A')+web.substring(signature+1);
        for(String invalid : List.of("","invalid-test-token",tampered)) {
            for(String channel : List.of("web","mobile","atm")) {
                String path=channel.equals("web")?"/api/bff/web/customers/"+customer:channel.equals("mobile")?"/api/bff/mobile/customers/"+customer:"/api/bff/atm/accounts/"+account+"/balance";
                assertThat(rawCall(channel,"GET",path,null,invalid).statusCode()).isEqualTo(401);
            }
        }
        for(String channel : List.of("web","mobile")) assertThat(call(channel,"POST","/api/core/internal/operations/credit",Map.of(),channel).statusCode()).isEqualTo(404);
        System.out.println("EFT08 JWT firmados: separacion Web/Mobile/ATM y cliente interno 403; ausencia/formato/firma alterada 401; contabilidad interna no expuesta por BFF");
    }

    @Test
    void customerOutageOpensIndependentCircuitsAndRecoversWithoutBlockingAccountsOrPayments() throws Exception {
        int customer=registerCustomer();
        int account=openAccount(customer);
        UUID deposit=UUID.randomUUID();
        money("deposits",Map.of("operationId",deposit.toString(),"accountId",account,"amount",10));
        APPS.remove("customer").close();
        try {
            for(int count=0;count<6;count++) assertThat(call("web","GET","/api/bff/web/customers/"+customer,null,"web").statusCode()).isEqualTo(503);
            assertThat(call("mobile","GET","/api/bff/mobile/customers/"+customer,null,"mobile").statusCode()).isEqualTo(503);
            assertThat(call("web","GET","/api/bff/web/accounts/"+account,null,"web").statusCode()).isEqualTo(200);
            assertThat(call("mobile","GET","/api/bff/mobile/payments/operations/"+deposit,null,"mobile").statusCode()).isEqualTo(200);
        } finally { startCustomer(); }
        await().pollInSameThread().pollInterval(Duration.ofMillis(100)).atMost(Duration.ofSeconds(10))
                .until(() -> call("web","GET","/api/bff/web/customers/"+customer,null,"web").statusCode()==200);
        assertThat(call("web","GET","/api/bff/web/customers/"+customer,null,"web").statusCode()).isEqualTo(200);
        for(int count=0;count<6;count++) assertThat(call("web","GET","/api/bff/web/customers/2147483647",null,"web").statusCode()).isEqualTo(404);
        assertThat(call("web","GET","/api/bff/web/customers/"+customer,null,"web").statusCode()).isEqualTo(200);
        for(int count=0;count<6;count++) assertThat(call("web","GET","/api/bff/web/accounts/2147483647",null,"web").statusCode()).isEqualTo(404);
        assertThat(call("web","GET","/api/bff/web/accounts/"+account,null,"web").statusCode()).isEqualTo(200);
        System.out.println("EFT08 resiliencia real: Clientes caido/circuito abierto 503; Cuentas y Pagos siguen 200; recuperacion y errores 404 no bloquean consultas validas");
    }

    @Test
    void lostPaymentResponseAfterCommitReturnsUnknownAndRecoversTheSameOperation() throws Exception {
        int account=openAccount(registerCustomer());
        UUID operation=UUID.randomUUID();
        int before=PAYMENT_COMMANDS.get();
        DROP_PAYMENT_RESPONSE.set(true);
        var lost=call("web","POST","/api/bff/web/payments/deposits",Map.of("operationId",operation.toString(),"accountId",account,"amount",30),"web");
        assertThat(lost.statusCode()).isEqualTo(503);
        assertThat(JSON.readTree(lost.body()).path("status").asText()).isEqualTo("UNKNOWN");
        assertThat(JSON.readTree(lost.body()).path("operationId").asText()).isEqualTo(operation.toString());
        assertThat(PAYMENT_COMMANDS.get()).isEqualTo(before+1);
        var recovered=call("mobile","GET","/api/bff/mobile/payments/operations/"+operation,null,"mobile");
        assertThat(recovered.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(recovered.body()).path("status").asText()).isEqualTo("COMPLETED");
        assertThat(money("deposits",Map.of("operationId",operation.toString(),"accountId",account,"amount",30)).path("status").asText()).isEqualTo("COMPLETED");
        JsonNode balance=JSON.readTree(call("web","GET","/api/bff/web/accounts/"+account,null,"web").body());
        assertThat(balance.path("finalBalance").decimalValue()).isEqualByComparingTo("30");
        System.out.println("EFT08 perdida de respuesta despues del commit: Web 503/UNKNOWN con mismo UUID, un solo envio; Mobile recupera COMPLETED, reenvio idempotente y saldo unico 30");
    }

    @Test
    void unchangedAtmPublishesPendingAndReadsNewOperationalWithdrawalResultsFromCore() throws Exception {
        int account=openAccount(registerCustomer());
        money("deposits",Map.of("operationId",UUID.randomUUID().toString(),"accountId",account,"amount",100000));
        var accepted=call("atm","POST","/api/bff/atm/accounts/"+account+"/withdraw",Map.of("amount",20000),"atm");
        assertThat(accepted.statusCode()).isEqualTo(202);
        JsonNode pending=JSON.readTree(accepted.body());
        assertThat(pending.path("status").asText()).isEqualTo("PENDING");
        String transaction=pending.path("transactionId").asText();
        JsonNode event=publishedWithdrawal(transaction);
        assertThat(event.path("accountId").asInt()).isEqualTo(account);
        assertThat(event.path("amount").decimalValue()).isEqualByComparingTo("20000");
        // Procesa el evento por el endpoint interno aislado; los listeners de retiros siguen apagados.
        var processed=call("core","POST","/api/core/internal/withdrawals",event,"payment");
        assertThat(processed.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(processed.body()).path("status").asText()).isEqualTo("COMPLETED");
        var result=call("atm","GET","/api/bff/atm/accounts/transactions/"+transaction,null,"atm");
        assertThat(result.statusCode()).isEqualTo(200);
        JsonNode completed=JSON.readTree(result.body());
        assertThat(completed.path("transactionId").asText()).isEqualTo(transaction);
        assertThat(completed.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(completed.path("finalBalance").decimalValue()).isEqualByComparingTo("80000");
        var balance=call("atm","GET","/api/bff/atm/accounts/"+account+"/balance",null,"atm");
        assertThat(balance.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(balance.body()).path("balance").decimalValue()).isEqualByComparingTo("80000");
        var invalid=call("atm","POST","/api/bff/atm/accounts/"+account+"/withdraw",Map.of("amount",20),"atm");
        assertThat(invalid.statusCode()).isEqualTo(202);
        String rejectedId=JSON.readTree(invalid.body()).path("transactionId").asText();
        JsonNode rejectedEvent=publishedWithdrawal(rejectedId);
        var rejected=call("core","POST","/api/core/internal/withdrawals",rejectedEvent,"payment");
        assertThat(rejected.statusCode()).isEqualTo(200);
        var rejectedQuery=call("atm","GET","/api/bff/atm/accounts/transactions/"+rejectedId,null,"atm");
        assertThat(rejectedQuery.statusCode()).isEqualTo(200);
        JsonNode rejectedBody=JSON.readTree(rejectedQuery.body());
        assertThat(rejectedBody.path("status").asText()).isEqualTo("REJECTED");
        assertThat(rejectedBody.path("rejectionReason").asText()).contains("multiplos de 10000");
        assertThat(JSON.readTree(call("atm","GET","/api/bff/atm/accounts/"+account+"/balance",null,"atm").body()).path("balance").decimalValue())
                .isEqualByComparingTo("80000");
        System.out.println("EFT08 ATM sin cambios: HTTP 202/PENDING y publicacion real en Kafka aislado; registro operacional Core consultado por ruta anterior, COMPLETED/REJECTED y saldo 80000; listeners apagados");
    }

    private static void startCustomer() throws Exception {
        start("customer","customer-service","cl.duoc.bancoxyz.customer.CustomerApplication","cl.duoc.bancoxyz.customer.CustomerServiceTest",
                databaseEnvironment(CUSTOMER_DB),List.of("--spring.sql.init.mode=always"));
    }

    private static void startPayment() throws Exception {
        var environment=new HashMap<>(jwtEnvironment());
        environment.put("OAUTH2_PAYMENT_SECRET",SECRETS.get("payment"));
        environment.put("OAUTH2_TOKEN_URI",url("auth")+"/oauth2/token");
        start("payment","payment-service","cl.duoc.bancoxyz.payment.PaymentApplication","cl.duoc.bancoxyz.payment.PaymentControllerTest",environment,
                List.of("--payment.kafka.withdrawals.enabled=false","--spring.kafka.admin.auto-create=false","--spring.kafka.listener.auto-startup=false",
                        "--spring.cloud.discovery.client.simple.instances.core-banking-service[0].uri="+url("core")));
    }

    private static void start(String key,String module,String main,String testClass,Map<String,String> environment,List<String> properties) throws Exception {
        List<String> secrets=new ArrayList<>(SECRETS.values());
        secrets.add(CORE_DB.getPassword()); secrets.add(CUSTOMER_DB.getPassword());
        var process=new BffTestApplicationProcess(module,main,BffTestApplicationProcess.moduleClasspath(module,testClass),PORTS.get(key),environment,properties,secrets);
        APPS.put(key,process);
        process.awaitHealth(PORTS.get(key));
    }

    private static Map<String,String> jwtEnvironment() {
        return Map.of("OAUTH2_ISSUER",url("auth"),"OAUTH2_JWK_SET_URI",url("auth")+"/oauth2/jwks");
    }

    private static Map<String,String> databaseEnvironment(PostgreSQLContainer<?> database) {
        Map<String,String> environment=new HashMap<>(jwtEnvironment());
        environment.put("SPRING_DATASOURCE_URL",database.getJdbcUrl());
        environment.put("SPRING_DATASOURCE_USERNAME",database.getUsername());
        environment.put("SPRING_DATASOURCE_PASSWORD",database.getPassword());
        return environment;
    }

    private static PostgreSQLContainer<?> database(String name) {
        return new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName(name).withUsername("eft_test").withPassword(UUID.randomUUID().toString());
    }

    private static String token(String client) throws Exception {
        String basic=Base64.getEncoder().encodeToString((client+"-client:"+SECRETS.get(client)).getBytes(StandardCharsets.UTF_8));
        String scope=client.equals("payment")?"account.operations":client;
        var response=HTTP.send(HttpRequest.newBuilder(URI.create(url("auth")+"/oauth2/token")).timeout(Duration.ofSeconds(10))
                .header("Authorization","Basic "+basic).header("Content-Type","application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials&scope="+scope)).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return JSON.readTree(response.body()).path("access_token").asText();
    }

    private static HttpResponse<String> call(String app,String method,String path,Object body,String channel) throws Exception {
        return rawCall(app,method,path,body,TOKENS.get(channel));
    }

    private static HttpResponse<String> rawCall(String app,String method,String path,Object body,String bearer) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(url(app)+path)).timeout(Duration.ofSeconds(10));
        if(!bearer.isEmpty()) builder.header("Authorization","Bearer "+bearer);
        if(body!=null) builder.header("Content-Type","application/json");
        builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        return HTTP.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }

    private static String url(String app) { return "http://localhost:"+PORTS.get(app); }

    private static int registerCustomer() throws Exception {
        var response=call("web","POST","/api/bff/web/customers",Map.of("firstName","Nombre","lastName","Prueba","email","cliente-"+UUID.randomUUID()+"@example.test","profile","ESTANDAR"),"web");
        assertThat(response.statusCode()).isEqualTo(201);
        int id=JSON.readTree(response.body()).path("customerId").asInt();
        assertThat(response.headers().firstValue("Location").orElseThrow()).isEqualTo("/api/bff/web/customers/"+id);
        return id;
    }

    private static Map<String,Object> opening(int customer) {
        return Map.of("customerId",customer,"accountName","Cuenta de prueba","accountType","ahorro","interestRate",0.01);
    }

    private static int openAccount(int customer) throws Exception {
        var response=call("web","POST","/api/bff/web/accounts",opening(customer),"web");
        assertThat(response.statusCode()).isEqualTo(201);
        int id=JSON.readTree(response.body()).path("accountId").asInt();
        assertThat(response.headers().firstValue("Location").orElseThrow()).isEqualTo("/api/bff/web/accounts/"+id);
        return id;
    }

    private static JsonNode money(String action,Object body) throws Exception {
        var response=call("web","POST","/api/bff/web/payments"+(action.isEmpty()?"":"/"+action),body,"web");
        assertThat(response.statusCode()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private static void seedHistoricalMovement(int account) throws Exception {
        try(var connection=DriverManager.getConnection(CORE_DB.getJdbcUrl(),CORE_DB.getUsername(),CORE_DB.getPassword());
                var statement=connection.prepareStatement("INSERT INTO movimientos_anuales (cuenta_id,fecha,transaccion,monto,descripcion) VALUES (?,CURRENT_DATE,'DEPOSITO',5,'Historia aislada BFF')")) {
            statement.setInt(1,account); statement.executeUpdate();
        }
    }

    private static JsonNode publishedWithdrawal(String transaction) throws Exception {
        try(var observer=new KafkaConsumer<String,String>(Map.of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG,"bff-observer-"+UUID.randomUUID(),ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,false,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,"earliest",ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,StringDeserializer.class))) {
            observer.subscribe(List.of("bank.withdrawal.requested"));
            long deadline=System.nanoTime()+Duration.ofSeconds(15).toNanos();
            while(System.nanoTime()<deadline) for(var record : observer.poll(Duration.ofMillis(200))) {
                JsonNode event=JSON.readTree(record.value());
                if(transaction.equals(event.path("transactionId").asText())) return event;
            }
        }
        throw new AssertionError("El retiro ATM no fue publicado en el broker aislado");
    }

    private static void startPaymentProxy() throws Exception {
        paymentProxy=HttpServer.create(new InetSocketAddress("localhost",0),0);
        paymentProxy.setExecutor(PROXY_THREADS);
        paymentProxy.createContext("/",exchange -> {
            boolean command=exchange.getRequestMethod().equals("POST");
            if(command) PAYMENT_COMMANDS.incrementAndGet();
            try {
                var request=HttpRequest.newBuilder(URI.create(url("payment")+exchange.getRequestURI())).timeout(Duration.ofSeconds(8));
                String bearer=exchange.getRequestHeaders().getFirst("Authorization");
                if(bearer!=null) request.header("Authorization",bearer);
                String contentType=exchange.getRequestHeaders().getFirst("Content-Type");
                if(contentType!=null) request.header("Content-Type",contentType);
                request.method(exchange.getRequestMethod(),HttpRequest.BodyPublishers.ofByteArray(exchange.getRequestBody().readAllBytes()));
                var response=HTTP.send(request.build(),HttpResponse.BodyHandlers.ofByteArray());
                if(command && response.statusCode()==200 && DROP_PAYMENT_RESPONSE.compareAndSet(true,false)) return;
                exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(response.statusCode(),response.body().length);
                exchange.getResponseBody().write(response.body());
            } catch(Exception error) {
                byte[] body="{}".getBytes(StandardCharsets.UTF_8);
                try { exchange.sendResponseHeaders(503,body.length); exchange.getResponseBody().write(body); } catch(java.io.IOException ignored) { }
            } finally { exchange.close(); }
        });
        paymentProxy.start();
    }
}
