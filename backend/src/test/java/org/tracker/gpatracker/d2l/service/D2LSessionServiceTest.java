package org.tracker.gpatracker.d2l.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class D2LSessionServiceTest {

    @Test
    void mergeCookies_overwritesRefreshedTokenAndKeepsLongLivedCookie() {
        String existing = "d2lSessionVal=longlived; d2lSecureSessionVal=oldshort; xtra=keep";
        List<String> setCookies = List.of(
                "d2lSecureSessionVal=newshort; Path=/; HttpOnly; Secure; SameSite=None"
        );

        String merged = D2LSessionService.mergeCookies(existing, setCookies);

        // refreshed short-lived token replaced, long-lived session preserved, attributes stripped
        assertThat(merged).contains("d2lSecureSessionVal=newshort");
        assertThat(merged).contains("d2lSessionVal=longlived");
        assertThat(merged).contains("xtra=keep");
        assertThat(merged).doesNotContain("HttpOnly");
        assertThat(merged).doesNotContain("Path=/");
    }

    @Test
    void mergeCookies_addsBrandNewCookieFromSetCookie() {
        String merged = D2LSessionService.mergeCookies(
                "a=1",
                List.of("b=2; Path=/", "c=3"));

        assertThat(merged).isEqualTo("a=1; b=2; c=3");
    }

    @Test
    void mergeCookies_handlesNullSetCookieList() {
        String merged = D2LSessionService.mergeCookies("a=1; b=2", null);
        assertThat(merged).isEqualTo("a=1; b=2");
    }
}
