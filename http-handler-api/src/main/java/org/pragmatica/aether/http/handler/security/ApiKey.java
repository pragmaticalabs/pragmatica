package org.pragmatica.aether.http.handler.security;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * API Key value object with validation.
 * <p>
 * Validates that API keys conform to expected format:
 * alphanumeric plus underscore/hyphen, 8-64 characters.
 *
 * @param value the API key value
 */
public record ApiKey(String value) {
    private static final Pattern VALID_KEY = Pattern.compile("^[a-zA-Z0-9_-]{8,64}$");

    /**
     * Canonical constructor with validation.
     */
    public ApiKey {
        Objects.requireNonNull(value, "api key value");
        if (!VALID_KEY.matcher(value)
                      .matches()) {
            throw new IllegalArgumentException("Invalid API key format: must be 8-64 alphanumeric characters with _ or -");
        }
    }

    /**
     * Parse and validate API key.
     *
     * @param value raw key value
     * @return Result containing valid ApiKey or failure
     */
    public static Result<ApiKey> apiKey(String value) {
        return Result.lift(Causes::fromThrowable, () -> new ApiKey(value));
    }

    /**
     * Check if a raw string is a valid API key format.
     */
    public static boolean isValidFormat(String value) {
        return value != null && VALID_KEY.matcher(value)
                                         .matches();
    }
}
