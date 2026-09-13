package com.app.exception;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.app.config.RequestIdFilter;

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

    /**
     * A uniqueness or foreign-key violation the database caught.
     *
     * 409, not 500: nothing is broken, the caller asked for something the data will not
     * allow - a duplicate mobile number, a city still holding customers. The driver's own
     * message is not returned, because it carries table names, constraint names and the
     * offending SQL; the constraint is translated into a sentence instead.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(
            DataIntegrityViolationException ex, HttpServletRequest request) {

        logger.warn("Constraint violation on {} {} [{}]: {}",
                request.getMethod(), request.getRequestURI(), reference(), rootMessage(ex));

        Map<String, Object> body = baseBody(HttpStatus.CONFLICT, describe(ex), request.getRequestURI());
        body.put("reference", reference());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /**
     * Authenticated, but not allowed to do this.
     *
     * Spring's own 403 page is HTML; a screen expecting JSON showed nothing at all and
     * looked like a hang. Deliberately does not say what the resource was.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleForbidden(
            AccessDeniedException ex, HttpServletRequest request) {

        logger.warn("Access denied on {} {} for {}",
                request.getMethod(), request.getRequestURI(),
                request.getUserPrincipal() == null ? "anonymous" : request.getUserPrincipal().getName());

        return response(HttpStatus.FORBIDDEN,
                "Your account does not have permission to do this.", request.getRequestURI(), null);
    }

    /** Bad credentials, or a token that will not verify. */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Map<String, Object>> handleUnauthorised(
            AuthenticationException ex, HttpServletRequest request) {

        // The reason is not returned: distinguishing "no such user" from "wrong password"
        // tells an attacker which usernames exist.
        logger.warn("Authentication failed on {}: {}", request.getRequestURI(), ex.getMessage());

        return response(HttpStatus.UNAUTHORIZED,
                "Those credentials were not accepted.", request.getRequestURI(), null);
    }

    /**
     * A parameter that is missing, or of the wrong type.
     *
     * Spring reports these as 400 already, but with text like "Failed to convert value of
     * type String to Long" which names a Java type rather than anything the caller
     * supplied. This names the parameter.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request) {

        String expected = ex.getRequiredType() == null ? "a different type"
                : friendlyType(ex.getRequiredType().getSimpleName());

        String message = String.format("\"%s\" is not valid for %s; expected %s.",
                ex.getValue(), ex.getName(), expected);

        logger.warn("Bad parameter on {} {}: {}", request.getMethod(), request.getRequestURI(), message);
        return response(HttpStatus.BAD_REQUEST, message, request.getRequestURI(), null);
    }

    /*
     * There is deliberately no handler for MaxUploadSizeExceededException.
     *
     * ResponseEntityExceptionHandler, which this class extends, already maps it - and
     * declaring a second one is not a duplicate that quietly wins, it stops the
     * application booting: "Ambiguous @ExceptionHandler method mapped for [class
     * MaxUploadSizeExceededException]". Found by starting the application rather than by
     * reading the code, which is the argument for starting it.
     */

    /**
     * The provider, the database or the filesystem did not answer.
     *
     * Separated from the catch-all so it is reported as 503 rather than 500: the
     * difference matters to whoever is looking at it, because a 503 is worth retrying and
     * a 500 is a defect.
     */
    @ExceptionHandler({ DataAccessResourceFailureException.class, IOException.class })
    public ResponseEntity<Map<String, Object>> handleUnavailable(
            Exception ex, HttpServletRequest request) {

        logger.error("Downstream failure [{}] on {} {}",
                reference(), request.getMethod(), request.getRequestURI(), ex);

        Map<String, Object> body = baseBody(HttpStatus.SERVICE_UNAVAILABLE,
                "A service this depends on did not respond. Try again in a moment."
                        + " Quote reference " + reference() + " if it keeps happening.",
                request.getRequestURI());
        body.put("reference", reference());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    /** Anything unexpected: logged in full, described to the caller only by reference. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(
            Exception ex, HttpServletRequest request) {

        String reference = reference();

        // The exception is the final argument, so the stack trace is logged.
        logger.error("Unhandled exception [{}] on {} {}",
                reference, request.getMethod(), request.getRequestURI(), ex);

        Map<String, Object> body = baseBody(HttpStatus.INTERNAL_SERVER_ERROR,
                "Something went wrong. Quote reference " + reference + " when reporting this.",
                request.getRequestURI());
        body.put("reference", reference);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    /**
     * The reference quoted to the caller: the request's own id.
     *
     * It used to be a fresh UUID minted here, which appeared on exactly one log line - the
     * error itself. Everything the request did before failing was in the log with nothing
     * connecting it to the report. Using the request id means "quote reference a6aa39bd"
     * and `grep a6aa39bd` return the same request from its first line to its last.
     */
    private static String reference() {
        String id = RequestIdFilter.current();
        return "-".equals(id) ? UUID.randomUUID().toString().substring(0, 8) : id;
    }

    /** Turns a constraint violation into something an operator can act on. */
    private static String describe(DataIntegrityViolationException ex) {
        String detail = rootMessage(ex).toLowerCase();

        if (detail.contains("foreign key")) {
            return "Something else still refers to this record, so it cannot be changed or removed"
                    + " while that is true.";
        }
        if (detail.contains("duplicate") || detail.contains("unique")) {
            return "That value is already recorded against another record.";
        }
        if (detail.contains("cannot be null")) {
            return "A required value is missing.";
        }
        return "The data would leave the records inconsistent, so it was not saved.";
    }

    private static String rootMessage(Throwable ex) {
        Throwable cause = ex;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? ex.toString() : cause.getMessage();
    }

    /** Java type names mean nothing to a caller; these do. */
    private static String friendlyType(String simpleName) {
        return switch (simpleName) {
            case "Long", "Integer", "Short" -> "a whole number";
            case "BigDecimal", "Double", "Float" -> "a number";
            case "LocalDate" -> "a date as yyyy-MM-dd";
            case "LocalDateTime" -> "a date and time";
            case "Boolean" -> "true or false";
            default -> "a " + simpleName;
        };
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
