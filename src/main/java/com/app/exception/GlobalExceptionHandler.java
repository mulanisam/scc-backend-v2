package com.app.exception;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Translates exceptions into consistent JSON responses.
 *
 * This class previously lived in a package outside com.app, so component
 * scanning never found it and the application effectively had no global
 * exception handling: each controller caught its own exceptions and returned a
 * bare 500. It now sits under com.app.exception and is actually registered.
 *
 * Two rules it follows that the original did not:
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
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Not found: safe to describe, since the message names a business entity. */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(
            ResourceNotFoundException ex, HttpServletRequest request) {

        logger.warn("Resource not found on {} {}: {}",
                request.getMethod(), request.getRequestURI(), ex.getMessage());

        return response(HttpStatus.NOT_FOUND, ex.getMessage(), request, null);
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

        return response(HttpStatus.BAD_REQUEST, ex.getMessage(), request, null);
    }

    /** Bean Validation failures, reported per field. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request) {

        Map<String, Object> fields = new LinkedHashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(error.getField(), error.getDefaultMessage());
        }

        logger.warn("Validation failed on {} {}: {}",
                request.getMethod(), request.getRequestURI(), fields);

        return response(HttpStatus.BAD_REQUEST, "Some fields are invalid.", request, fields);
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
                request);
        body.put("reference", reference);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    private ResponseEntity<Map<String, Object>> response(HttpStatus status, String message,
            HttpServletRequest request, Map<String, Object> fields) {

        Map<String, Object> body = baseBody(status, message, request);
        if (fields != null && !fields.isEmpty()) {
            body.put("fields", fields);
        }
        return ResponseEntity.status(status).body(body);
    }

    private Map<String, Object> baseBody(HttpStatus status, String message,
            HttpServletRequest request) {

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", OffsetDateTime.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        body.put("path", request.getRequestURI());
        return body;
    }
}
