// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.blueprint;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;


/// A `[streams.X]` section [StreamConfigParser] refuses for what it declares, as opposed to how its
/// `source` or `version` is spelled (those carry the address and version types' own causes). Typed so the
/// deploy validator derives the reported rule from the cause, never from its text (#1336 review, B1).
public sealed interface StreamDeclarationError extends Cause {
    /// Both `source` (external) and `version` (owned) set on one section.
    record VersionAndSourceBothSet(String alias) implements StreamDeclarationError {
        @Override
        public String message() {
            return "Stream resource '" + alias + "' must not set both 'source' and 'version'";
        }
    }

    /// A producing role with `version = "latest"` (spec §11.1.3).
    record ProducerVersionLatest(String alias, String role) implements StreamDeclarationError {
        @Override
        public String message() {
            return "Stream resource '" + alias
                 + "' has role '" + role
                 + "' with version 'latest'; producers must pin to an exact MAJOR.MINOR.PATCH triplet (spec §11.1.3)";
        }
    }

    /// More partitions than [StreamConfigParser#MAX_PARTITIONS_PER_STREAM_CEILING] (spec §7/§10).
    record PartitionsOverCeiling(String alias, int partitions, int ceiling) implements StreamDeclarationError {
        @Override
        public String message() {
            return "Stream resource '" + alias
                 + "' declares " + partitions
                 + " partitions, over the per-stream ceiling of " + ceiling;
        }
    }

    /// A key directly under `[streams.<alias>]` that no part of the stream parser reads (#1549). Refused
    /// rather than ignored: before #1549 the runtime read snake_case keys the parser never saw, and the
    /// parser ignored them in turn, so `min_sync_replicas = 3` validated as `0` and was provisioned as
    /// `3` — or, in the dashed spelling, validated as `3` and was provisioned as `0`. One spelling is
    /// accepted now, and everything else names the key and, where one is close, the key it resembles.
    record UnknownStreamKeys(String alias, List<String> keys, Map<String, String> suggestions) implements StreamDeclarationError {
        public UnknownStreamKeys {
            keys = List.copyOf(keys);
            suggestions = Map.copyOf(suggestions);
        }

        @Override
        public String message() {
            return "Stream resource '" + alias
                 + "' declares key(s) the stream parser does not read: " + keys.stream()
                                                                               .map(this::describe)
                                                                               .collect(Collectors.joining(", "));
        }

        private String describe(String key) {
            return Option.option(suggestions.get(key))
                         .filter(suggestion -> !suggestion.isEmpty())
                         .map(suggestion -> "'" + key + "' (did you mean '" + suggestion + "'?)")
                         .or("'" + key + "'");
        }
    }

    /// An integer key whose value is not an integer (#1549): refused, never defaulted.
    record NotAnInteger(String alias, String key, String value) implements StreamDeclarationError {
        @Override
        public String message() {
            return "Stream resource '" + alias + "' has " + key + " = '" + value + "', which is not an integer";
        }
    }

    /// A value whose text is not in the form its key takes (#1549) — `"1.5MB"`, `"5 min"`, an unknown
    /// retention or consistency spelling. Refused rather than defaulted or thrown.
    record MalformedValue(String alias, String key, String value, String expected) implements StreamDeclarationError {
        @Override
        public String message() {
            return "Stream resource '" + alias + "' has " + key + " = '" + value + "', expected " + expected;
        }
    }

    /// A value too large to represent once its unit is applied (#1549) — it used to wrap into a negative
    /// bound silently (`"999999999999999d"`) or throw (`count` past the `long` range).
    record ValueOverflows(String alias, String key, String value) implements StreamDeclarationError {
        @Override
        public String message() {
            return "Stream resource '" + alias + "' has " + key + " = '" + value + "', which overflows";
        }
    }

    /// A value below its key's minimum (#1549): a count, size or duration of 0, or partitions below 1.
    record ValueOutOfRange(String alias, String key, String value, long minimum) implements StreamDeclarationError {
        @Override
        public String message() {
            return "Stream resource '" + alias + "' has " + key + " = '" + value + "', below the minimum of " + minimum;
        }
    }

    /// `replicas < 1`, or `min-sync-replicas > replicas` (spec §11.x).
    record ReplicationInvalid(String alias, String detail) implements StreamDeclarationError {
        @Override
        public String message() {
            return "Stream resource '" + alias + "' has " + detail;
        }
    }
}
