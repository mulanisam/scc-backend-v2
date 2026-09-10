package com.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Guards the signing key's configuration.
 *
 * application.properties declares jwt.secret as ${JWT_SECRET:} - an empty default
 * rather than none - so that a missing key produces the message below instead of
 * an opaque "Could not resolve placeholder 'JWT_SECRET'". The empty default is
 * only safe as long as an empty value still refuses to start, which is what these
 * tests hold in place: there must be no path where the application comes up
 * signing tokens with a blank or short key.
 */
class JWTUtilsConfigurationTest {

    private static final long ONE_DAY = 86_400_000L;

    private static UserDetails user() {
        return new User("someone@example.com", "irrelevant", List.of());
    }

    @Test
    @DisplayName("a missing key refuses to start, and says what to set")
    void blankSecretIsRejected() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> new JWTUtils("", ONE_DAY));

        assertTrue(thrown.getMessage().contains("JWT_SECRET"),
                "the message has to name the variable to set, got: " + thrown.getMessage());

        assertThrows(IllegalStateException.class, () -> new JWTUtils("   ", ONE_DAY));
        assertThrows(IllegalStateException.class, () -> new JWTUtils(null, ONE_DAY));
    }

    @Test
    @DisplayName("a key too short for HMAC-SHA256 refuses to start")
    void shortSecretIsRejected() {
        String sixteenBytes = Base64.getEncoder().encodeToString(new byte[16]);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> new JWTUtils(sixteenBytes, ONE_DAY));

        assertTrue(thrown.getMessage().contains("32"),
                "the message has to state the minimum, got: " + thrown.getMessage());
    }

    @Test
    @DisplayName("a proper key signs a token that round-trips")
    void validSecretSignsAndVerifies() {
        byte[] keyBytes = new byte[32];
        for (int i = 0; i < keyBytes.length; i++) {
            keyBytes[i] = (byte) (i + 1);
        }

        JWTUtils jwtUtils = new JWTUtils(Base64.getEncoder().encodeToString(keyBytes), ONE_DAY);
        String token = jwtUtils.generateToken(user());

        assertNotNull(token);
        assertEquals("someone@example.com", jwtUtils.extractUsername(token));
        assertTrue(jwtUtils.isTokenValid(token, user()));
    }

    @Test
    @DisplayName("a key given as raw text is accepted when it is long enough")
    void rawTextSecretIsAccepted() {
        // decode() falls back to the raw bytes when the value is not Base64, so a
        // hand-written passphrase works provided it clears 32 bytes.
        JWTUtils jwtUtils = new JWTUtils("this-passphrase-is-long-enough-to-sign-with", ONE_DAY);
        assertTrue(jwtUtils.isTokenValid(jwtUtils.generateToken(user()), user()));
    }
}
