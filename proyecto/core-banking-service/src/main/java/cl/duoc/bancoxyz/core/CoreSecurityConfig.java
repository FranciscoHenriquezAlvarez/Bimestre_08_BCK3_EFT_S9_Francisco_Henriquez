package cl.duoc.bancoxyz.core;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

// Exige un Access Token OAuth2 para acceder a las APIs bancarias.
@Configuration
public class CoreSecurityConfig {
    @Bean
    SecurityFilterChain coreSecurity(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        // El prefijo de retiros se resuelve antes de las reglas generales de cuentas.
                        .requestMatchers(HttpMethod.GET, "/api/core/accounts/async-withdrawals/{transactionId}")
                        .hasAuthority("SCOPE_atm")
                        .requestMatchers("/api/core/accounts/async-withdrawals/**").denyAll()
                        .requestMatchers(HttpMethod.GET, "/api/core/accounts/{id}/annual-statement")
                        .hasAuthority("SCOPE_web")
                        .requestMatchers(HttpMethod.GET, "/api/core/accounts/{id}/movements/latest")
                        .hasAuthority("SCOPE_mobile")
                        .requestMatchers(HttpMethod.GET, "/api/core/accounts/{id}/balance")
                        .hasAuthority("SCOPE_atm")
                        .requestMatchers(HttpMethod.GET, "/api/core/accounts/{id}")
                        .hasAnyAuthority("SCOPE_web", "SCOPE_mobile")
                        .requestMatchers(HttpMethod.GET, "/api/config/origin")
                        .hasAnyAuthority("SCOPE_web", "SCOPE_mobile", "SCOPE_atm")
                        // Las operaciones administrativas conservan los permisos Web y Mobile.
                        .requestMatchers(HttpMethod.POST, "/api/core/accounts", "/api/core/accounts/{id}/close")
                        .hasAnyAuthority("SCOPE_web", "SCOPE_mobile")
                        .requestMatchers(HttpMethod.PUT, "/api/core/accounts/{id}")
                        .hasAnyAuthority("SCOPE_web", "SCOPE_mobile")
                        .requestMatchers(HttpMethod.POST, "/api/core/internal/operations/debit",
                                "/api/core/internal/operations/credit", "/api/core/internal/operations/transfer")
                        .hasAuthority("SCOPE_account.operations")
                        .requestMatchers(HttpMethod.GET, "/api/core/internal/operations/{operationId}")
                        .hasAuthority("SCOPE_account.operations")
                        .requestMatchers(HttpMethod.POST, "/api/core/internal/withdrawals")
                        .hasAuthority("SCOPE_account.operations")
                        .requestMatchers(HttpMethod.GET, "/api/core/internal/withdrawals/{transactionId}")
                        .hasAuthority("SCOPE_account.operations")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults())).build();
    }
}
