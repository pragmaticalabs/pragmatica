// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.parse.TimeSpan;

import static org.pragmatica.lang.Result.success;


/// Resolved stream parameters of a DURABLE topic (durable-pubsub-spec §3) — the durable tier's
/// knobs with declaration defaults applied, valid by construction.
///
/// The v1 durable-config constraint is enforced HERE, at parse (§3): `replicas >= 2` and
/// `min-sync-replicas == replicas`. That is exactly the configuration whose lossless owner-kill
/// failover is proven (streaming-spec §10.5 scoping); everything outside it is rejected with a
/// pointer to the spec section rather than accepted and silently weaker. When #411 (multi-survivor
/// union catch-up) lands, the constraint relaxes to `2 <= min-sync <= replicas` by amending this
/// factory — not silently.
///
/// `minSyncReplicas` counts the owner (Kafka `min.insync.replicas` convention, same as
/// [org.pragmatica.aether.slice.StreamConfig]).
public record DurableTopicSpec(int partitions, int replicas, int minSyncReplicas, TimeSpan retention) {
    public static final int DEFAULT_PARTITIONS = 1;
    public static final int DEFAULT_REPLICAS = 2;
    public static final TimeSpan DEFAULT_RETENTION = TimeSpan.timeSpan("7d").unwrap();

    public static Result<DurableTopicSpec> durableTopicSpec(int partitions,
                                                            int replicas,
                                                            int minSyncReplicas,
                                                            TimeSpan retention) {
        if (partitions < 1) {
            return TopicConfigError.invalidPartitions(partitions).result();
        }

        if (replicas < 2 || minSyncReplicas != replicas) {
            return TopicConfigError.outsideProvenDurableConfig(replicas, minSyncReplicas).result();
        }

        return success(new DurableTopicSpec(partitions, replicas, minSyncReplicas, retention));
    }
}
