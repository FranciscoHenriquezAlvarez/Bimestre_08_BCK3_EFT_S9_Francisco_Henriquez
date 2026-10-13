package cl.duoc.bancoxyz.core;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.File;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;

final class AccountTestApplicationProcess implements AutoCloseable {
    private final Process process;
    private final Thread logReader;
    private final Path log;

    AccountTestApplicationProcess(String module, String main, String classpath, int port, Map<String, String> environment,
            List<String> properties, List<String> secrets) throws Exception {
        Path modulePath = Path.of("../" + module).toAbsolutePath().normalize();
        Path workingDirectory = Path.of("target/step07-apps/" + UUID.randomUUID()).toAbsolutePath();
        Files.createDirectories(workingDirectory);
        log = workingDirectory.resolve("application.log");
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-cp", classpath, main, "--server.port=" + port,
                "--spring.config.location=" + modulePath.resolve("src/main/resources/application.properties").toUri(),
                "--spring.cloud.config.enabled=false", "--eureka.client.enabled=false",
                "--debug=false", "--trace=false",
                "--logging.level.root=WARN", "--logging.level.org.springframework=WARN"));
        command.addAll(properties);
        ProcessBuilder builder = new ProcessBuilder(command).directory(workingDirectory.toFile()).redirectErrorStream(true);
        builder.environment().putAll(environment);
        process = builder.start();
        // Filtra la salida antes de escribirla, incluso si la aplicacion falla al iniciar.
        logReader = new Thread(() -> {
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                    var writer = Files.newBufferedWriter(log)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    for (String secret : secrets) {
                        line = line.replace(secret, "[CREDENCIAL OMITIDA]");
                    }
                    line = line.replaceAll("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+", "[JWT OMITIDO]")
                            .replaceAll("(?i)\\b(Basic|Bearer)\\s+[A-Za-z0-9._~+/=-]+", "$1 [TOKEN OMITIDO]");
                    writer.write(line);
                    writer.newLine();
                    writer.flush();
                }
            } catch (Exception error) {
                throw new IllegalStateException("No se pudo conservar la salida filtrada del proceso de prueba", error);
            }
        }, "step07-" + module + "-log");
        logReader.setDaemon(true);
        logReader.start();
    }

    void awaitHealth(int port) throws Exception {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        while (System.nanoTime() < deadline && process.isAlive()) {
            try {
                var response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health"))
                        .timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return;
                }
            } catch (java.io.IOException ignored) {
                // El puerto puede tardar en aceptar conexiones durante el inicio de Spring.
            }
            Thread.sleep(100);
        }
        throw new AssertionError("La aplicacion de prueba no inicio: " + Files.readString(log));
    }

    static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    static String moduleClasspath(String module, String testClass) throws Exception {
        Path report = Path.of("../" + module + "/target/surefire-reports/TEST-" + testClass + ".xml");
        if (!Files.isRegularFile(report)) {
            throw new AssertionError("Ejecute ./mvnw test desde proyecto/ para compilar y probar primero " + module);
        }
        // Reutiliza el classpath del reactor sin depender de los jars ejecutables de otros servicios.
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var properties = factory.newDocumentBuilder().parse(report.toFile()).getElementsByTagName("property");
        for (int index = 0; index < properties.getLength(); index++) {
            Element property = (Element) properties.item(index);
            if ("java.class.path".equals(property.getAttribute("name"))) {
                return productionClasses(property.getAttribute("value"));
            }
        }
        throw new AssertionError("El reporte Maven no contiene el classpath de " + module);
    }

    static String productionClasses(String classpath) {
        return String.join(File.pathSeparator, Arrays.stream(classpath.split(File.pathSeparator))
                .filter(path -> !path.endsWith("/target/test-classes")).toList());
    }

    @Override
    public void close() throws Exception {
        process.destroy();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
        logReader.join(2000);
    }
}
