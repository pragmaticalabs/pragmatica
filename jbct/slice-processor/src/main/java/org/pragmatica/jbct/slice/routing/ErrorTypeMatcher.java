// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.jbct.slice.routing;

/// Matches type names against glob-like patterns for error type discovery.
///
/// Supported pattern formats:
///
///   - `*Suffix` - matches types ending with "Suffix" (startsWith when reversed)
///   - `Prefix*` - matches types starting with "Prefix"
///   - `*Contains*` - matches types containing "Contains"
///   - `ExactMatch` - matches types with exact name
///
///
/// A pattern that contains no `*` wildcard is an EXACT TYPE REFERENCE (#385, wishlist item 6):
/// [#matchesType] resolves it against the fully-qualified name by exact equality or dotted-suffix,
/// so `SeatNotFound`, `SeatError.SeatNotFound`, and `com.acme.seat.SeatError.SeatNotFound` all
/// resolve to the same nested Cause record. This is rename-proof and type-checked at compile time.
/// The glob forms keep matching the simple name unchanged (backward compatible).
///
/// Pattern matching is case-sensitive.
public final class ErrorTypeMatcher {
    private ErrorTypeMatcher() {}

    /// Check if a type name matches the given pattern.
    ///
    /// @param typeName the simple or qualified type name to match
    /// @param pattern  the glob-like pattern
    /// @return true if the type name matches the pattern
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

    /// Whether the pattern is an exact type reference (no `*` wildcard) rather than a glob.
    /// Exact references are type-checked at compile time and resolved by [#matchesType].
    ///
    /// @param pattern the mapping entry to classify
    /// @return true when the pattern contains no wildcard
    public static boolean isExactReference(String pattern) {
        return pattern != null && pattern.indexOf('*') < 0;
    }

    /// Check whether a Cause type matches the given mapping entry.
    ///
    /// A glob entry (contains `*`) is matched against the SIMPLE name, exactly as [#matches] —
    /// the backward-compatible behaviour. An exact type reference (no `*`) is resolved against the
    /// FULLY-QUALIFIED name by exact equality or dotted-suffix, so `SeatNotFound`,
    /// `SeatError.SeatNotFound`, and `com.acme.seat.SeatError.SeatNotFound` all resolve to the same
    /// nested Cause record.
    ///
    /// @param simpleName    the simple name of the Cause type
    /// @param qualifiedName the fully-qualified (dotted) name of the Cause type
    /// @param pattern       the glob or exact-reference mapping entry
    /// @return true if the type matches the entry
    public static boolean matchesType(String simpleName, String qualifiedName, String pattern) {
        if (pattern == null) {
            return false;
        }

        if (!isExactReference(pattern)) {
            return matches(simpleName, pattern);
        }

        if (qualifiedName == null || pattern.isEmpty()) {
            return false;
        }

        return qualifiedName.equals(pattern) || qualifiedName.endsWith("." + pattern);
    }

    /// Extract the literal portion of a pattern (without wildcards).
    /// Useful for diagnostics and debugging.
    ///
    /// @param pattern the glob-like pattern
    /// @return the literal portion without wildcards
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
