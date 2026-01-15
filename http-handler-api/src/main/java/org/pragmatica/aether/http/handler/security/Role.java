package org.pragmatica.aether.http.handler.security;

import java.util.Objects;

/**
 * Role for authorization checks.
 * <p>
 * Value object ensuring valid role names.
 * Used in conjunction with {@link SecurityContext} for access control.
 *
 * @param value the role name
 */
public record Role(String value) {
    /**
     * Common role: administrator with full access.
     */
    public static final Role ADMIN = new Role("admin");

    /**
     * Common role: regular authenticated user.
     */
    public static final Role USER = new Role("user");

    /**
     * Common role: service-to-service communication.
     */
    public static final Role SERVICE = new Role("service");

    /**
     * Canonical constructor with validation.
     */
    public Role {
        Objects.requireNonNull(value, "role value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Role cannot be blank");
        }
    }

    /**
     * Create role from name.
     */
    public static Role role(String value) {
        return new Role(value);
    }
}
