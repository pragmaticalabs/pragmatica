// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.blueprint;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.pragmatica.aether.slice.stream.DeclarationBounds;
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

    /// A value above its key's maximum (#1937): a `max_event_size` that cannot travel in one replication or catch-up frame.
    record ValueAboveMaximum(String alias, String key, String value, long maximum) implements StreamDeclarationError {
        @Override
        public String message() {
            return "Stream resource '" + alias
                 + "' has " + key
                 + " = '" + value
                 + "', above the maximum of " + maximum
                 + " bytes: one event must fit a single replication or catch-up frame";
        }
    }

    /// The node supplied no [org.pragmatica.aether.slice.ReplicationContext.Source], so the section's factors cannot be
    /// resolved against the committed cluster defaults. Refused rather than resolved against a guess (#1564).
    record ReplicationContextUnavailable(String alias) implements StreamDeclarationError {
        @Override
        public String message() {
            return "Stream resource '" + alias
                 + "' cannot resolve its replication factors: the node supplies no replication context";
        }
    }

    /// The section's `replication_factor`/`confirmation_factor` refused by [org.pragmatica.aether.slice.ReplicationDeclaration#resolve]
    /// (#1564); `cause` is the typed [org.pragmatica.aether.slice.ReplicationFactorsError].
    record ReplicationRefused(String alias, Cause cause) implements StreamDeclarationError {
        @Override
        public String message() {
            return "Stream resource '" + alias + "': " + cause.message();
        }
    }

    /// A `[retention]` block that declares no bound (#3333, S2a1 section 2.2): none of `max_age`, `max_bytes`, `max_count`.
    /// Refused rather than defaulted, because a block that bounds nothing is a mistake, not a request for the default.
    record RetentionUndeclared(String alias) implements StreamDeclarationError {
        @Override
        public String message() {
            return "Stream resource '" + alias
                 + "' declares a [retention] block with no bound: declare at least one of max_age, max_bytes, max_count,"
                 + " or remove the block for the default";
        }
    }

    /// A `[retention]` block that declares `max_count` without `max_bytes` (#3333, S2a1 section 2.2): a count alone has no
    /// byte bound a user would recognise, so it is refused here instead of at plan, where the reason would be hidden.
    record RetentionCountOnly(String alias, long maxCount, long maxEventSize) implements StreamDeclarationError {
        @Override
        public String message() {
            return "Stream resource '" + alias
                 + "' declares retention.max_count = " + maxCount
                 + " without retention.max_bytes: add max_bytes; max_count alone bounds this stream only at max_count x max_event_size (" + maxEventSize
                 + " bytes per event)";
        }
    }

    /// `hot.max_bytes` or `hot.max_count` below two seal runs (#3333, S2a1 section 2.2): a hot window that cannot hold two
    /// seal runs is refused at parse, never clamped up. `dimension` is the key (`hot.max_bytes` or `hot.max_count`), `declared`
    /// its value (the default counts when the key is not written) and `minimum` the smallest value that clears the check.
    record HotWindowBelowSealRuns(String alias, String dimension, long declared, long minimum) implements StreamDeclarationError {
        @Override
        public String message() {
            return "Stream resource '" + alias
                 + "' declares " + dimension
                 + " = " + declared
                 + ", below two seal runs: the smallest valid " + dimension
                 + " is " + minimum;
        }
    }

    /// `hot.max_bytes` below `max_event_size` (#3333, S2a1 section 2.2): no ring of the stream could accept a maximal event.
    /// Checked after [HotWindowBelowSealRuns]. The create-time twin is `StreamError.HotWindowBelowMaxEvent` in `aether-stream`,
    /// which this module cannot see; it stays the second line.
    record HotWindowBelowMaxEvent(String alias, long hotMaxBytes, long maxEventSize) implements StreamDeclarationError {
        @Override
        public String message() {
            return "Stream resource '" + alias
                 + "' declares hot.max_bytes = " + hotMaxBytes
                 + ", below max_event_size = " + maxEventSize
                 + ": no ring of the stream could accept one maximal event; the smallest valid hot.max_bytes is " + maxEventSize;
        }
    }

    /// A replay-window share below the seal or ring floor (#3333, S2a1 section 2.2): `arm` is the check that failed, `setBy`
    /// the `retention` key whose value sets the share, `share` the per-partition value compared (bytes; records for the count
    /// arm), `minimum` what it is compared against and `smallestValid` the smallest value of `setBy` that clears the arm for
    /// this partition count. Refused rather than clamped up.
    record RetentionShareTooSmall(String alias,
                                  DeclarationBounds.Arm arm,
                                  String setBy,
                                  long share,
                                  long minimum,
                                  long smallestValid) implements StreamDeclarationError {
        @Override
        public String message() {
            return "Stream resource '" + alias
                 + "' declares " + setBy
                 + " so small that the per-partition share is " + share + unitOf(arm)
                 + ", below " + floorOf(arm)
                 + " (" + minimum + unitOf(arm)
                 + "): the smallest valid " + setBy
                 + " is " + smallestValid;
        }

        private static String unitOf(DeclarationBounds.Arm arm) {
            return arm == DeclarationBounds.Arm.COUNT
                   ? " records"
                   : " bytes";
        }

        private static String floorOf(DeclarationBounds.Arm arm) {
            return switch (arm) {
                case BYTE -> "two merged segments";
                case COUNT -> "two seal runs";
                case EVENT -> "max_event_size";
            };
        }
    }

    /// `hot.max_count` above the index capacity of the ring's first data region (#3333, S2a1 section 2.2): `maxCapacity` is
    /// `RingGeometry.maxCapacity(firstSegmentBytes(hot.max_bytes, max_event_size))`. A separate record from
    /// `StreamError.RetentionCountUnindexable` in `aether-stream` (the create-time check), which this module cannot see; the
    /// retention `max_count` is not what is checked.
    record RetentionCountUnindexable(String alias, long maxCount, long maxCapacity) implements StreamDeclarationError {
        @Override
        public String message() {
            return "Stream resource '" + alias
                 + "' declares hot.max_count = " + maxCount
                 + ", above the indexable ring capacity " + maxCapacity
                 + " of its first data region";
        }
    }

    /// A key whose release has not merged yet (#3333, T-146): refused instead of accepted and ignored, so no declaration is
    /// stored that nothing enforces. The release that lands the behaviour removes the refusal.
    record NotYetAvailable(String alias, String key, String release) implements StreamDeclarationError {
        @Override
        public String message() {
            return "Stream resource '" + alias
                 + "' declares " + key
                 + " with a value that is not available before release " + release;
        }
    }

    /// `truncation.policy = "until_consumed"` with no `retention.max_bytes` (#3333, S2a1 section 2.2, O1): a stream held
    /// for its slowest group needs a byte cap, and an age bound alone is not one.
    record UntilConsumedUncapped(String alias) implements StreamDeclarationError {
        @Override
        public String message() {
            return "Stream resource '" + alias
                 + "' declares truncation.policy = \"until_consumed\" without retention.max_bytes: add max_bytes to [retention];"
                 + " max_age alone does not cap a stream held for its slowest consumer group";
        }
    }

    /// `truncation.policy = "until_consumed"` with `retention.mode = "all"` (#3333, S2a1 section 2.2): under `all` a prefix
    /// stays until every bound is exceeded, so the age bound would lift the byte cap.
    record UntilConsumedModeAll(String alias) implements StreamDeclarationError {
        @Override
        public String message() {
            return "Stream resource '" + alias
                 + "' declares truncation.policy = \"until_consumed\" with retention.mode = \"all\": use mode = \"any\";"
                 + " under \"all\" the age bound would lift the byte cap";
        }
    }

    /// `truncation.policy = "until_consumed"` on a stream kind that has no committed cursor to wait for (#3333, S2a1
    /// section 2.2): an `entity:` log has its own floor and a DLQ has no committed cursor. `kind` names which.
    record UntilConsumedUnsupported(String alias, String kind) implements StreamDeclarationError {
        @Override
        public String message() {
            return "Stream resource '" + alias
                 + "' declares truncation.policy = \"until_consumed\", which a " + kind
                 + " stream does not support: remove the key";
        }
    }

    /// `truncation.group_idle_expiry` on a stream whose policy is `window`, declared or defaulted (#3333, S2a1 section 2.2):
    /// the key means nothing there, so it is refused rather than ignored.
    record GroupIdleExpiryWithoutUntilConsumed(String alias) implements StreamDeclarationError {
        @Override
        public String message() {
            return "Stream resource '" + alias
                 + "' declares truncation.group_idle_expiry with truncation.policy = \"window\": the key applies only to"
                 + " \"until_consumed\"; remove it or declare that policy";
        }
    }

    /// A section that declares the storage model (`[retention]`, `[truncation]`, `[hot]`) and a key of today's retention
    /// model (`retention`, `retention_value`, `retention_mode`, top-level `max_age`, `max_count`, `max_bytes`) at once
    /// (#3333, T-163): one refusal naming one key of each, never a parse that reads one model and drops the other.
    record ConflictingDeclarationModels(String alias, String newKey, String oldKey) implements StreamDeclarationError {
        @Override
        public String message() {
            return "Stream resource '" + alias
                 + "' declares both " + newKey
                 + " and " + oldKey
                 + ": declare retention in the [retention] block only and remove " + oldKey;
        }
    }

    /// A `[[streams.<alias>.<name>]]` table array under a stream section (#3520): the section holds tables, and no part of the
    /// parser reads an array of them, so it is refused as malformed instead of accepted and ignored. `name` is the full table
    /// array name, as the TOML document reports it.
    record TableArrayDeclared(String alias, String name) implements StreamDeclarationError {
        @Override
        public String message() {
            return "Stream resource '" + alias
                 + "' declares [[" + name
                 + "]] as an array of tables: a stream section holds tables, so write it as [" + name
                 + "]";
        }
    }

    /// Keys of the section, or of its consumer sections, that have no runtime effect ([InertKeyRules]), refused at activation
    /// as the deploy validator refuses them (#2201 E-02): a declaration that reaches a node without passing the validator
    /// does not bind with a key that silently does nothing.
    record InertKeysRefused(String alias, List<String> reasons) implements StreamDeclarationError {
        @Override
        public String message() {
            return "Stream resource '" + alias + "' declares keys with no runtime effect: " + String.join("; ", reasons);
        }
    }
}
