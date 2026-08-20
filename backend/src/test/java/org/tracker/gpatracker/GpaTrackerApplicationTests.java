package org.tracker.gpatracker;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.tracker.gpatracker.support.ContainerIntegrationBase;

@SpringBootTest
@ActiveProfiles("test")
class GpaTrackerApplicationTests extends ContainerIntegrationBase {
    @Test
    void contextLoads() {
    }
}
