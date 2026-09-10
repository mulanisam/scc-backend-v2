package com.app.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Opt-in Flyway repair, for databases whose history was written by a restore
 * rather than by Flyway itself.
 *
 * The dev and production schemas here were brought up by restoring a dump, which
 * left flyway_schema_history rows carrying no checksum at all. Flyway then refuses
 * to run anything further:
 *
 *   Migration checksum mismatch for migration version 3
 *   -> Applied to database : null
 *   -> Resolved locally    : 786286829
 *
 * repair() rewrites the recorded checksums to match the files on disk. It changes
 * no data and applies no migration; it only reconciles the history table.
 *
 * It is deliberately off unless asked for. Repairing on every start would mean an
 * edited migration is silently accepted, which is the exact protection
 * validate-on-migrate exists to give - a schema quietly diverging from the files
 * that are supposed to describe it. Run it once, per database:
 *
 *   ENV=dev ./mvnw spring-boot:run -Dspring-boot.run.arguments=--app.flyway.repair=true
 *
 * then start normally again.
 */
@Configuration
@ConditionalOnProperty(name = "app.flyway.repair", havingValue = "true")
public class FlywayRepairConfig {

    private static final Logger logger = LoggerFactory.getLogger(FlywayRepairConfig.class);

    @Bean
    public FlywayMigrationStrategy repairThenMigrate() {
        return flyway -> {
            logger.warn("app.flyway.repair=true: reconciling flyway_schema_history checksums "
                    + "against the migration files before migrating. Turn this off afterwards.");
            flyway.repair();
            flyway.migrate();
        };
    }
}
