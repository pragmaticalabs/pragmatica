package org.pragmatica.jbct.slice.routing;

import javax.lang.model.element.TypeElement;

/**
 * Maps an error type implementing Cause to an HTTP status code.
 * <p>
 * Used during annotation processing to associate error types with
 * their corresponding HTTP response codes for router generation.
 *
 * @param errorType      the TypeElement representing the error type
 * @param httpStatus     the HTTP status code to return for this error
 * @param matchedPattern the glob pattern that matched this type (null if explicit mapping)
 */
public record ErrorTypeMapping(TypeElement errorType,
                               int httpStatus,
                               String matchedPattern) {
    /**
     * Factory method for explicit mapping (no pattern).
     */
    public static ErrorTypeMapping explicit(TypeElement errorType, int httpStatus) {
        return new ErrorTypeMapping(errorType, httpStatus, null);
    }

    /**
     * Factory method for pattern-matched mapping.
     */
    public static ErrorTypeMapping fromPattern(TypeElement errorType,
                                               int httpStatus,
                                               String matchedPattern) {
        return new ErrorTypeMapping(errorType, httpStatus, matchedPattern);
    }

    /**
     * Get the fully qualified name of the error type.
     */
    public String qualifiedName() {
        return errorType.getQualifiedName()
                        .toString();
    }

    /**
     * Get the simple name of the error type.
     */
    public String simpleName() {
        return errorType.getSimpleName()
                        .toString();
    }

    /**
     * Check if this mapping was from an explicit configuration (not pattern-matched).
     */
    public boolean isExplicit() {
        return matchedPattern == null;
    }
}
