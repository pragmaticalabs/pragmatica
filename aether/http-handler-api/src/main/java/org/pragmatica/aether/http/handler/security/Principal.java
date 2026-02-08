package org.pragmatica.aether.http.handler.security;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

/**
 * Principal identity - who is making the request.
 * <p>
 * Value object with parse-don't-validate pattern.
 * Represents the authenticated entity making a request.
 *
 * @param value the principal identifier
 */
public record Principal(String value) {
    /// Validation errors for Principal.
    public sealed interface PrincipalError extends Cause {
        record NullValue() implements PrincipalError {
            @Override
            public String message() {
                return "Principal value cannot be null";
            }
        }

        record BlankValue() implements PrincipalError {
            @Override
            public String message() {
                return "Principal cannot be blank";
            }
        }
    }

    public static final Principal ANONYMOUS = new Principal("anonymous");

    /**
     * Create principal from raw value with validation.
     *
     * @param value the principal identifier
     * @return Result containing valid Principal or validation error
     */
    public static Result<Principal> principal(String value) {
        if (value == null) {
            return new PrincipalError.NullValue().result();
        }
        if (value.isBlank()) {
            return new PrincipalError.BlankValue().result();
        }
        return Result.success(new Principal(value));
    }

    /**
     * Create principal for API key authentication.
     *
     * @param keyName the API key name
     * @return Result containing valid Principal or validation error
     */
    public static Result<Principal> apiKeyPrincipal(String keyName) {
        if (keyName == null || keyName.isBlank()) {
            return new PrincipalError.BlankValue().result();
        }
        return Result.success(new Principal("api-key:" + keyName));
    }

    /**
     * Create principal for user authentication.
     *
     * @param userId the user ID
     * @return Result containing valid Principal or validation error
     */
    public static Result<Principal> userPrincipal(String userId) {
        if (userId == null || userId.isBlank()) {
            return new PrincipalError.BlankValue().result();
        }
        return Result.success(new Principal("user:" + userId));
    }

    /**
     * Create principal for service-to-service authentication.
     *
     * @param serviceName the service name
     * @return Result containing valid Principal or validation error
     */
    public static Result<Principal> servicePrincipal(String serviceName) {
        if (serviceName == null || serviceName.isBlank()) {
            return new PrincipalError.BlankValue().result();
        }
        return Result.success(new Principal("service:" + serviceName));
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
