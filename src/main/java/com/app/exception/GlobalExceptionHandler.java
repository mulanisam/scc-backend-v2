package com.app.exception;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Translates exceptions into consistent JSON responses.
 *
 * This class previously lived in a package outside com.app, so component
 * scanning never found it and the application effectively had no global
 * exception handling: each controller caught its own exceptions and returned a
 * bare 500. It now sits under com.app.exception and is actually registered.
 *
 * It extends ResponseEntityExceptionHandler so Spring's own MVC exceptions keep
 * the status they already carry. Without that, an unknown URL, a wrong HTTP verb
 * and an unreadable request body all fell through to the catch-all below and
 * were reported as 500 - a mistyped URL looked like a server fault and was
 * logged at ERROR with a stack trace.
 *
 * Two further rules the original did not follow:
 *
 *  - Stack traces are logged. The old code passed ex.getMessage() as the only
 *    argument, which logs one line and discards the trace, leaving production
 *    failures undiagnosable.
 *  - Internal detail is not returned to callers. The old code put
 *    ex.getMessage() into the response body, exposing SQL and driver errors.
 *    Unexpected failures now carry a short reference that also appears in the
 *    log, so "error a1b2c3d4" can be traced without leaking anything.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Not found: safe to describe, since the message names a business entity. */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(
            ResourceNotFoundException ex, HttpServletRequest request) {

        logger.warn("Resource not found on {} {}: {}",
                request.getMethod(), request.getRequestURI(), ex.getMessage());

        return response(HttpStatus.NOT_FOUND, ex.getMessage(), request.getRequestURI(), null);
    }

    /**
     * Business-rule rejections: an amount that disagrees with the server's own
     * calculation, a bird count that does not balance, an invalid role. These
     * messages are written for the operator and are meant to be shown.
     */
    @ExceptionHandler({ IllegalArgumentException.class, IllegalStateException.class })
    public ResponseEntity<Map<String, Object>> handleBadRequest(
            RuntimeException ex, HttpServletRequest request) {

        logger.warn("Rejected {} {}: {}",
                request.getMethod(), request.getRequestURI(), ex.getMessage());

        return response(HttpStatus.BAD_REQUEST, ex.getMessage(), request.getRequestURI(), null);
    }

    /**
     * Bean Validation failures, reported per field.
     *
     * Overrides the inherited handler so the response names the offending fields
     * rather than returning a bare 400.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {

        Map<String, Object> fields = new LinkedHashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(error.getField(), error.getDefaultMessage());
        }

        String path = path(request);
        logger.warn("Validation failed on {}: {}", path, fields);

        Map<String, Object> body = baseBody(HttpStatus.BAD_REQUEST, "Some fields are invalid.", path);
        body.put("fields", fields);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /** Anything unexpected: logged in full, described to the caller only by reference. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(
            Exception ex, HttpServletRequest request) {

        String reference = UUID.randomUUID().toString().substring(0, 8);

        // The exception is the final argument, so the stack trace is logged.
        logger.error("Unhandled exception [{}] on {} {}",
                reference, request.getMethod(), request.getRequestURI(), ex);

        Map<String, Object> body = baseBody(HttpStatus.INTERNAL_SERVER_ERROR,
                "Something went wrong. Quote reference " + reference + " when reporting this.",
                request.getRequestURI());
        body.put("reference", reference);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    private ResponseEntity<Map<String, Object>> response(HttpStatus status, String message,
            String path, Map<String, Object> fields) {

        Map<String, Object> body = baseBody(status, message, path);
        if (fields != null && !fields.isEmpty()) {
            body.put("fields", fields);
        }
        return ResponseEntity.status(status).body(body);
    }

    private Map<String, Object> baseBody(HttpStatus status, String message, String path) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", OffsetDateTime.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        body.put("path", path);
        return body;
    }

    private String path(WebRequest request) {
        if (request instanceof ServletWebRequest servletRequest) {
            return servletRequest.getRequest().getRequestURI();
        }
        return request.getDescription(false);
    }
}
