package com.kuberfashion.backend.config;

import com.kuberfashion.backend.security.JwtAuthenticationEntryPoint;
import com.kuberfashion.backend.security.JwtAuthenticationFilter;
import com.kuberfashion.backend.security.DbUserRoleEnricherFilter;
import com.kuberfashion.backend.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Central Spring Security configuration.
 *
 * <h3>Route security summary</h3>
 * <table border="1">
 *   <tr><th>Pattern</th><th>Required</th><th>Reason</th></tr>
 *   <tr><td>/api/auth/**</td><td>none</td><td>Login / registration</td></tr>
 *   <tr><td>/api/payments/webhook</td><td>none</td><td>PhonePe S2S callback (IP-based auth via SDK)</td></tr>
 *   <tr><td>/api/payments/**</td><td>authenticated</td><td>Customer-facing payment flow</td></tr>
 *   <tr><td>/api/admin/**</td><td>ROLE_ADMIN</td><td>Administrative operations</td></tr>
 * </table>
 *
 * <h3>CORS note</h3>
 * Combining {@code allowedOriginPatterns("*")} with {@code allowCredentials(true)} is
 * rejected by browsers (spec violation). This configuration uses an explicit list of allowed
 * origins instead so that credentialed requests from the frontend work correctly.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

    @Autowired
    private JwtAuthenticationEntryPoint unauthorizedHandler;

    @Autowired
    private UserService userService;

    @Autowired
    private DbUserRoleEnricherFilter dbUserRoleEnricherFilter;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Value("${supabase.jwks.uri:https://example.supabase.co/auth/v1/keys}")
    private String jwksUri;

    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter() {
        return new JwtAuthenticationFilter();
    }

    @Bean
    public UserDetailsService userDetailsService() {
        return userService;
    }

    @Bean
    public DaoAuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider();
        authProvider.setUserDetailsService(userDetailsService());
        authProvider.setPasswordEncoder(passwordEncoder);
        return authProvider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authConfig) throws Exception {
        return authConfig.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf.disable())
                .exceptionHandling(exception -> exception.authenticationEntryPoint(unauthorizedHandler))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authz -> authz
                        // ── Public endpoints ─────────────────────────────────
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/api/admin/auth/login").permitAll()
                        .requestMatchers("/api/test/**").permitAll()
                        .requestMatchers("/api/products/**").permitAll()
                        .requestMatchers("/api/categories/**").permitAll()
                        .requestMatchers("/api/health").permitAll()
                        .requestMatchers("/actuator/**").permitAll()

                        // ── PhonePe server-to-server webhook ─────────────────
                        // This route MUST be public so PhonePe's servers can POST to it.
                        // Authentication is performed INSIDE PaymentService.handleCallback()
                        // using the SDK's validateCallback() with the configured webhook
                        // credentials — no customer JWT is involved.
                        .requestMatchers("/api/payments/webhook").permitAll()

                        // ── Authenticated customer routes ─────────────────────
                        .requestMatchers("/api/payments/**").authenticated()
                        .requestMatchers("/api/cart/**").authenticated()
                        .requestMatchers("/api/wishlist/**").authenticated()
                        .requestMatchers("/api/orders/**").authenticated()
                        .requestMatchers("/api/users/profile/**").authenticated()
                        .requestMatchers("/api/users/change-password").authenticated()

                        // ── Admin-only routes ─────────────────────────────────
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")

                        .anyRequest().authenticated());

        http.authenticationProvider(authenticationProvider());
        // Use only custom JWT authentication filter
        http.addFilterBefore(jwtAuthenticationFilter(), BearerTokenAuthenticationFilter.class);

        return http.build();
    }

    /**
     * CORS configuration.
     *
     * <p><b>Do NOT use {@code allowedOriginPatterns("*")} together with
     * {@code allowCredentials(true)}</b>. The Fetch/XHR spec forbids this combination; browsers
     * will reject the preflight. Use an explicit list of origins instead.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        // Explicit origins — wildcard is removed because it's incompatible with allowCredentials
        configuration.setAllowedOrigins(List.of(
                "https://laxmi.kuberfashions.in",
                "https://laxmi-api.kuberfashions.in",
                "https://kuberfashions.in",
                "https://www.kuberfashions.in",
                "http://localhost:5173",
                "http://127.0.0.1:5173",
                "http://localhost:5174",
                "http://127.0.0.1:5174"));
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);
        configuration.setExposedHeaders(List.of("Authorization"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    /**
     * JWT decoder used by the OAuth2 resource server for Supabase token validation.
     *
     * <p>Note: The application's primary token mechanism is the custom
     * {@link com.kuberfashion.backend.security.JwtTokenProvider} (HMAC-SHA256).
     * This decoder is only used when Supabase JWKS validation is active.
     * When the JWKS URI is the placeholder value, a non-operational decoder is
     * wired in for dev compatibility. It is not used for payment flows.
     */
    @Bean
    public JwtDecoder jwtDecoder() {
        if (jwksUri == null || jwksUri.contains("example.supabase.co")) {
            // Dev fallback: wire Google's public JWKS — tokens signed by Google will
            // pass this decoder but our custom filter ignores them for API calls.
            // This avoids a startup failure when supabase.jwks.uri is unconfigured.
            return NimbusJwtDecoder.withJwkSetUri("https://www.googleapis.com/oauth2/v3/certs").build();
        }
        return NimbusJwtDecoder.withJwkSetUri(jwksUri).build();
    }
}
