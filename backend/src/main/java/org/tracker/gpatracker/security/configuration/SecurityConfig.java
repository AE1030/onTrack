package org.tracker.gpatracker.security.configuration;

import org.springframework.http.HttpStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final UserDetailsService userDetailsService;
    private final JwtFilter jwtFilter;
    private final RateLimitFilter rateLimitFilter;

    @org.springframework.beans.factory.annotation.Value("${CORS_ALLOWED_ORIGIN}")
    private String[] corsAllowedOrigins;

    @org.springframework.beans.factory.annotation.Value("${site.base.url.https}")
    private String baseUrl;

    public SecurityConfig(UserDetailsService userDetailsService, JwtFilter jwtFilter, RateLimitFilter rateLimitFilter) {
        this.userDetailsService = userDetailsService;
        this.jwtFilter = jwtFilter;
        this.rateLimitFilter = rateLimitFilter;
    }

  

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .cors(c -> c.configurationSource(corsConfigurationSource()))
                .csrf(customizer -> customizer.disable())
                .authorizeHttpRequests(request -> request
                        .requestMatchers("/login","/register", "/verify","/verify/resend",
                                "/forgot-password","/reset-password",
                                "/api/calendar/google/callback",
                                "/api/calendar/google/success",
                                "/error").permitAll()//These are the resources that don't need authentication
                        .anyRequest().authenticated()) //Any other request needs authentication
                //.formLogin(Customizer.withDefaults()) //this is designed for a stateful session;because our session is stateless this is why in the browser we are stuck in a loop. It also contains an html code that contains the login form.
                //.httpBasic(Customizer.withDefaults()) //This is designed for a stateless session like a REST API(postman) which relies on JWT tokens.
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(rateLimitFilter, JwtFilter.class)
                .build();

    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        // Configure allowed origins, methods, headers, etc.
        List<String> origins = new java.util.ArrayList<>(List.of(corsAllowedOrigins));
        origins.add(baseUrl); // backend URL for server-rendered forms
        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true); // If you need to send cookies/credentials

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration); // Apply this config to all paths
        return source;
    }

    @Bean
    public BCryptPasswordEncoder passwordencoder(){

        return new BCryptPasswordEncoder(12);
    }



    @Bean
    public AuthenticationProvider authenticationProvider(){
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordencoder());
        return provider;
    }

    //Handling authentication
    //the authentication configuration creates a ready to use authentication manager object
    //The authentication manager is a blueprint for the authentication process
    //config then calls the getAuthenticationManager method to returns an authentication object of type AuthenticationManager
    //follows the single responsibility principle the authentication manager is only responsible for authentication
    //the authentication configuaration is responsible for creating an authentication object
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
