package cl.duoc.bancoxyz.core;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class CustomerClientConfig {
    @Bean
    @LoadBalanced
    RestClient.Builder customerRestClientBuilder(
            @Value("${core.customer.connect-timeout-ms:2000}") int connectTimeout,
            @Value("${core.customer.read-timeout-ms:3000}") int readTimeout) {
        // No sigue redirecciones para evitar enviar el Bearer a otro destino.
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(connectTimeout))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofMillis(readTimeout));
        return RestClient.builder().requestFactory(factory);
    }
}
