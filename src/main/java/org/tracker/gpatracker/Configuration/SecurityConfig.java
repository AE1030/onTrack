package org.tracker.gpatracker.Configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.tracker.gpatracker.model.Users;

@Configuration
@EnableWebSecurity
public class SecurityConfig {
    // Security configuration details would go here

    @Autowired
    private UserDetailsService userDetailsService; //Injecting the user detail service interface

    @Autowired
    private JwtFilter jwtFilter;

  

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(customizer -> customizer.disable())
                .authorizeHttpRequests(request -> request
                        .requestMatchers("/login","/register","/upload").permitAll()//These are the resources that don't need authentication
                        .anyRequest().authenticated()) //Any other request needs authentication
                //.formLogin(Customizer.withDefaults()) //this is designed for a stateful session;because our session is stateless this is why in the browser we are stuck in a loop. It also contains an html code that contains the login form.
                .httpBasic(Customizer.withDefaults()) //This is designed for a stateless session like a REST API(postman) which relies on JWT tokens.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .build();

    }

    @Bean
    public BCryptPasswordEncoder passwordencoder(){

        return new BCryptPasswordEncoder(12);
    }



    @Bean
    public AuthenticationProvider authenticationProvider(){
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
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
