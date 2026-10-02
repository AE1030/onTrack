package org.tracker.gpatracker.security.configuration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.tracker.gpatracker.security.service.JWTService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class JwtFilterTest {

    private static final String SECRET = "dGVzdER1bW15Snd0U2VjcmV0S2V5Rm9yVW5pdFRlc3RzMTIzNA==";

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("an expired bearer token leaves the request unauthenticated instead of throwing")
    void expiredTokenDoesNotThrow() throws Exception {
        // Negative lifetime: the token is already expired when minted.
        JWTService expiring = new JWTService(SECRET, -1_000);
        String token = expiring.generateToken("a@mcmaster.ca", 1L, 2L);

        JwtFilter filter = new JwtFilter(expiring, mock(ApplicationContext.class));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/dashboard");
        request.setServletPath("/api/dashboard");
        request.addHeader("Authorization", "Bearer " + token);
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).as("the chain must continue so the entry point can answer 401").isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("a malformed bearer token leaves the request unauthenticated instead of throwing")
    void malformedTokenDoesNotThrow() throws Exception {
        JwtFilter filter = new JwtFilter(new JWTService(SECRET, 60_000), mock(ApplicationContext.class));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/dashboard");
        request.setServletPath("/api/dashboard");
        request.addHeader("Authorization", "Bearer not.a.jwt");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
