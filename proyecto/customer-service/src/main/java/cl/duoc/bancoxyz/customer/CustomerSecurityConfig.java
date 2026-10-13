package cl.duoc.bancoxyz.customer;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class CustomerSecurityConfig {
    @Bean
    SecurityFilterChain customerSecurity(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/customers").hasAnyAuthority("SCOPE_web", "SCOPE_mobile")
                        .requestMatchers(HttpMethod.GET, "/api/customers/{id}", "/api/customers/{id}/profile")
                        .hasAnyAuthority("SCOPE_web", "SCOPE_mobile")
                        .requestMatchers(HttpMethod.PUT, "/api/customers/{id}", "/api/customers/{id}/profile")
                        .hasAnyAuthority("SCOPE_web", "SCOPE_mobile")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults())).build();
    }
}
