// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.validation;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.blueprint.BlueprintNamespace;
import org.pragmatica.aether.slice.blueprint.StreamConfigParser;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.aether.slice.stream.StreamResource;
import org.pragmatica.aether.slice.stream.StreamVersionSpec;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes.CompositeCause;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


/// Deploy-time validator that enforces spec §15.1 runtime gates over a blueprint's stream
/// resources, aggregating **all** failures via [Result#allOf] (spec §15.1.1) instead of
/// short-circuiting at the first error.
///
/// Reusable from both the artifact-deploy path ([org.pragmatica.aether.deployment.cluster.BlueprintService#publishFromArtifact])
/// and the validate route. Returns:
///  - [Result#success] with [ValidatedStreamResources] (parsed map + non-blocking warnings) on
///    pass.
///  - [Result#failure] carrying [StreamValidationFailures] (a [CompositeCause] of
///    [StreamValidationFailure] entries + the warnings collected up to the failure point) on
///    rejection.
///
/// The HTTP route handler is responsible for translating that failure into a 422 response —
/// the validator itself does not depend on `http-routing` so it stays reusable from the CLI / audit
/// paths too (spec §15.1.2).
@SuppressWarnings("JBCT-SEQ-01")
public sealed interface StreamResourceValidator {
    String RULE_NAMESPACE_RESERVED = "namespace-reserved";
    String RULE_RESOURCES_PARSE = "resources-toml-parse";
    String RULE_BLUEPRINT_NAMESPACE = "blueprint-namespace-invalid";
    String RULE_VERSION_PIN_RECOMMENDED = "version-pin-recommended";

    /// Run the full validation pass for a deploy attempt.
    ///
    /// `resourcesConfig` is the raw `resources.toml` content from the blueprint artifact (absent
    /// when the blueprint declares no resources). `blueprintArtifact` carries the Maven coords
    /// from which the namespace is derived. `roleHints` maps a stream alias to its inferred role
    /// per spec §11.1.2 — empty when no slice manifests are available (e.g., legacy DSL path).
    static Result<ValidatedStreamResources> validate(Option<String> resourcesConfig,
                                                     Artifact blueprintArtifact,
                                                     Map<String, String> roleHints) {
        var failures = new ArrayList<StreamValidationFailure>();
        var warnings = new ArrayList<StreamValidationWarning>();

        guardBlueprintNamespace(blueprintArtifact, failures);
        var resources = parseResourcesOrCollect(resourcesConfig, roleHints, failures);

        if (failures.isEmpty()) {
            collectMultiVersionWarnings(resources, resourcesConfig, roleHints, warnings);
        }

        if (failures.isEmpty()) {
            return Result.success(ValidatedStreamResources.validatedStreamResources(resources, warnings));
        }

        return StreamValidationFailures.streamValidationFailures(List.copyOf(failures),
                                                                 List.copyOf(warnings))
                                       .result();
    }

    private static void guardBlueprintNamespace(Artifact blueprintArtifact, List<StreamValidationFailure> failures) {
        BlueprintNamespace.deriveNamespace(blueprintArtifact).onFailure(cause -> failures.add(StreamValidationFailure.streamValidationFailure(blueprintArtifact.asString(),
                                                                                                                                              classifyNamespaceFailure(cause),
                                                                                                                                              cause.message())));
    }

    private static String classifyNamespaceFailure(Cause cause) {
        return cause.message()
                    .toLowerCase()
                    .contains("reserved")
               ? RULE_NAMESPACE_RESERVED
               : RULE_BLUEPRINT_NAMESPACE;
    }

    private static Map<String, StreamResource> parseResourcesOrCollect(Option<String> resourcesConfig,
                                                                       Map<String, String> roleHints,
                                                                       List<StreamValidationFailure> failures) {
        return resourcesConfig.map(toml -> StreamConfigParser.parseResourcesAggregating(toml, roleHints))
                              .or(Result.success(Map.<String, StreamResource> of()))
                              .fold(cause -> collectParseFailures(cause, failures),
                                    map -> map);
    }

    private static Map<String, StreamResource> collectParseFailures(Cause cause,
                                                                    List<StreamValidationFailure> failures) {
        if (cause instanceof CompositeCause composite) {
            composite.stream().forEach(inner -> failures.add(toFailure(inner)));
        } else {
            failures.add(toFailure(cause));
        }

        return Map.of();
    }

    private static StreamValidationFailure toFailure(Cause cause) {
        var message = cause.message();

        return StreamValidationFailure.streamValidationFailure(extractField(message), inferRule(message), message);
    }

    private static String extractField(String message) {
        var openQuote = message.indexOf('\'');

        if (openQuote < 0) {
            return "[streams]";
        }

        var closeQuote = message.indexOf('\'', openQuote + 1);

        if (closeQuote < 0) {
            return "[streams]";
        }

        return "[streams." + message.substring(openQuote + 1, closeQuote) + "]";
    }

    private static String inferRule(String message) {
        var lower = message.toLowerCase();

        if (lower.contains("must not set both")) {
            return "version-and-source-mutually-exclusive";
        }

        if (lower.contains("producer") && lower.contains("latest")) {
            return "producer-version-must-be-exact";
        }

        if (lower.contains("must specify either")) {
            return "version-or-source-required";
        }

        if (lower.contains("namespace") && lower.contains("reserved")) {
            return RULE_NAMESPACE_RESERVED;
        }

        if (lower.contains("namespace")) {
            return "namespace-invalid";
        }

        if (lower.contains("stream name")) {
            return "stream-name-invalid";
        }

        if (lower.contains("version")) {
            return "version-format-invalid";
        }

        if (lower.contains("parse error")) {
            return RULE_RESOURCES_PARSE;
        }

        return "stream-resource-invalid";
    }

    /// Spec §11.1.3 multi-version warning: when a blueprint declares two or more
    /// `(stream, distinct-version)` pairs sharing the same alias **and** at least one declaration
    /// resolved its version from a default (no explicit `version` field), recommend an explicit
    /// pin. Implemented at the (alias, version-spec) level since this validator runs over a single
    /// blueprint's resources.toml. Cross-blueprint multi-version is a cluster-state concern handled
    /// by the registry, not here.
    private static void collectMultiVersionWarnings(Map<String, StreamResource> resources,
                                                    Option<String> resourcesConfig,
                                                    Map<String, String> roleHints,
                                                    List<StreamValidationWarning> warnings) {
        var explicitVersionAliases = collectExplicitVersionAliases(resourcesConfig);
        var byAlias = groupByAlias(resources);

        byAlias.forEach((alias, entries) -> {
            if (entries.size() <= 1) {
                return;
            }

            var anyDefaulted = entries.stream()
                                      .anyMatch(spec -> !explicitVersionAliases.contains(alias));

            if (anyDefaulted) {
                warnings.add(StreamValidationWarning.streamValidationWarning("[streams." + alias + "]",
                                                                             RULE_VERSION_PIN_RECOMMENDED,
                                                                             "Stream '" + alias
                                                                            + "' has multiple version declarations and at least one omits "
                                                                            + "'version'; pin every declaration explicitly per spec §11.1.3"));
            }
        });
        unused(roleHints);
    }

    /// `roleHints` is reserved for future cross-namespace warnings (e.g., consumer with mismatched
    /// inferred-vs-explicit role). Currently used only by the parser; the warning pass is a
    /// no-op on it. Kept on the signature so the wiring stays stable when those rules land.
    private static void unused(Map<String, String> roleHints) {
        if (roleHints == null) {
            throw new IllegalArgumentException("roleHints must not be null");
        }
    }

    private static Set<String> collectExplicitVersionAliases(Option<String> resourcesConfig) {
        return resourcesConfig.map(StreamResourceValidator::scanExplicitVersionAliases)
                              .or(Set.of());
    }

    private static Set<String> scanExplicitVersionAliases(String toml) {
        var aliases = new LinkedHashSet<String>();
        var currentAlias = new StringBuilder();
        var aliasActive = false;

        for (var rawLine : toml.split("\n")) {
            var line = rawLine.trim();

            if (line.startsWith("[streams.")) {
                aliasActive = parseAliasHeader(line, currentAlias);
                continue;
            }

            if (line.startsWith("[")) {
                aliasActive = false;
                continue;
            }

            if (aliasActive && line.startsWith("version")) {
                aliases.add(currentAlias.toString());
            }
        }

        return Set.copyOf(aliases);
    }

    private static boolean parseAliasHeader(String line, StringBuilder out) {
        var inner = line.substring(1, line.length() - 1).trim();

        if (!inner.startsWith("streams.")) {
            return false;
        }

        var alias = inner.substring("streams.".length());

        if (alias.contains(".")) {
            return false;
        }

        out.setLength(0);
        out.append(alias);

        return true;
    }

    private static Map<String, List<StreamVersionSpec>> groupByAlias(Map<String, StreamResource> resources) {
        var byAlias = new HashMap<String, List<StreamVersionSpec>>();

        resources.forEach((alias, resource) -> versionSpecOf(resource).onPresent(spec -> byAlias.computeIfAbsent(alias,
                                                                                                                 _ -> new ArrayList<>())
                                                                                                .add(spec)));

        return byAlias;
    }

    private static Option<StreamVersionSpec> versionSpecOf(StreamResource resource) {
        return switch (resource) {
            case StreamResource.Owned owned -> Option.some(owned.version());
            case StreamResource.External _ -> Option.none();
        };
    }

    /// Defensive double-check of the spec §4.3 reserved-namespace rule. The address parser already
    /// enforces this; this hook keeps the runtime gate explicit per spec §15.1.
    static boolean isReservedNamespace(String namespace) {
        return ResourceAddress.isReservedNamespace(namespace);
    }

    record unused() implements StreamResourceValidator {}

    /// Convenience constructor for the no-role-hints case (legacy DSL path / tests).
    static Result<ValidatedStreamResources> validate(Option<String> resourcesConfig, Artifact blueprintArtifact) {
        return validate(resourcesConfig, blueprintArtifact, Map.of());
    }

    /// Convenience: project resources only when the validator is being used purely for the parsed
    /// map (validate route).
    static Result<Map<String, StreamResource>> validateResources(Option<String> resourcesConfig,
                                                                 Artifact blueprintArtifact,
                                                                 Map<String, String> roleHints) {
        return validate(resourcesConfig, blueprintArtifact, roleHints).map(ValidatedStreamResources::resources);
    }

    /// Mutating helper retained for the per-warning-aggregation contract used by the orchestrator.
    /// Kept package-private so test code can exercise the warning collector in isolation if needed.
    static List<StreamValidationWarning> emptyWarnings() {
        return new ArrayList<>();
    }

    /// Helper for tests that need to flatten a CompositeCause for assertion convenience without
    /// reaching into Pragmatica internals.
    static List<StreamValidationFailure> flattenFailures(Cause cause) {
        var collected = new LinkedHashMap<String, StreamValidationFailure>();

        flattenInto(cause, collected);

        return List.copyOf(collected.values());
    }

    private static void flattenInto(Cause cause, Map<String, StreamValidationFailure> sink) {
        switch (cause) {
            case StreamValidationFailures composite -> composite.failures().forEach(failure -> sink.putIfAbsent(failure.field()
                                                                                                               + "::" + failure.rule(),
                                                                                                                failure));
            case StreamValidationFailure failure -> sink.putIfAbsent(failure.field() + "::" + failure.rule(),
                                                                     failure);
            case CompositeCause composite -> composite.stream().forEach(inner -> flattenInto(inner, sink));
            default -> {
                var fallback = StreamValidationFailure.streamValidationFailure("[streams]",
                                                                               "stream-resource-invalid",
                                                                               cause.message());

                sink.putIfAbsent(fallback.field() + "::" + fallback.rule(),
                                 fallback);
            }
        }
    }
}
