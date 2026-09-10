package com.app.config;

import java.util.List;
import java.util.Set;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Requires ENV to name the environment, and fails loudly when it does not.
 *
 * application.properties used to carry {@code spring.profiles.active=${ENV:test}}.
 * That default is the kind of mistake that only shows up in the accounts: a
 * production start that forgot ENV would come up on the test profile, whose
 * application-test.properties points at poultry_db_test, and a day of trading
 * would be written to the test database without a single error being logged. The
 * first symptom would be a customer disputing a balance.
 *
 * Defaulting to prod instead does not fix it, it just moves the silent failure to
 * the worse side - a developer forgetting ENV would write test data into the live
 * books, which is harder to undo than recovering a day's entries from the wrong
 * schema. Neither default is safe, so there is none.
 *
 * This runs as an EnvironmentPostProcessor because it has to happen before the
 * datasource is built. A check inside a bean would run after Hibernate had already
 * connected to whichever database the wrong profile chose.
 *
 * An explicitly supplied profile still wins - {@code --spring.profiles.active=...}
 * on the command line, SPRING_PROFILES_ACTIVE, or {@code @ActiveProfiles} in a
 * test - so this only closes the case where nothing at all says which environment
 * is meant.
 */
public class RequiredProfileEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final Set<String> KNOWN_PROFILES = Set.of("dev", "test", "prod");

    /**
     * Ahead of ConfigDataEnvironmentPostProcessor, which is what loads
     * application-{profile}.properties.
     *
     * Without an order this runs last, and setting the active profile then is too
     * late for anything to be read from it: the profile showed as "test" in the
     * banner while application-test.properties had never been loaded, and startup
     * failed with "'url' attribute is not specified". The profile has to be decided
     * before config data is resolved, not after.
     */
    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER - 1;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String env = environment.getProperty("ENV");

        if (env != null && !env.isBlank()) {
            String profile = env.trim().toLowerCase();
            if (!KNOWN_PROFILES.contains(profile)) {
                throw new IllegalStateException(message("ENV is set to \"" + env + "\", which is not one of "
                        + KNOWN_PROFILES + "."));
            }
            environment.setActiveProfiles(profile);
            return;
        }

        // Nothing in ENV. Accept an explicit profile from anywhere else - a test
        // annotation, a command-line argument, SPRING_PROFILES_ACTIVE - because
        // those are somebody stating the environment deliberately.
        List<String> explicit = List.of(environment.getActiveProfiles());
        if (!explicit.isEmpty()) {
            return;
        }

        throw new IllegalStateException(message("ENV is not set."));
    }

    private String message(String problem) {
        return String.join(System.lineSeparator(),
                "",
                "***************************************************************",
                "The application will not start: " + problem,
                "",
                "ENV decides which database is opened, and there is deliberately",
                "no default - a wrong guess writes real trading into the test",
                "schema, or test data into the live accounts, with no error.",
                "",
                "Set one of:",
                "    ENV=prod    -> poultry_db      (live)",
                "    ENV=test    -> poultry_db_test",
                "    ENV=dev     -> poultry_db_dev",
                "",
                "PowerShell:   $env:ENV = \"prod\";  .\\mvnw.cmd spring-boot:run",
                "Bash:         ENV=prod ./mvnw spring-boot:run",
                "Jar:          java -jar backend.jar --spring.profiles.active=prod",
                "***************************************************************",
                "");
    }
}
