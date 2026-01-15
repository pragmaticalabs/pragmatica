package org.pragmatica.jbct.slice.routing;
/**
 * Matches type names against glob-like patterns for error type discovery.
 * <p>
 * Supported pattern formats:
 * <ul>
 *   <li>{@code *Suffix} - matches types ending with "Suffix" (startsWith when reversed)</li>
 *   <li>{@code Prefix*} - matches types starting with "Prefix"</li>
 *   <li>{@code *Contains*} - matches types containing "Contains"</li>
 *   <li>{@code ExactMatch} - matches types with exact name</li>
 * </ul>
 * <p>
 * Pattern matching is case-sensitive.
 */
public final class ErrorTypeMatcher {
    private ErrorTypeMatcher() {}

    /**
     * Check if a type name matches the given pattern.
     *
     * @param typeName the simple or qualified type name to match
     * @param pattern  the glob-like pattern
     * @return true if the type name matches the pattern
     */
    public static boolean matches(String typeName, String pattern) {
        if (typeName == null || pattern == null) {
            return false;
        }
        if (pattern.isEmpty()) {
            return typeName.isEmpty();
        }
        var startsWithWildcard = pattern.startsWith("*");
        var endsWithWildcard = pattern.endsWith("*");
        if (startsWithWildcard && endsWithWildcard) {
            return matchContains(typeName, pattern);
        }
        if (startsWithWildcard) {
            return matchEndsWith(typeName, pattern);
        }
        if (endsWithWildcard) {
            return matchStartsWith(typeName, pattern);
        }
        return matchExact(typeName, pattern);
    }

    private static boolean matchContains(String typeName, String pattern) {
        // Pattern: *Contains* -> strip both wildcards
        var literal = pattern.substring(1, pattern.length() - 1);
        return typeName.contains(literal);
    }

    private static boolean matchEndsWith(String typeName, String pattern) {
        // Pattern: *Suffix -> strip leading wildcard
        var suffix = pattern.substring(1);
        return typeName.endsWith(suffix);
    }

    private static boolean matchStartsWith(String typeName, String pattern) {
        // Pattern: Prefix* -> strip trailing wildcard
        var prefix = pattern.substring(0, pattern.length() - 1);
        return typeName.startsWith(prefix);
    }

    private static boolean matchExact(String typeName, String pattern) {
        return typeName.equals(pattern);
    }

    /**
     * Extract the literal portion of a pattern (without wildcards).
     * Useful for diagnostics and debugging.
     *
     * @param pattern the glob-like pattern
     * @return the literal portion without wildcards
     */
    public static String extractLiteral(String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            return "";
        }
        var start = pattern.startsWith("*")
                    ? 1
                    : 0;
        var end = pattern.endsWith("*")
                  ? pattern.length() - 1
                  : pattern.length();
        return start < end
               ? pattern.substring(start, end)
               : "";
    }
}
