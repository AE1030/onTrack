package org.tracker.gpatracker.configuration;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.config.EnableMongoAuditing;

/**
 * Turns on {@code @CreatedDate}/{@code @LastModifiedDate} for Mongo documents.
 *
 * <p>Without this the annotations on {@code BaseDocument} are inert — Mongo auditing is not enabled
 * by the JPA {@code @EnableJpaAuditing} and has to be switched on separately.
 */
@Configuration
@EnableMongoAuditing
public class MongoAuditingConfig {
}
