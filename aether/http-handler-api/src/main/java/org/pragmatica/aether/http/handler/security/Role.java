package org.pragmatica.aether.http.handler.security;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.serialization.Codec;

/// Role for authorization checks.
///
/// Value object ensuring valid role names.
/// Used in conjunction with {@link SecurityContext} for access control.
///
/// @param value the role name
@Codec
public record Role(String value) {
    /// Validation errors for Role.
    public sealed interface RoleError extends Cause {
        enum General implements RoleError {
            NULL_VALUE("Role value cannot be null"),
            BLANK_VALUE("Role cannot be blank");
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
        record unused() implements RoleError {
            @Override
            public String message() {
                return "";
            }
        }
    }

    /// Common role: administrator with full access.
    public static final Role ADMIN = role("admin").unwrap();

    /// Common role: regular authenticated user.
    public static final Role USER = role("user").unwrap();

    /// Common role: service-to-service communication.
    public static final Role SERVICE = role("service").unwrap();

    /// Create role from name with validation.
    ///
    /// @param value the role name
    /// @return Result containing valid Role or validation error
    public static Result<Role> role(String value) {
        return ensureNotBlank(value).map(Role::new);
    }

    private static Result<String> ensureNotBlank(String value) {
        return Verify.ensure(value, Verify.Is::notNull, RoleError.General.NULL_VALUE)
                     .filter(RoleError.General.BLANK_VALUE, Verify.Is::notBlank);
    }
}
