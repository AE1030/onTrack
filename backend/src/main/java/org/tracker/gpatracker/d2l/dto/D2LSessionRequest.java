package org.tracker.gpatracker.d2l.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Credentials harvested by the device WebView after a successful Avenue (D2L) login.
 *
 * @param cookieHeader full {@code k=v; k=v} cookie jar (including HttpOnly session cookies read from
 *                     the native cookie store)
 * @param xsrfToken    Brightspace XSRF token read from page localStorage
 */
public record D2LSessionRequest(
        @NotBlank String cookieHeader,
        @NotBlank String xsrfToken
) {
}
