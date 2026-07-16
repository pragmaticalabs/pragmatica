// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.jbct.slice.routing;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;


/// Pure compile-time totality + dead-mapping check for a slice's error->HTTP configuration (#385).
///
/// Mirrors [VersionSchemaValidator]: every check is side-effect free and returns a list of
/// Messager-ready [Issue]s, so the rules can be unit-tested without a compile-fail harness.
/// [ErrorTypeDiscovery] builds the [CauseDescriptor]s from the slice's sealed `Cause` hierarchy and
/// calls this; [SliceProcessor] reports each [Issue] via the Messager, choosing the severity.
///
/// The three checks:
///
///   - TOTALITY - every concrete (leaf) Cause type must resolve to a status; a leaf matched by
///     no glob, exact reference, or explicit mapping would silently fall through to the `default`
///     status (HTTP 500) and yields an [IssueKind#UNMAPPED_CAUSE].
///   - DEAD GLOB - a glob pattern (`*NotFound*`) that matches no Cause type in the slice yields an
///     [IssueKind#DEAD_PATTERN] (a stale pattern that silently matches nothing).
///   - DEAD REFERENCE - an exact type reference (`SeatError.SeatNotFound`) or an `[errors.explicit]`
///     entry that resolves to no Cause type yields an [IssueKind#DEAD_REFERENCE] (a renamed or
///     removed Cause breaks its reference here).
///
/// Severity is decided by the caller: [IssueKind#UNMAPPED_CAUSE] fails the build only when strict
/// mode is on (`[errors] strict = true` or `-Ajbct.routes.errors.strict=true`); everything else is
/// a warning by default so existing slices with unmapped causes keep building.
public sealed interface ErrorMappingValidator {
    /// A concrete or abstract Cause type discovered in the routed slice's hierarchy.
    ///
    /// @param simpleName    the simple name (e.g. `SeatNotFound`)
    /// @param qualifiedName the fully-qualified dotted name (e.g. `com.acme.seat.SeatError.SeatNotFound`)
    /// @param leaf          whether the type is a concrete leaf (record / enum / non-abstract class)
    ///                      that can actually be returned, and therefore must be mapped; the sealed
    ///                      interface and abstract classes are not leaves
    record CauseDescriptor(String simpleName, String qualifiedName, boolean leaf) {}

    /// The category of a mapping problem, which selects the diagnostic severity in [SliceProcessor].
    enum IssueKind {
        /// A concrete Cause leaf has no HTTP status mapping (totality violation).
        UNMAPPED_CAUSE,
        /// A glob pattern matches no Cause type in the slice.
        DEAD_PATTERN,
        /// An exact type reference or explicit mapping resolves to no Cause type in the slice.
        DEAD_REFERENCE
    }

    /// A single mapping problem with a Messager-ready message.
    ///
    /// @param kind    the category (selects severity)
    /// @param message the full diagnostic message, naming the type/pattern and the `routes.toml`
    record Issue(IssueKind kind, String message) {}

    /// Validate a slice's Cause hierarchy against its error-pattern configuration.
    ///
    /// @param causes        every Cause type discovered in the routed slice's package
    /// @param config        the parsed `[errors]` configuration
    /// @param routesTomlPath the resource path of the `routes.toml` to name in messages
    /// @return the mapping problems, in a stable order (unmapped, then dead globs/refs, then dead
    ///         explicit mappings); empty when the configuration is total and has no dead entries
    static List<Issue> validate(List<CauseDescriptor> causes, ErrorPatternConfig config, String routesTomlPath) {
        var issues = new ArrayList<Issue>();

        collectUnmappedCauses(causes, config, routesTomlPath, issues);
        collectDeadStatusEntries(causes, config, routesTomlPath, issues);
        collectDeadExplicitMappings(causes, config, routesTomlPath, issues);

        return List.copyOf(issues);
    }

    private static void collectUnmappedCauses(List<CauseDescriptor> causes,
                                              ErrorPatternConfig config,
                                              String routesTomlPath,
                                              List<Issue> issues) {
        for (var cause : causes) {
            if (cause.leaf() && !isMapped(cause, config)) {
                issues.add(new Issue(IssueKind.UNMAPPED_CAUSE,
                                     unmappedMessage(cause, config.defaultStatus(), routesTomlPath)));
            }
        }
    }

    private static boolean isMapped(CauseDescriptor cause, ErrorPatternConfig config) {
        return config.explicitMappings()
                     .containsKey(cause.simpleName()) || matchesAnyPattern(cause, config);
    }

    private static boolean matchesAnyPattern(CauseDescriptor cause, ErrorPatternConfig config) {
        for (var patterns : config.statusPatterns().values()) {
            for (var pattern : patterns) {
                if (ErrorTypeMatcher.matchesType(cause.simpleName(), cause.qualifiedName(), pattern)) {
                    return true;
                }
            }
        }

        return false;
    }

    private static void collectDeadStatusEntries(List<CauseDescriptor> causes,
                                                 ErrorPatternConfig config,
                                                 String routesTomlPath,
                                                 List<Issue> issues) {
        config.statusPatterns()
              .entrySet()
              .stream()
              .sorted(Map.Entry.comparingByKey())
              .forEach(entry -> collectDeadEntriesForStatus(entry.getKey(),
                                                            entry.getValue(),
                                                            causes,
                                                            routesTomlPath,
                                                            issues));
    }

    private static void collectDeadEntriesForStatus(int status,
                                                    List<String> patterns,
                                                    List<CauseDescriptor> causes,
                                                    String routesTomlPath,
                                                    List<Issue> issues) {
        for (var pattern : patterns) {
            if (!anyCauseMatches(pattern, causes)) {
                issues.add(deadEntryIssue(pattern, status, routesTomlPath));
            }
        }
    }

    private static boolean anyCauseMatches(String pattern, List<CauseDescriptor> causes) {
        for (var cause : causes) {
            if (ErrorTypeMatcher.matchesType(cause.simpleName(), cause.qualifiedName(), pattern)) {
                return true;
            }
        }

        return false;
    }

    private static Issue deadEntryIssue(String pattern, int status, String routesTomlPath) {
        return ErrorTypeMatcher.isExactReference(pattern)
               ? new Issue(IssueKind.DEAD_REFERENCE, deadReferenceMessage(pattern, status, routesTomlPath))
               : new Issue(IssueKind.DEAD_PATTERN, deadPatternMessage(pattern, status, routesTomlPath));
    }

    private static void collectDeadExplicitMappings(List<CauseDescriptor> causes,
                                                    ErrorPatternConfig config,
                                                    String routesTomlPath,
                                                    List<Issue> issues) {
        config.explicitMappings()
              .entrySet()
              .stream()
              .sorted(Map.Entry.comparingByKey())
              .forEach(entry -> collectDeadExplicitMapping(entry, causes, routesTomlPath, issues));
    }

    private static void collectDeadExplicitMapping(Map.Entry<String, Integer> entry,
                                                   List<CauseDescriptor> causes,
                                                   String routesTomlPath,
                                                   List<Issue> issues) {
        if (!anyCauseHasSimpleName(entry.getKey(), causes)) {
            issues.add(new Issue(IssueKind.DEAD_REFERENCE,
                                 deadExplicitMessage(entry.getKey(), entry.getValue(), routesTomlPath)));
        }
    }

    private static boolean anyCauseHasSimpleName(String simpleName, List<CauseDescriptor> causes) {
        for (var cause : causes) {
            if (cause.simpleName().equals(simpleName)) {
                return true;
            }
        }

        return false;
    }

    private static String unmappedMessage(CauseDescriptor cause, int defaultStatus, String routesTomlPath) {
        return "Unmapped Cause '" + cause.qualifiedName()
             + "': no HTTP status mapping, so it would fall through to HTTP " + defaultStatus
             + ". Add it under [errors] in " + routesTomlPath
             + " (e.g. 400 = [\"" + cause.simpleName()
             + "\"]).";
    }

    private static String deadPatternMessage(String pattern, int status, String routesTomlPath) {
        return "Dead error pattern '" + pattern
             + "' (HTTP " + status
             + "): it matches no Cause type in this slice. Remove it or fix the glob in " + routesTomlPath
             + ".";
    }

    private static String deadReferenceMessage(String reference, int status, String routesTomlPath) {
        return "Dead error type reference '" + reference
             + "' (HTTP " + status
             + "): it resolves to no Cause type in this slice - a renamed or removed Cause? Fix or remove it in " + routesTomlPath
             + ".";
    }

    private static String deadExplicitMessage(String name, int status, String routesTomlPath) {
        return "Dead explicit error mapping '" + name
             + "' (HTTP " + status
             + "): it resolves to no Cause type in this slice. Fix or remove it in " + routesTomlPath
             + ".";
    }

    record unused() implements ErrorMappingValidator {}
}
