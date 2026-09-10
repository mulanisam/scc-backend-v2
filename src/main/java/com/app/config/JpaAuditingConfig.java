package com.app.config;

import java.util.Optional;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Enables the createdBy / createdAt / updatedBy / updatedAt fields on
 * AuditableEntity.
 *
 * The auditor is read from the authenticated principal rather than from the
 * request body, so a client cannot claim to be someone else. Requests without
 * an authenticated user - the ledger backfill run from a scheduled job, for
 * instance - are attributed to "system" rather than left null.
 */
@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
public class JpaAuditingConfig {

    static final String SYSTEM_PRINCIPAL = "system";

    @Bean
    public AuditorAware<String> auditorAware() {
        return () -> {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

            if (authentication == null
                    || !authentication.isAuthenticated()
                    || "anonymousUser".equals(authentication.getName())) {
                return Optional.of(SYSTEM_PRINCIPAL);
            }

            return Optional.of(authentication.getName());
        };
    }
}
