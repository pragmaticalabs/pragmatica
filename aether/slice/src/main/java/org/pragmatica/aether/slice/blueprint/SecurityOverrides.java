// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.blueprint;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.pragmatica.lang.Option;
import org.pragmatica.serialization.Codec;


@Codec
@SuppressWarnings("JBCT-UTIL-02")
public record SecurityOverrides(List<Entry> entries, SecurityOverridePolicy policy) {
    @Codec
    public record Entry(String routePattern, String securityLevel) {
        public static Entry entry(String routePattern, String securityLevel) {
            return new Entry(routePattern, securityLevel);
        }
    }

    public static final SecurityOverrides EMPTY = new SecurityOverrides(List.of(),
                                                                        SecurityOverridePolicy.STRENGTHEN_ONLY);

    public static SecurityOverrides securityOverrides(List<Entry> entries, SecurityOverridePolicy policy) {
        return new SecurityOverrides(List.copyOf(entries), policy);
    }

    public static SecurityOverrides fromMap(Map<String, String> overrideMap, SecurityOverridePolicy policy) {
        var parsed = overrideMap.entrySet().stream().map(e -> Entry.entry(e.getKey(), e.getValue())).toList();

        return securityOverrides(parsed, policy);
    }

    /// The MOST SPECIFIC matching entry wins, independent of list order (#1659): the longest path it names, then an
    /// exact path over a `/*` wildcard naming the same path, then a named method over `*`; list order breaks only a
    /// complete tie. First-listed-wins let a broad `GET /api/*` listed first shadow a stricter `GET /api/admin/*`,
    /// and the order of a TOML table carries no security meaning.
    public Option<String> findMatch(String httpMethod, String pathPrefix) {
        // `max` keeps the FIRST of equally specific entries (BinaryOperator.maxBy), so list order breaks only a tie.
        return Option.from(entries.stream()
                                  .filter(entry -> matchesRoute(entry.routePattern(),
                                                                httpMethod,
                                                                pathPrefix))
                                  .max(Comparator.comparingInt(entry -> specificity(entry.routePattern())))).map(Entry::securityLevel);
    }

    /// Path length dominates; at equal length an exact path outranks a wildcard, and a named method outranks `*`.
    private static int specificity(String pattern) {
        var parts = splitMethodAndPath(pattern);
        var wildcard = parts.path().endsWith("/*");
        var path = wildcard
                   ? parts.path().substring(0,
                                            parts.path().length() - 1)
                   : normalizePath(parts.path());
        var exactBonus = wildcard
                         ? 0
                         : 2;
        var methodBonus = "*".equals(parts.method())
                          ? 0
                          : 1;

        return path.length() * 4 + exactBonus + methodBonus;
    }

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
            return new MethodAndPath(pattern.substring(0, spaceIdx).strip(),
                                     pattern.substring(spaceIdx + 1).strip());
        }

        return new MethodAndPath("*", pattern.strip());
    }
}
