package cl.duoc.bancoxyz.payment;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.endpoint.DefaultClientCredentialsTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

@Configuration
public class PaymentClientConfig {
    @Bean
    @LoadBalanced
    RestClient.Builder coreRestClientBuilder(@Value("${payment.http.connect-timeout-ms}") int connectTimeout,
            @Value("${payment.http.read-timeout-ms}") int readTimeout) {
        return RestClient.builder().requestFactory(requestFactory(connectTimeout, readTimeout));
    }

    @Bean
    OAuth2AuthorizedClientManager internalClientManager(ClientRegistrationRepository registrations,
            OAuth2AuthorizedClientService clients,
            @Value("${payment.http.connect-timeout-ms}") int connectTimeout,
            @Value("${payment.http.read-timeout-ms}") int readTimeout) {
        RestTemplate tokenHttp = new RestTemplate(List.of(new FormHttpMessageConverter(),
                new OAuth2AccessTokenResponseHttpMessageConverter()));
        tokenHttp.setRequestFactory(requestFactory(connectTimeout, readTimeout));
        tokenHttp.setErrorHandler(new OAuth2ErrorResponseErrorHandler());
        DefaultClientCredentialsTokenResponseClient tokenClient = new DefaultClientCredentialsTokenResponseClient();
        tokenClient.setRestOperations(tokenHttp);
        var provider = OAuth2AuthorizedClientProviderBuilder.builder()
                .clientCredentials(credentials -> credentials.accessTokenResponseClient(tokenClient)).build();
        var manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, clients);
        manager.setAuthorizedClientProvider(provider);
        return manager;
    }

    private SimpleClientHttpRequestFactory requestFactory(int connectTimeout, int readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return factory;
    }
}
