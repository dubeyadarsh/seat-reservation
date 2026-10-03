package com.seatbooking.config;

import com.seatbooking.observability.CorrelationIdFilter;
import com.seatbooking.security.JwtAuthenticationFilter;
import com.seatbooking.security.JwtService;
import com.seatbooking.security.Role;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Stateless bearer-token security: no sessions, no cookies, so CSRF protection is unnecessary.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties({AuthProperties.class, CorsProperties.class})
public class SecurityConfig {

    public static final String ADMIN_SECRET_HEADER = "X-Admin-Secret";

    private static final long HSTS_MAX_AGE_SECONDS = 31_536_000L;
    private static final long CORS_MAX_AGE_SECONDS = 3_600L;

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtService jwtService,
            CorsProperties corsProperties,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) throws Exception {
        // Security errors happen in filters, before any controller. Hand them to GlobalExceptionHandler
        // so 401/403 use the same JSON as every other error.
        AuthenticationEntryPoint authenticationEntryPoint =
                (request, response, ex) -> exceptionResolver.resolveException(request, response, null, ex);
        AccessDeniedHandler accessDeniedHandler =
                (request, response, ex) -> exceptionResolver.resolveException(request, response, null, ex);

        http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource(corsProperties)))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(HSTS_MAX_AGE_SECONDS)))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/auth/token").permitAll()
                        .requestMatchers(HttpMethod.GET, "/health/**", "/metrics").permitAll()
                        .requestMatchers(HttpMethod.POST, "/shows").hasRole(Role.ADMIN.name())
                        .requestMatchers(HttpMethod.GET, "/shows/*").permitAll()
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(
                        new JwtAuthenticationFilter(jwtService, authenticationEntryPoint),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private static CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(properties.allowedOrigins());
        config.setAllowedMethods(List.of(HttpMethod.GET.name(), HttpMethod.POST.name()));
        config.setAllowedHeaders(List.of(
                HttpHeaders.AUTHORIZATION,
                HttpHeaders.CONTENT_TYPE,
                CorrelationIdFilter.REQUEST_ID_HEADER,
                ADMIN_SECRET_HEADER));
        config.setExposedHeaders(List.of(CorrelationIdFilter.REQUEST_ID_HEADER));
        config.setAllowCredentials(false);
        config.setMaxAge(CORS_MAX_AGE_SECONDS);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
