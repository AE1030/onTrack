package org.tracker.gpatracker.configuration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;

/**
 * Logs how long this instance took to become ready, so cold starts can be measured on Cloud Run.
 *
 * <p>Do not remove, reword or demote this line. Three things depend on it staying exactly
 * {@code cold start: <n> ms} at INFO:
 * <ul>
 *   <li>the {@code cold_start_ms} log-based metric in Cloud Logging, which extracts the number
 *       with a regex on this text;</li>
 *   <li>the keep-warm decision in the hosting plan: how often this line appears against the
 *       Scheduler ping interval says whether min-instances needs to go up;</li>
 *   <li>{@code StartupTimeLoggerTest}, which fails the build if the format or level drifts.</li>
 * </ul>
 */
@Component
public class StartupTimeLogger {

    /** Public so the test and any log query can refer to the same text. */
    public static final String MESSAGE_PREFIX = "cold start: ";

    private static final Logger logger = LoggerFactory.getLogger(StartupTimeLogger.class);

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        // JVM uptime, so it includes class loading and context refresh, not just the portion
        // Spring chooses to report.
        logger.info(MESSAGE_PREFIX + "{} ms", ManagementFactory.getRuntimeMXBean().getUptime());
    }
}
