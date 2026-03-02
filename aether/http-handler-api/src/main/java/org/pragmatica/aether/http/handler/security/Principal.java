package org.pragmatica.aether.http.handler.security;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.serialization.Codec;

/// Principal identity - who is making the request.
///
/// Value object with parse-don't-validate pattern.
/// Represents the authenticated entity making a request.
///
/// @param value the principal identifier
@Codec
public record Principal(String value) {
    /// Validation errors for Principal.
    public sealed interface PrincipalError extends Cause {
        enum General implements PrincipalError {
            NULL_VALUE("Principal value cannot be null"),
            BLANK_VALUE("Principal cannot be blank");
            private final String message;
            General(String message) {
                this.message = message;
            }
            @Override
            public String message() {
                return message;
            }
        }

        @SuppressWarnings("unused")
        record unused() implements PrincipalError {
            @Override
            public String message() {
                return "";
            }
        }
    }

    public static final Principal ANONYMOUS = principal("anonymous").unwrap();

    /// Create principal from raw value with validation.
    ///
    /// @param value the principal identifier
    /// @return Result containing valid Principal or validation error
    public static Result<Principal> principal(String value) {
        return ensureNotBlank(value).map(Principal::new);
    }

    /// Create principal with type prefix.
    ///
    /// @param name the identifier
    /// @param type the principal type (determines prefix)
    /// @return Result containing valid Principal or validation error
    public static Result<Principal> principal(String name, PrincipalType type) {
        return ensureNotBlank(name).map(type::prefixed)
                             .map(Principal::new);
    }

    /// Check if this is an anonymous (unauthenticated) principal.
    public boolean isAnonymous() {
        return this.equals(ANONYMOUS);
    }

    /// Check if this principal represents an API key.
    public boolean isApiKey() {
        return value.startsWith("api-key:");
    }

    /// Check if this principal represents a user.
    public boolean isUser() {
        return value.startsWith("user:");
    }

    /// Check if this principal represents a service.
    public boolean isService() {
        return value.startsWith("service:");
    }

    private static Result<String> ensureNotBlank(String value) {
        return Verify.ensure(value, Verify.Is::notNull, PrincipalError.General.NULL_VALUE)
                     .filter(PrincipalError.General.BLANK_VALUE, Verify.Is::notBlank);
    }

    /// Principal type with associated prefix.
    public enum PrincipalType {
        API_KEY("api-key:"),
        USER("user:"),
        SERVICE("service:");
        private final String prefix;
        PrincipalType(String prefix) {
            this.prefix = prefix;
        }
        String prefixed(String name) {
            return prefix + name;
        }
    }
}
