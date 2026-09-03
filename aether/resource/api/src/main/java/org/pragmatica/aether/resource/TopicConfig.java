// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource;

import java.util.ArrayList;
import java.util.List;

import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.aether.slice.resource.ResourceVersion;
import org.pragmatica.config.StrictKeys;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.parse.TimeSpan;

import static org.pragmatica.lang.Option.none;


/// Pub/sub topic configuration.
///
/// The wire/TOML shape keeps the original single `topic_name` field verbatim for backward
/// compatibility — existing un-namespaced declarations (`topic_name = "order-events"`) keep
/// deserializing unchanged, binding to durability [TopicDurability#EPHEMERAL] with no stream knobs
/// (all `Option` components absent, falling back through [#DEFAULT]).
///
/// The durable-pubsub extension (spec §3, D1 — ratified 2026-07-18 on #386) adds the declared
/// durability class plus the durable tier's stream knobs:
///
/// ```toml
/// [order-events]
/// topic_name = "order-events"
/// durability = "durable"        # "ephemeral" (default) | "durable"
/// partitions = 4                 # durable only; default 1
/// replicas = 2                   # durable only; default 2
/// min_sync_replicas = 2          # durable only; default = replicas
/// retention = "7d"               # durable only; default 7d
/// ```
///
/// Validation is parse-time via [#topicConfig] (the TOML binder invokes the matching static
/// factory when one exists): a durable declaration outside the proven `min-sync == replicas >= 2`
/// configuration is rejected (see [DurableTopicSpec]), and stream knobs on an ephemeral topic are
/// rejected as inert rather than silently ignored (#576 config-honesty stance). The knob
/// components are `Option`-typed precisely so declared-vs-absent is distinguishable — an absent
/// key falls back to the durable tier's default at resolution, a declared key on an ephemeral
/// topic is a loud error.
///
/// The namespaced [ResourceAddress] is a derived view, not a stored field, so config
/// deserialization is unaffected. [#address] resolves the declared string to a canonical
/// `namespace:topic:version`:
///  - a value that already parses as a full `namespace:topic:version` is used verbatim;
///  - a bare topic name resolves to [ResourceAddress#DEFAULT_NAMESPACE] + [ResourceVersion#defaultVersion]
///    (the deploy path replaces the default namespace with the blueprint-derived one via
///    `resolveTopicName`).
///
/// [#topicName] remains the bare-name convenience accessor used for runtime pub/sub routing.
///
/// [StrictKeys]-annotated: an unrecognized key in the topic's own section (most commonly a dashed
/// key where a `min_sync_replicas`-style underscore is expected) fails the bind loudly instead of
/// resolving to `none()`/`DEFAULT` indistinguishably from the key never having been written (#738).
/// The check never inspects nested sub-sections such as a consumer group table, and is scoped to
/// the static/file-backed layer only — an environment variable, system property, or KV-overlay
/// entry landing at `<section>.<one segment>` never fails this check regardless of spelling, since
/// none of those layers wrote the TOML section this record declares (#738 review finding).
///
/// **Known limitation, not a defect**: a quoted key with a literal dot (`"a.b" = 1`) is, once the
/// TOML source flattens it, byte-identical to a genuine nested sub-table (`[order-events.a]` /
/// `b = 1`) — nothing downstream of the parser retains section structure to tell them apart, so
/// such a key is silently accepted rather than flagged as unknown, exactly as a real nested
/// sub-section would be. Avoid quoted dotted keys in a durable-pubsub topic section; use a nested
/// table instead if the grouping is intentional.
@StrictKeys
public record TopicConfig(String topicName,
                          TopicDurability durability,
                          Option<Integer> partitions,
                          Option<Integer> replicas,
                          Option<Integer> minSyncReplicas,
                          Option<TimeSpan> retention) {
    /// Binder fallback for components absent from TOML (per-component accessor lookup). The blank
    /// topic name never survives binding: [#topicConfig] rejects it, keeping a missing `topic_name`
    /// as loud as it was before this constant existed.
    public static final TopicConfig DEFAULT = new TopicConfig("");

    /// The original single-field shape: an ephemeral topic with no stream knobs. Every
    /// pre-extension construction site keeps compiling — and keeps meaning what it meant.
    public TopicConfig(String topicName) {
        this(topicName, TopicDurability.EPHEMERAL, none(), none(), none(), none());
    }

    /// Parse-time validating factory — the TOML binder resolves this (exact component signature,
    /// `Result` return) in preference to the canonical constructor, so every declaration bound
    /// from `resources.toml` passes through it.
    public static Result<TopicConfig> topicConfig(String topicName,
                                                  TopicDurability durability,
                                                  Option<Integer> partitions,
                                                  Option<Integer> replicas,
                                                  Option<Integer> minSyncReplicas,
                                                  Option<TimeSpan> retention) {
        return Verify.ensure(topicName,
                             Verify.Is::present,
                             TopicConfigError.missingTopicName())
                     .flatMap(name -> validateTier(new TopicConfig(name,
                                                                   durability,
                                                                   partitions,
                                                                   replicas,
                                                                   minSyncReplicas,
                                                                   retention)));
    }

    /// Resolved durable-tier parameters: present exactly when the topic is durable, with the §3
    /// declaration defaults applied. Ephemeral topics resolve to a successful `none()` — the knobs
    /// do not exist for them, which is the honest shape rather than zero-filled placeholders. The
    /// failure branch fires only for a durable config built through the canonical constructor with
    /// knobs the [#topicConfig] factory would have rejected — loud at the point of use instead of
    /// silently degrading to ephemeral.
    public Result<Option<DurableTopicSpec>> durableSpec() {
        return durability == TopicDurability.DURABLE
               ? resolveSpec().map(Option::some)
               : Result.success(none());
    }

    /// Resolve the declared topic string to a canonical [ResourceAddress].
    ///
    /// Back-compat: a bare name (no `:` separators) is lifted to
    /// `default:<topic>:1.0.0`; an already-namespaced `namespace:topic:version` value is parsed
    /// as-is. The deploy path overrides the placeholder namespace with the blueprint-derived one.
    public Result<ResourceAddress> address() {
        return topicName != null && topicName.contains(":")
               ? ResourceAddress.resourceAddress(topicName)
               : ResourceAddress.resourceAddress(ResourceAddress.DEFAULT_NAMESPACE,
                                                 topicName,
                                                 ResourceVersion.defaultVersion());
    }

    private static Result<TopicConfig> validateTier(TopicConfig config) {
        return switch (config.durability()) {
            case EPHEMERAL -> config.rejectInertKeys();
            case DURABLE -> config.resolveSpec().map(_ -> config);
        };
    }

    private Result<TopicConfig> rejectInertKeys() {
        var declared = declaredStreamKeys();

        return declared.isEmpty()
               ? Result.success(this)
               : TopicConfigError.inertEphemeralKeys(String.join(", ", declared)).result();
    }

    private Result<DurableTopicSpec> resolveSpec() {
        var resolvedReplicas = replicas.or(DurableTopicSpec.DEFAULT_REPLICAS);

        return DurableTopicSpec.durableTopicSpec(partitions.or(DurableTopicSpec.DEFAULT_PARTITIONS),
                                                 resolvedReplicas,
                                                 minSyncReplicas.or(resolvedReplicas),
                                                 retention.or(DurableTopicSpec.DEFAULT_RETENTION));
    }

    private List<String> declaredStreamKeys() {
        var declared = new ArrayList<String>();

        partitions.onPresent(_ -> declared.add("partitions"));
        replicas.onPresent(_ -> declared.add("replicas"));
        minSyncReplicas.onPresent(_ -> declared.add("min_sync_replicas"));
        retention.onPresent(_ -> declared.add("retention"));

        return declared;
    }
}
