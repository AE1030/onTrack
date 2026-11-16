package org.tracker.gpatracker.configuration;

import org.apache.catalina.Context;
import org.apache.catalina.Host;
import org.apache.catalina.Lifecycle;
import org.apache.catalina.Valve;
import org.apache.catalina.valves.ErrorReportValve;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Configuration;

/**
 * Silences Tomcat's built-in error page.
 *
 * <p>A request Tomcat rejects before Spring sees it (an illegal character in the URL, a malformed
 * header) is answered by Tomcat's ErrorReportValve, which by default prints the exception, its
 * stack trace when there is one, and the Tomcat version. With the report and server info off it
 * answers with the status line only.
 *
 * <p>The host creates that valve itself when it starts, which is before any context starts, so it
 * is switched off from the context's before-start event rather than replaced.
 */
@Configuration
public class ErrorPageConfig implements WebServerFactoryCustomizer<TomcatServletWebServerFactory> {

    @Override
    public void customize(TomcatServletWebServerFactory factory) {
        factory.addContextLifecycleListeners(event -> {
            if (!Lifecycle.BEFORE_START_EVENT.equals(event.getType())) return;
            if (!(event.getLifecycle() instanceof Context context)) return;
            if (!(context.getParent() instanceof Host host)) return;
            for (Valve valve : host.getPipeline().getValves()) {
                if (valve instanceof ErrorReportValve report) {
                    report.setShowReport(false);
                    report.setShowServerInfo(false);
                }
            }
        });
    }
}
