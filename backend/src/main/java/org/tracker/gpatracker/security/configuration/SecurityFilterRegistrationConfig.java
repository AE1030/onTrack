package org.tracker.gpatracker.security.configuration;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Stops the security filters from running twice per request.
 *
 * <p>{@code JwtFilter} and {@code RateLimitFilter} are {@code @Component}s, so Boot auto-registers
 * them in the plain servlet chain; they are <em>also</em> added to the Spring Security chain in
 * {@link SecurityConfig}. Disabling the servlet-level registration leaves the security chain as
 * the single place they execute.
 *
 * <p>This was already costing correctness before any tenancy work: {@code RateLimitFilter}
 * decremented its bucket twice for every request, halving the effective limit.
 */
@Configuration
public class SecurityFilterRegistrationConfig {

    @Bean
    public FilterRegistrationBean<JwtFilter> disableJwtFilterAutoRegistration(JwtFilter filter) {
        FilterRegistrationBean<JwtFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<RateLimitFilter> disableRateLimitFilterAutoRegistration(RateLimitFilter filter) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
