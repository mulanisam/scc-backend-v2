package com.app.config;

import java.io.IOException;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Stamps every request with a short id, in the logs and in the response.
 *
 * The problem it solves is the one that makes production support guesswork. A 500 already
 * answered with "quote reference a6aa39bd", but that reference was invented in the
 * exception handler - so it appeared on exactly one log line, the error. Everything the
 * request did before failing was in the log with nothing tying it to the report, and with
 * two operators entering sales at once the interleaved lines could not be told apart.
 *
 * Now the same id is in the MDC for the whole request, printed on every line logged
 * during it, returned as X-Request-Id, and used as the reference in an error body. Given
 * "a6aa39bd" from a screenshot, `grep a6aa39bd` returns that request and nothing else.
 *
 * An inbound X-Request-Id is honoured, so a reverse proxy or the browser can supply one
 * and the same id spans the hop - but it is sanitised first, because it arrives from
 * outside and goes into log lines.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    /** Eight hex characters: short enough to read aloud, wide enough not to collide. */
    private static final int LENGTH = 8;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String id = sanitise(request.getHeader(HEADER));
        if (id == null) {
            id = UUID.randomUUID().toString().replace("-", "").substring(0, LENGTH);
        }

        MDC.put(MDC_KEY, id);
        response.setHeader(HEADER, id);
        try {
            chain.doFilter(request, response);
        } finally {
            // Always cleared. Tomcat reuses threads, so a leftover id would be stamped on
            // the next unrelated request and quietly point support at the wrong one.
            MDC.remove(MDC_KEY);
        }
    }

    /**
     * Accepts only short alphanumeric ids from outside.
     *
     * A header is caller-controlled and this value is written into log lines, so a
     * newline in it would let somebody forge log entries. Anything unexpected is dropped
     * and a fresh id generated rather than rejected - a malformed header should not fail
     * the request.
     */
    private static String sanitise(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String trimmed = header.trim();
        if (trimmed.length() > 64 || !trimmed.matches("[A-Za-z0-9._-]+")) {
            return null;
        }
        return trimmed;
    }

    /** The id for the request in flight, or "-" outside one. */
    public static String current() {
        String id = MDC.get(MDC_KEY);
        return id == null ? "-" : id;
    }
}
