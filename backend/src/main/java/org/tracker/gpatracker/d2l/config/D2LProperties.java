package org.tracker.gpatracker.d2l.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the D2L Brightspace (McMaster Avenue to Learn) integration.
 *
 * <p>Defaults target McMaster's Avenue to Learn but every value is overridable so the
 * client can point at another Brightspace institution.
 */
@ConfigurationProperties(prefix = "d2l")
public class D2LProperties {

    /** Bare host of the Brightspace instance, e.g. {@code avenue.mcmaster.ca}. */
    private String host = "avenue.mcmaster.ca";

    /** Valence (Learning Platform) API version segment, e.g. {@code 1.45}. */
    private String apiVersion = "1.45";

    /** Desktop User-Agent forwarded on every request so Brightspace treats us as a browser. */
    private String userAgent =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    /**
     * Path replayed (carrying the long-lived session cookie) to mint a fresh short-lived auth
     * token without a browser. The exact endpoint must be confirmed against the live host during
     * prototyping; hitting an authenticated page that re-issues the token cookie is the fallback.
     */
    private String refreshPath = "/d2l/lp/auth/login/ProcessLoginActions.d2l";

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public String getApiVersion() {
        return apiVersion;
    }

    public void setApiVersion(String apiVersion) {
        this.apiVersion = apiVersion;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public String getRefreshPath() {
        return refreshPath;
    }

    public void setRefreshPath(String refreshPath) {
        this.refreshPath = refreshPath;
    }

    /** Convenience accessor for the {@code https://{host}} origin. */
    public String getBaseUrl() {
        return "https://" + host;
    }
}
