package org.pragmatica.aether.http.handler.security;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

/// Role for authorization checks.
///
/// Value object ensuring valid role names.
/// Used in conjunction with {@link SecurityContext} for access control.
///
/// @param value the role name
public record Role(String value) {
    /// Validation errors for Role.
    public sealed interface RoleError extends Cause {
        record NullValue() implements RoleError {
            @Override
            public String message() {
                return "Role value cannot be null";
            }
        }

        record BlankValue() implements RoleError {
            @Override
            public String message() {
                return "Role cannot be blank";
            }
        }
    }

    /// Common role: administrator with full access.
    public static final Role ADMIN = new Role("admin");

    /// Common role: regular authenticated user.
    public static final Role USER = new Role("user");

    /// Common role: service-to-service communication.
    public static final Role SERVICE = new Role("service");

    /// Create role from name with validation.
    ///
    /// @param value the role name
    /// @return Result containing valid Role or validation error
    public static Result<Role> role(String value) {
        if (value == null) {
            return new RoleError.NullValue().result();
        }
        if (value.isBlank()) {
            return new RoleError.BlankValue().result();
        }
        return Result.success(new Role(value));
    }
}
