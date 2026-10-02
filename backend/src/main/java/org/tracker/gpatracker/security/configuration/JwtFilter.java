package org.tracker.gpatracker.security.configuration;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.ApplicationContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.tracker.gpatracker.security.model.UserPrincipal;
import org.tracker.gpatracker.security.service.JWTService;
import org.tracker.gpatracker.security.service.MyUserDetailService;
import org.tracker.gpatracker.tenancy.UserContext;

import java.io.IOException;

@Component
public class JwtFilter extends OncePerRequestFilter {

    private final JWTService jwtService;
    private final ApplicationContext context;

    public JwtFilter(JWTService jwtService, ApplicationContext context) {
        this.jwtService = jwtService;
        this.context = context;
    }

    @Override

    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        //from client I will get this in the http header:
        // Bearer the_token_here
        String authHeader = request.getHeader("Authorization");
        String token = null;
        String username = null;
        if(authHeader != null && authHeader.startsWith("Bearer ")){
            token = authHeader.substring(7);
            try {
                username = jwtService.extractUserName(token);
            } catch (JwtException | IllegalArgumentException e) {
                // Expired, malformed or badly signed. Parsing throws rather than returning, and
                // letting that escape the filter chain surfaced as a 500. Leaving the request
                // unauthenticated lets the entry point answer 401, which is the client's cue to
                // call /refresh.
                logger.debug("Rejected bearer token: " + e.getMessage());
            }
        }

        /*This code checks if a valid JWT username exists and no user is currently authenticated.
        If so, it loads the user’s details from the database (MyUserDetailService).
        It then validates the JWT token against that user.
        iff the token is valid, it creates an authentication object
        (UsernamePasswordAuthenticationToken) and stores it in the SecurityContextHolder,
        effectively logging the user in for the current request.*/

        try {
            if (username != null && SecurityContextHolder.getContext().getAuthentication() == null){//check if the username is not empty and that the user is not already logged for this request (does this by checking the application context)


                UserDetails userDetails = loadUser(username);
                if (userDetails != null && jwtService.validateToken(token, userDetails)){
                    UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
                    authToken.setDetails(new WebAuthenticationDetailsSource() .buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authToken);

                    bindTenant(token, userDetails);
                }

            }
            //go on to the next filter in the security chain
            filterChain.doFilter(request, response);
        } finally {
            // Tomcat threads are pooled. Without this the next request served by this thread
            // would inherit the previous user's tenant.
            UserContext.clear();
        }

    }

    /** Null when the account behind a still-valid token has since been deleted. */
    private UserDetails loadUser(String username) {
        try {
            return context.getBean(MyUserDetailService.class).loadUserByUsername(username);
        } catch (UsernameNotFoundException e) {
            return null;
        }
    }

    private void bindTenant(String token, UserDetails userDetails) {
        Long userId = jwtService.extractUserId(token);
        Long studentId = jwtService.extractStudentId(token);

        if (userId == null && userDetails instanceof UserPrincipal principal) {
            userId = principal.getId();
        }

        UserContext.bind(userId, studentId);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return "OPTIONS".equalsIgnoreCase(request.getMethod())
                || "/login".equals(path)
                || "/refresh".equals(path)
                || "/logout".equals(path)
                || "/register".equals(path)
                || "/verify".equals(path)
                || "/verify/resend".equals(path)
                || "/reset-password".equals(path)
                || "/forgot-password".equals(path);
    }
}
