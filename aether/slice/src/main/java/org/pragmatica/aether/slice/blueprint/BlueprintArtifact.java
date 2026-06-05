// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.blueprint;

import org.pragmatica.lang.Option;
import org.pragmatica.serialization.Codec;
import org.pragmatica.serialization.CodecFor;

import java.util.List;
import java.util.Map;


/// Parsed blueprint artifact bundle.
///
/// `roleHints` (spec event-stream-namespaces §11.1.2) maps a stream alias (the part after
/// `streams.` in a `[streams.X]` section) to its inferred role — `producer`, `consumer`, or
/// `both` — derived from the bundled slice manifests' `StreamPublisher`/`StreamAccess` parameter
/// bindings. Empty when the artifact carries no slice manifests (legacy DSL path or pre-Wave-3
/// blueprint JARs).
@Codec@CodecFor(Blueprint.class) public record BlueprintArtifact(Blueprint blueprint,
                                                                 Option<String> resourcesConfig,
                                                                 Map<String, List<MigrationEntry>> schemaMigrations,
                                                                 Map<String, String> roleHints) {
    public BlueprintArtifact {
        schemaMigrations = schemaMigrations == null
                          ? Map.of()
                          : Map.copyOf(schemaMigrations);
        roleHints = roleHints == null
                   ? Map.of()
                   : Map.copyOf(roleHints);
    }

    public static BlueprintArtifact blueprintArtifact(Blueprint blueprint,
                                                      Option<String> resourcesConfig,
                                                      Map<String, List<MigrationEntry>> schemaMigrations,
                                                      Map<String, String> roleHints) {
        return new BlueprintArtifact(blueprint,
                                     resourcesConfig,
                                     Map.copyOf(schemaMigrations),
                                     Map.copyOf(roleHints));
    }

    public static BlueprintArtifact blueprintArtifact(Blueprint blueprint,
                                                      Option<String> resourcesConfig,
                                                      Map<String, List<MigrationEntry>> schemaMigrations) {
        return new BlueprintArtifact(blueprint, resourcesConfig, Map.copyOf(schemaMigrations), Map.of());
    }

    public static BlueprintArtifact blueprintArtifact(Blueprint blueprint) {
        return new BlueprintArtifact(blueprint, Option.none(), Map.of(), Map.of());
    }
}
