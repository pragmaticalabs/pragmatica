// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.jbct.slice.routing;

import org.pragmatica.lang.Option;

import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/// Pure compile-time validation rules for API versioning (#198 §10).
///
/// Mirrors [MediaTypeTypeChecker]: every check is side-effect free and returns
/// [Option#none()] when valid or [Option#some] with a Messager-ready error message when not, so
/// the rules can be unit-tested without a compile-fail harness. [SliceProcessor]/
/// [RouteSourceGenerator] call these and report any returned message via the Messager.
///
/// Rules:
///
///   - schema mixing: a slice may not declare BOTH a flat `[routes]` block AND any `[vN.routes]` block
///   - duplicate bind key: a bind key may appear at most once within one `[vN.routes]` block
///   - single default: at most one version across the slice may set `defaultIfMissing = true`
///   - sunset format: a `[vN] sunset` value must parse as an RFC3339/ISO date
///   - method resolution: every (vN, bindKey) must resolve to an existing slice method
///
public sealed interface VersionSchemaValidator {

    /// Validate that a slice does not mix the flat `[routes]` schema with versioned `[vN.routes]`
    /// schema (#198 §4).
    ///
    /// @param hasFlatRoutes whether a non-empty flat `[routes]` block is present
    /// @param versionCount  the number of `[vN.routes]` blocks present
    /// @return none when valid, some(errorMessage) when both schemas are present
    static Option<String> checkSchemaMixing(boolean hasFlatRoutes, int versionCount) {
        return hasFlatRoutes && versionCount > 0
               ? Option.some("Route configuration mixes a flat [routes] block with versioned [vN.routes] blocks. "
                             + "A slice must be either unversioned ([routes] + prefix) or versioned ([vN.routes] + [api] prefix), not both.")
               : Option.none();
    }

    /// Validate that no bind key is declared more than once within a single `[vN.routes]` block.
    ///
    /// @param version  the version number (for the error message)
    /// @param bindKeys the bind keys as declared (may contain duplicates)
    /// @return none when all keys are unique, some(errorMessage) naming the first duplicate
    static Option<String> checkDuplicateBindKey(int version, List<String> bindKeys) {
        var seen = new HashSet<String>();
        for (var key : bindKeys) {
            if (!seen.add(key)) {
                return Option.some("Version v" + version + " declares bind key '" + key
                                   + "' more than once in [v" + version + ".routes].");
            }
        }
        return Option.none();
    }

    /// Validate that at most one version sets `defaultIfMissing = true` (#198 §4).
    ///
    /// @param defaultVersions the version numbers that declared `defaultIfMissing = true`
    /// @return none when zero or one default, some(errorMessage) listing the conflicting versions
    static Option<String> checkSingleDefault(Set<Integer> defaultVersions) {
        return defaultVersions.size() > 1
               ? Option.some("Multiple versions declare defaultIfMissing = true: "
                             + defaultVersions.stream().sorted().map(v -> "v" + v).toList()
                             + ". At most one version may be the default.")
               : Option.none();
    }

    /// Validate that a `[vN] sunset` value parses as an RFC3339/ISO date (#198 §10).
    ///
    /// @param version    the version number (for the error message)
    /// @param sunsetDate the declared sunset value
    /// @return none when parseable, some(errorMessage) when the value is not an ISO date
    static Option<String> checkSunsetFormat(int version, String sunsetDate) {
        return isIsoDate(sunsetDate)
               ? Option.none()
               : Option.some("Version v" + version + " has an invalid 'sunset' value '" + sunsetDate
                             + "': expected an RFC3339/ISO date (e.g. 2026-12-31).");
    }

    /// Validate that a (vN, bindKey) pair resolves to an existing slice method (#198 §5, D8).
    ///
    /// @param version    the version number (for the error message)
    /// @param bindKey    the declared bind key
    /// @param methodName the resolved slice method name (`getV{N}` or an explicit override)
    /// @param methodResolved whether a slice method with that name exists
    /// @return none when the method exists, some(errorMessage) otherwise
    static Option<String> checkMethodResolved(int version,
                                              String bindKey,
                                              String methodName,
                                              boolean methodResolved) {
        return methodResolved
               ? Option.none()
               : Option.some("Version v" + version + " bind key '" + bindKey + "' resolves to slice method '"
                             + methodName + "', which does not exist. Add the method or specify method = \"...\".");
    }

    private static boolean isIsoDate(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            DateTimeFormatter.ISO_DATE.parse(value.trim());
            return true;
        } catch (DateTimeParseException _) {
            return false;
        }
    }

    record unused() implements VersionSchemaValidator {}
}
