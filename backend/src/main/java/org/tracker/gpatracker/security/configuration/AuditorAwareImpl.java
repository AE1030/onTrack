package org.tracker.gpatracker.security.configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.tracker.gpatracker.security.model.UserPrincipal;

import java.util.Optional;

@Component
public class AuditorAwareImpl implements AuditorAware<Long> {

    @Override
    public Optional<Long> getCurrentAuditor() {

        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();

        // Case 1: No authentication (registration, system actions)
        if (authentication == null ||
                !authentication.isAuthenticated() ||
                authentication instanceof AnonymousAuthenticationToken) {
            return Optional.empty(); // or Optional.of(0L)
        }

        Object principal = authentication.getPrincipal();

        // Case 2: Authenticated via JWT
        if (principal instanceof UserPrincipal userPrincipal) {
            return Optional.of(userPrincipal.getId());
        }

        return Optional.empty();
    }
}
