// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.jbct.slice.topic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.config.toml.TomlParser;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;


/// Reads the declared durability class of each topic section from a slice's `resources.toml`.
///
/// This is the slice-processor's ONLY compile-time source of a topic's durability: `Topic<T>`
/// carries just the name and payload type, and `TopicConfig` / `TopicDurability` are bound at
/// runtime. Without this loader the D5 type-level-honesty rule (durable-pubsub-spec §3, #386)
/// could not be enforced at compile time at all.
///
/// ```toml
/// [order-events]
/// topic_name = "order-events"
/// durability = "durable"     # "ephemeral" (default) | "durable"
/// ```
///
/// A [Result] failure means the file was missing or unparseable — deliberately distinct from a
/// successfully-read file that declares nothing, so the caller can tell "declared ephemeral" from
/// "could not be determined" and apply its fail-closed policy in one visible place rather than
/// having this loader silently answer "ephemeral" for both.
public final class TopicDurabilityLoader {
    public static final String CONFIG_FILE = "resources.toml";

    /// The declared value that selects the durable tier. Mirrors `TopicDurability.DURABLE` as a
    /// literal rather than an enum reference on purpose: `resource-api` is a `provided` dependency,
    /// so it is not guaranteed to be on the annotation-processor path of a consuming build.
    private static final String DURABLE_VALUE = "durable";
    private static final String DURABILITY_KEY = "durability";

    private static final Cause FILE_NOT_FOUND = Causes.cause("Topic durability configuration file not found");
    private static final Cause PARSE_ERROR = Causes.cause("Failed to parse topic durability configuration");

    private TopicDurabilityLoader() {}

    /// The set of topic sections a slice declares durable, resolved once per slice and queried per
    /// subscription binding.
    public record TopicDurabilityIndex(Set<String> durableSections) {
        /// True when `configSection` names a section that declares `durability = "durable"`.
        /// Everything else — an absent section, a section without the key, or an unrecognized
        /// value — is the ephemeral default of spec §3.
        public boolean isDurable(String configSection) {
            return durableSections.contains(configSection);
        }
    }

    /// Load the durability index from a specific `resources.toml`.
    public static Result<TopicDurabilityIndex> load(Path resourcesPath) {
        if (!Files.exists(resourcesPath) || !Files.isRegularFile(resourcesPath)) {
            return FILE_NOT_FOUND.result();
        }

        return TomlParser.parseFile(resourcesPath)
                         .fold(_ -> PARSE_ERROR.<TomlDocument> result(),
                               Result::success)
                         .map(TopicDurabilityLoader::index);
    }

    private static TopicDurabilityIndex index(TomlDocument toml) {
        return new TopicDurabilityIndex(toml.sectionNames()
                                            .stream()
                                            .filter(section -> declaresDurable(toml, section))
                                            .collect(Collectors.toUnmodifiableSet()));
    }

    private static boolean declaresDurable(TomlDocument toml, String section) {
        return toml.getString(section, DURABILITY_KEY)
                   .map(value -> DURABLE_VALUE.equalsIgnoreCase(value.trim()))
                   .or(false);
    }
}
