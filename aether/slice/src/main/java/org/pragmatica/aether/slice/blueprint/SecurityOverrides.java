package org.pragmatica.aether.slice.blueprint;

import org.pragmatica.lang.Option;
import org.pragmatica.serialization.Codec;

import java.util.List;
import java.util.Map;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// Blueprint security overrides: a set of route pattern-to-security mappings
/// with an override policy controlling how they are applied.
///
/// @param entries  route pattern to security level mappings
/// @param policy   how overrides interact with original route security
@Codec
@SuppressWarnings({"JBCT-UTIL-02", "JBCT-VO-02"})
public record SecurityOverrides(List<Entry> entries, SecurityOverridePolicy policy) {
    /// A single security override entry mapping a route pattern to a security level.
    ///
    /// @param routePattern route pattern (e.g. "GET /api/v1/urls/*")
    /// @param securityLevel security level string (e.g. "authenticated")
    @Codec
    public record Entry(String routePattern, String securityLevel) {
        public static Entry entry(String routePattern, String securityLevel) {
            return new Entry(routePattern, securityLevel);
        }
    }

    /// Empty overrides with default STRENGTHEN_ONLY policy.
    public static final SecurityOverrides EMPTY = new SecurityOverrides(List.of(),
                                                                        SecurityOverridePolicy.STRENGTHEN_ONLY);

    /// Factory method.
    public static SecurityOverrides securityOverrides(List<Entry> entries, SecurityOverridePolicy policy) {
        return new SecurityOverrides(List.copyOf(entries), policy);
    }

    /// Parse from TOML section map entries and policy string.
    public static SecurityOverrides fromMap(Map<String, String> overrideMap, SecurityOverridePolicy policy) {
        var parsed = overrideMap.entrySet()
                                .stream()
                                .map(e -> Entry.entry(e.getKey(),
                                                      e.getValue()))
                                .toList();
        return securityOverrides(parsed, policy);
    }

    /// Find matching override security level string for a given HTTP method and path prefix.
    ///
    /// Matching rules:
    /// - Exact: "GET /api/v1/urls/" matches only that route
    /// - Wildcard suffix: "GET /api/v1/urls/*" matches any route starting with prefix
    /// - Method wildcard: "* /api/v1/urls/*" matches any method
    ///
    /// @return the security level string if a match is found (e.g. "authenticated", "role:admin")
    public Option<String> findMatch(String httpMethod, String pathPrefix) {
        for (var entry : entries) {
            if (matchesRoute(entry.routePattern(), httpMethod, pathPrefix)) {
                return some(entry.securityLevel());
            }
        }
        return none();
    }

    /// Check if there are any overrides defined.
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    private static boolean matchesRoute(String pattern, String httpMethod, String pathPrefix) {
        var parts = splitMethodAndPath(pattern);
        return matchesMethod(parts.method(), httpMethod) && matchesPath(parts.path(), pathPrefix);
    }

    private static boolean matchesMethod(String patternMethod, String httpMethod) {
        return "*".equals(patternMethod) || patternMethod.equalsIgnoreCase(httpMethod);
    }

    private static boolean matchesPath(String patternPath, String pathPrefix) {
        if (patternPath.endsWith("/*")) {
            var base = patternPath.substring(0, patternPath.length() - 1);
            return pathPrefix.startsWith(base);
        }
        return normalizePath(patternPath).equals(normalizePath(pathPrefix));
    }

    private static String normalizePath(String path) {
        var trimmed = path.strip();
        if (!trimmed.endsWith("/")) {
            return trimmed + "/";
        }
        return trimmed;
    }

    private record MethodAndPath(String method, String path) {}

    private static MethodAndPath splitMethodAndPath(String pattern) {
        var spaceIdx = pattern.indexOf(' ');
        if (spaceIdx > 0) {
            return new MethodAndPath(pattern.substring(0, spaceIdx)
                                            .strip(),
                                     pattern.substring(spaceIdx + 1)
                                            .strip());
        }
        return new MethodAndPath("*", pattern.strip());
    }
}
