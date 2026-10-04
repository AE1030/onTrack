package org.tracker.gpatracker.security.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which address the rate limiter counts a request against. Wrong here means either everyone shares
 * one bucket, or anyone can pick their own bucket by forging a header.
 */
class RateLimitFilterClientIpTest {

    private static final String SECRET = "test-origin-secret";

    private static MockHttpServletRequest request(String clientIp, String secret) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("10.0.0.1"); // the proxy
        if (clientIp != null) {
            req.addHeader(RateLimitFilter.CLIENT_IP_HEADER, clientIp);
        }
        if (secret != null) {
            req.addHeader(RateLimitFilter.ORIGIN_SECRET_HEADER, secret);
        }
        return req;
    }

    @Test
    void usesTheForwardedAddressWhenTheSecretMatches() {
        RateLimitFilter filter = new RateLimitFilter(SECRET);

        assertThat(filter.extractIp(request("203.0.113.7", SECRET))).isEqualTo("203.0.113.7");
    }

    @Test
    void usesTheForwardedAddressForIpv6() {
        RateLimitFilter filter = new RateLimitFilter(SECRET);

        assertThat(filter.extractIp(request("2606:4700::1", SECRET))).isEqualTo("2606:4700::1");
    }

    @Test
    void ignoresTheForwardedAddressWithoutTheSecret() {
        RateLimitFilter filter = new RateLimitFilter(SECRET);

        assertThat(filter.extractIp(request("203.0.113.7", null))).isEqualTo("10.0.0.1");
    }

    @Test
    void ignoresTheForwardedAddressWithTheWrongSecret() {
        RateLimitFilter filter = new RateLimitFilter(SECRET);

        assertThat(filter.extractIp(request("203.0.113.7", "guess"))).isEqualTo("10.0.0.1");
    }

    @Test
    void ignoresTheHeaderWhenNoSecretIsConfigured() {
        // A deploy that forgets ORIGIN_SHARED_SECRET must not trust a header anyone can send,
        // even if the caller sends an empty secret to match the empty configuration.
        RateLimitFilter filter = new RateLimitFilter("");

        assertThat(filter.extractIp(request("203.0.113.7", ""))).isEqualTo("10.0.0.1");
        assertThat(filter.extractIp(request("203.0.113.7", null))).isEqualTo("10.0.0.1");
    }

    @Test
    void fallsBackWhenTheForwardedAddressIsMissingOrMalformed() {
        RateLimitFilter filter = new RateLimitFilter(SECRET);

        assertThat(filter.extractIp(request(null, SECRET))).isEqualTo("10.0.0.1");
        assertThat(filter.extractIp(request("", SECRET))).isEqualTo("10.0.0.1");
        assertThat(filter.extractIp(request("not an address; drop table", SECRET))).isEqualTo("10.0.0.1");
        assertThat(filter.extractIp(request("1".repeat(200), SECRET))).isEqualTo("10.0.0.1");
    }
}
