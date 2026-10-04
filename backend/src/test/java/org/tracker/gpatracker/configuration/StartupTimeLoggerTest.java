package org.tracker.gpatracker.configuration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.Properties;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the cold start log line. It feeds a Cloud Logging metric and the min-instances decision,
 * and nothing else would notice if it quietly stopped appearing.
 */
@ExtendWith(OutputCaptureExtension.class)
class StartupTimeLoggerTest {

    /** The same shape the cold_start_ms metric extracts with: "cold start: 1234 ms". */
    private static final Pattern LINE = Pattern.compile("cold start: (\\d+) ms");

    @Test
    void logsUptimeInTheFormatTheMetricParses(CapturedOutput output) {
        new StartupTimeLogger().onReady();

        assertThat(output.getOut()).containsPattern(LINE);
    }

    @Test
    void logsAtInfo(CapturedOutput output) {
        new StartupTimeLogger().onReady();

        assertThat(output.getOut()).contains("INFO").contains(StartupTimeLogger.MESSAGE_PREFIX);
    }

    @Test
    void levelIsPinnedSoRaisingThePackageLevelCannotHideIt() throws Exception {
        Properties props = new Properties();
        try (InputStream in = new ClassPathResource("application.properties").getInputStream()) {
            props.load(in);
        }

        assertThat(props.getProperty(
                "logging.level.org.tracker.gpatracker.configuration.StartupTimeLogger"))
                .isEqualTo("INFO");
    }
}
