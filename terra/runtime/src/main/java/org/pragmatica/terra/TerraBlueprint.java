// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.terra;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.config.toml.TomlParser;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Option;


/// Selection of exact slice coordinates using the Aether blueprint TOML shape.
public record TerraBlueprint(List<String> artifacts, Option<String> id) {
    public TerraBlueprint {
        artifacts = List.copyOf(artifacts);
    }

    public TerraBlueprint(List<String> artifacts) {
        this(artifacts, Option.none());
    }

    public static Result<TerraBlueprint> parse(String toml) {
        return TomlParser.parse(toml).flatMap(TerraBlueprint::selection);
    }

    private static Result<TerraBlueprint> selection(TomlDocument document) {
        if (!Set.of("", "blueprint").containsAll(document.sections().keySet()) || !Set.of("slices").containsAll(document.tableArrays()
                                                                                                                        .keySet())) {
            return new TerraError.InvalidBlueprint("Terra supports blueprint identity and slice selection only; unsupported section").result();
        }

        if (document.sections().values().stream().anyMatch(section -> !Set.of("id").containsAll(section.keySet()))) {
            return new TerraError.InvalidBlueprint("Unsupported blueprint option; only id is accepted").result();
        }

        var entries = document.tableArrays().getOrDefault("slices", List.of());

        if (entries.isEmpty() || entries.stream().anyMatch(entry -> !supportedSlice(entry))) {
            return new TerraError.InvalidBlueprint("Each [[slices]] requires a versioned artifact; unsupported slice fields are refused").result();
        }

        var artifacts = entries.stream().map(e -> (String) e.get("artifact")).toList();

        return artifacts.stream()
                        .distinct()
                        .count() == artifacts.size()
               ? identity(document).map(id -> new TerraBlueprint(artifacts, id))
               : new TerraError.InvalidBlueprint("Duplicate slice artifact in blueprint").result();
    }

    private static Result<Option<String>> identity(TomlDocument document) {
        var values = document.sections()
                             .values()
                             .stream()
                             .filter(section -> section.containsKey("id"))
                             .map(section -> section.get("id"))
                             .toList();

        if (values.isEmpty()) {
            return Result.success(Option.none());
        }

        if (values.size() != 1 || !(values.getFirst() instanceof String id) || id.split(":", -1).length != 3 || Arrays.stream(id.split(":",
                                                                                                                                       - 1)).anyMatch(String::isBlank)) {
            return new TerraError.InvalidBlueprint("Declare one versioned blueprint id (group:artifact:version)").result();
        }

        return Result.success(Option.some((String) values.getFirst()));
    }

    private static boolean supportedSlice(Map<String, Object> entry) {
        var permitted = Set.of("artifact",
                               "instances",
                               "minAvailable",
                               "maxInstances",
                               "scaleUpThreshold",
                               "scaleDownThreshold");

        return permitted.containsAll(entry.keySet())
               && entry.get("artifact") instanceof String coordinate
               && coordinate.split(":", -1).length == 3
               && Arrays.stream(coordinate.split(":", -1)).noneMatch(String::isBlank);
    }
}
