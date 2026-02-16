package org.pragmatica.aether.http.handler.security;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import java.util.regex.Pattern;

import static org.pragmatica.lang.Option.option;

/// API Key value object with validation.
///
/// Validates that API keys conform to expected format:
/// alphanumeric plus underscore/hyphen, 8-64 characters.
///
/// @param value the API key value
public record ApiKey(String value) {
    private static final Pattern VALID_KEY = Pattern.compile("^[a-zA-Z0-9_-]{8,64}$");

    /// Validation errors for ApiKey.
    public sealed interface ApiKeyError extends Cause {
        enum General implements ApiKeyError {
            NULL_VALUE("API key value cannot be null");
            private final String message;
            General(String message) {
                this.message = message;
            }
            @Override
            public String message() {
                return message;
            }
        }

        record InvalidFormat(String value) implements ApiKeyError {
            @SuppressWarnings("unused")
            public static Result<InvalidFormat> invalidFormat(Result<String> value) {
                return value.map(InvalidFormat::new);
            }

            @Override
            public String message() {
                return "Invalid API key format: must be 8-64 alphanumeric characters with _ or -";
            }
        }

        @SuppressWarnings("unused")
        record unused() implements ApiKeyError {
            @Override
            public String message() {
                return "";
            }
        }
    }

    /// Parse and validate API key.
    ///
    /// @param value raw key value
    /// @return Result containing valid ApiKey or failure
    public static Result<ApiKey> apiKey(String value) {
        return ensureNotNull(value).filter(ApiKey::invalidFormat,
                                           VALID_KEY.asMatchPredicate())
                            .map(ApiKey::new);
    }

    /// Check if a raw string is a valid API key format.
    public static boolean isValidFormat(String value) {
        return option(value).map(VALID_KEY.asMatchPredicate()::test)
                     .or(false);
    }

    private static Result<String> ensureNotNull(String value) {
        return Verify.ensure(value, Verify.Is::notNull, ApiKeyError.General.NULL_VALUE);
    }

    private static Cause invalidFormat(String val) {
        return new ApiKeyError.InvalidFormat(val);
    }
}
