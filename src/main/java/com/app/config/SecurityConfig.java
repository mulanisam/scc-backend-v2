package com.app.config;

import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.app.service.OurUserDetailsService;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Authentication and authorization rules.
 *
 * Authorization is enforced here rather than relying on the React app to hide
 * screens. Hiding a route in the UI is not access control: every business
 * endpoint is reachable directly with any valid token.
 *
 * Roles:
 *   ADMIN  - full access, including trading, user management and migration
 *   USER   - office staff: sales, purchases, payments, ledger, reports, masters
 *   DRIVER - field staff: records sales on their route, reads the master data
 *            those entries reference, and nothing else
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private static final Logger logger = LoggerFactory.getLogger(SecurityConfig.class);

    private static final String ADMIN = "ADMIN";
    private static final String USER = "USER";
    private static final String DRIVER = "DRIVER";

    @Autowired
    private OurUserDetailsService ourUserDetailsService;

    @Autowired
    private JWTAuthFilter jwtAuthFilter;

    /** Comma-separated list of origins permitted to call this API. */
    @Value("${frontend.url:http://localhost:3000}")
    private String frontendUrl;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        List<String> origins = Arrays.stream(frontendUrl.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();

        CorsConfiguration configuration = new CorsConfiguration();
        // Explicit origins, never a wildcard: "*" lets any site on the
        // internet call this API with a signed-in user token.
        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        // The frontend authenticates with a bearer header, not cookies.
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);

        logger.info("CORS allowed origins: {}", origins);
        return source;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity httpSecurity) throws Exception {
        httpSecurity
                .csrf(AbstractHttpConfigurer::disable)
                // Previously commented out, which left this bean unused and the
                // wildcard MVC configuration in charge of CORS.
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .authorizeHttpRequests(request -> request
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                        // Creating users is an administrator action. This rule
                        // must precede the /auth/** rule below, which is open so
                        // that login works. Registration accepted a client-supplied
                        // role while sitting behind that open rule, so anyone who
                        // could reach the API could POST themselves an ADMIN
                        // account with no credentials at all.
                        .requestMatchers(HttpMethod.POST, "/auth/register").hasAuthority(ADMIN)

                        .requestMatchers("/auth/**", "/public/**").permitAll()

                        // Administration
                        .requestMatchers("/admin/**").hasAuthority(ADMIN)
                        .requestMatchers("/api/trading/**").hasAuthority(ADMIN)
                        .requestMatchers("/user/parties/**", "/user/partyVehicles/**").hasAuthority(ADMIN)

                        .requestMatchers("/adminuser/**").hasAnyAuthority(ADMIN, USER)

                        // Drivers record sales on their route and need the
                        // master data those entries reference.
                        .requestMatchers("/user/sales/**").hasAnyAuthority(ADMIN, USER, DRIVER)
                        .requestMatchers(HttpMethod.GET, "/user/routes/**", "/user/vehicles/**",
                                "/user/drivers/**", "/user/customers/byRoute/**")
                                .hasAnyAuthority(ADMIN, USER, DRIVER)

                        // Everything else under /user is office-staff work:
                        // purchases, payments, ledger, and master-data writes.
                        .requestMatchers("/user/**").hasAnyAuthority(ADMIN, USER)
                        .requestMatchers("/dashboard/**", "/reports/**").hasAnyAuthority(ADMIN, USER)

                        .anyRequest().authenticated()
                )
                .sessionManagement(manager -> manager.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authenticationProvider(authenticationProvider())
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint())
                        .accessDeniedHandler(accessDeniedHandler())
                );

        logger.info("Security configuration applied");
        return httpSecurity.build();
    }

    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider daoAuthenticationProvider = new DaoAuthenticationProvider();
        daoAuthenticationProvider.setUserDetailsService(ourUserDetailsService);
        daoAuthenticationProvider.setPasswordEncoder(passwordEncoder());
        return daoAuthenticationProvider;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authenticationConfiguration)
            throws Exception {
        return authenticationConfiguration.getAuthenticationManager();
    }

    @Bean
    public AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, authException) -> {
            logger.warn("Unauthenticated request to {} {}", request.getMethod(), request.getRequestURI());
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized");
        };
    }

    @Bean
    public AccessDeniedHandler accessDeniedHandler() {
        return (request, response, accessDeniedException) -> {
            logger.warn("Access denied for {} {}", request.getMethod(), request.getRequestURI());
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Access Denied");
        };
    }
}
