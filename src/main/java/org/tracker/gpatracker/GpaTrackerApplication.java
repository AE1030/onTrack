package org.tracker.gpatracker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@SpringBootApplication
@EnableAspectJAutoProxy
public class GpaTrackerApplication {

    public static void main(String[] args) {
        SpringApplication.run(GpaTrackerApplication.class, args);
    }

}
