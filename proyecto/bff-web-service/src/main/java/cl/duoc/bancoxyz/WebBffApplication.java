package cl.duoc.bancoxyz;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.*;
import org.springframework.web.client.RestClient;

// Inicia el BFF del canal web y sus llamadas balanceadas.
@SpringBootApplication
public class WebBffApplication {
    public static void main(String[] args) {
        SpringApplication.run(WebBffApplication.class, args);
    }

    @Bean
    @LoadBalanced
    RestClient.Builder loadBalancedRestClient(
            @Value("${bff.http.connect-timeout-ms:2000}") int connectTimeout,
            @Value("${bff.http.read-timeout-ms:3000}") int readTimeout) {
        // Evita redirecciones del Bearer y limita la espera de los servicios remotos.
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(connectTimeout))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofMillis(readTimeout));
        return RestClient.builder().requestFactory(factory);
    }
}
