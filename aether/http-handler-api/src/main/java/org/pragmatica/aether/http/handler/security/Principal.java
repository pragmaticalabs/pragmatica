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
@Codec public record Principal(String value) {
    public sealed interface PrincipalError extends Cause {
        enum General implements PrincipalError {
            NULL_VALUE("Principal value cannot be null"),
            BLANK_VALUE("Principal cannot be blank");
            private final String message;
            General(String message) {
                this.message = message;
            }
            @Override public String message() {
                return message;
            }
        }

        @SuppressWarnings("unused") record unused() implements PrincipalError {
            @Override public String message() {
                return "";
            }
        }
    }

    public static final Principal ANONYMOUS = principal("anonymous").unwrap();

    public static Result<Principal> principal(String value) {
        return ensureNotBlank(value).map(Principal::new);
    }

    public static Result<Principal> principal(String name, PrincipalType type) {
        return ensureNotBlank(name).map(type::prefixed).map(Principal::new);
    }

    public boolean isAnonymous() {
        return this.equals(ANONYMOUS);
    }

    public boolean isApiKey() {
        return value.startsWith("api-key:");
    }

    public boolean isUser() {
        return value.startsWith("user:");
    }

    public boolean isService() {
        return value.startsWith("service:");
    }

    private static Result<String> ensureNotBlank(String value) {
        return Verify.ensure(value, Verify.Is::notNull, PrincipalError.General.NULL_VALUE)
                            .filter(PrincipalError.General.BLANK_VALUE, Verify.Is::notBlank);
    }

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
