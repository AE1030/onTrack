package org.tracker.gpatracker.mailing;

import com.resend.Resend;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "resend.api-key")
public class ResendConfig {

    @Bean
    public Resend resend(@Value("${resend.api-key}") String apiKey) {
        return new Resend(apiKey);
    }
}