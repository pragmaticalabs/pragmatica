package org.pragmatica.jbct.slice.routing;

import javax.lang.model.element.TypeElement;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Represents an ambiguous pattern match where a single error type
 * matches multiple patterns with different HTTP status codes.
 * <p>
 * This is a configuration error that must be resolved by either:
 * <ul>
 *   <li>Using more specific patterns that don't overlap</li>
 *   <li>Adding an explicit mapping for the conflicting type</li>
 * </ul>
 *
 * @param errorType        the TypeElement with conflicting matches
 * @param matchingPatterns list of all patterns that matched with their status codes
 */
public record ErrorConflict(TypeElement errorType,
                            List<PatternMatch> matchingPatterns) {
    /**
     * A single pattern match with its associated HTTP status.
     *
     * @param pattern the glob pattern that matched
     * @param status  the HTTP status code associated with the pattern
     */
    public record PatternMatch(String pattern, int status) {
        @Override
        public String toString() {
            return "'" + pattern + "' -> " + status;
        }
    }

    /**
     * Factory method for creating a conflict.
     */
    public static ErrorConflict errorConflict(TypeElement errorType,
                                              List<PatternMatch> matchingPatterns) {
        return new ErrorConflict(errorType, matchingPatterns);
    }

    /**
     * Get the fully qualified name of the conflicting error type.
     */
    public String qualifiedName() {
        return errorType.getQualifiedName()
                        .toString();
    }

    /**
     * Get the simple name of the conflicting error type.
     */
    public String simpleName() {
        return errorType.getSimpleName()
                        .toString();
    }

    /**
     * Format an error message suitable for compiler diagnostics.
     * <p>
     * Example output:
     * <pre>
     * Ambiguous error mapping for 'UserNotFoundError':
     *   - '*NotFound*' -> 404
     *   - '*Error' -> 500
     * Use explicit mapping to resolve conflict.
     * </pre>
     */
    public String errorMessage() {
        var patterns = matchingPatterns.stream()
                                       .map(pm -> "  - " + pm)
                                       .collect(Collectors.joining("\n"));
        return "Ambiguous error mapping for '" + simpleName() + "':\n"
               + patterns + "\n"
               + "Use explicit mapping to resolve conflict.";
    }
}
