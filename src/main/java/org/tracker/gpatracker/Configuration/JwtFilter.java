package org.tracker.gpatracker.Configuration;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.tracker.gpatracker.service.JWTService;
import org.tracker.gpatracker.service.MyUserDetailService;

import java.io.IOException;

@Component
public class JwtFilter extends OncePerRequestFilter {

    @Autowired
    JWTService jwtService;
    @Autowired
    ApplicationContext context;
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        //from client I will get this in the http header:
        // Bearer the_token_here
        String authHeader = request.getHeader("Authorization");
        String token = null;
        String username = null;
        if(authHeader != null && authHeader.startsWith("Bearer ")){
            token = authHeader.substring(7);
            username = jwtService.extractUserName(token);
        }
        if (username != null && SecurityContextHolder.getContext().getAuthentication() == null){//check if the username is not empty and that the user has been authenticated


            UserDetails userDetails = context.getBean(MyUserDetailService.class).loadUserByUsername(username); //use application context to get the bean to avoid creating been in the application context and outside in the jvm
            if (jwtService.validateToken(token, userDetails)){
                UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
                authToken.setDetails(new WebAuthenticationDetailsSource() .buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authToken);
            }
            //now that we have validated our token move on to the next filter
            filterChain.doFilter(request, response);
        }
    }
}
