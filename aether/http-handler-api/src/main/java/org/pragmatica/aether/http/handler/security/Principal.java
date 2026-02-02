package org.pragmatica.aether.http.handler.security;

import java.util.Objects;

/**
 * Principal identity - who is making the request.
 * <p>
 * Value object with parse-don't-validate pattern.
 * Represents the authenticated entity making a request.
 *
 * @param value the principal identifier
 */
public record Principal(String value) {
    public static final Principal ANONYMOUS = new Principal("anonymous");

    /**
     * Canonical constructor with validation.
     */
    public Principal {
        Objects.requireNonNull(value, "principal value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Principal cannot be blank");
        }
    }

    /**
     * Create principal from raw value.
     */
    public static Principal principal(String value) {
        return new Principal(value);
    }

    /**
     * Create principal for API key authentication.
     */
    public static Principal apiKeyPrincipal(String keyName) {
        return new Principal("api-key:" + keyName);
    }

    /**
     * Create principal for user authentication.
     */
    public static Principal userPrincipal(String userId) {
        return new Principal("user:" + userId);
    }

    /**
     * Create principal for service-to-service authentication.
     */
    public static Principal servicePrincipal(String serviceName) {
        return new Principal("service:" + serviceName);
    }

    /**
     * Check if this is an anonymous (unauthenticated) principal.
     */
    public boolean isAnonymous() {
        return this.equals(ANONYMOUS);
    }

    /**
     * Check if this principal represents an API key.
     */
    public boolean isApiKey() {
        return value.startsWith("api-key:");
    }

    /**
     * Check if this principal represents a user.
     */
    public boolean isUser() {
        return value.startsWith("user:");
    }

    /**
     * Check if this principal represents a service.
     */
    public boolean isService() {
        return value.startsWith("service:");
    }
}
