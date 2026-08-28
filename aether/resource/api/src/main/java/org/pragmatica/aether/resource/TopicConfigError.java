// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource;

import org.pragmatica.lang.Cause;


/// Typed failures of topic-declaration parsing (durable-pubsub-spec §3).
///
/// Every rejection names the offending declaration and the fix, because these surface at deploy
/// time to an operator who did not write the slice: a bare "invalid config" would send them into
/// the slice source, the spec pointer keeps the round-trip to one edit.
public sealed interface TopicConfigError extends Cause {
    record MissingTopicName() implements TopicConfigError {
        @Override
        public String message() {
            return "Topic declaration has no topic_name (or it is blank)";
        }
    }

    record InvalidPartitions(int partitions) implements TopicConfigError {
        @Override
        public String message() {
            return "partitions must be >= 1, got " + partitions;
        }
    }

    /// The v1 durable-config constraint (durable-pubsub-spec §3): outside `min-sync == replicas >= 2`
    /// nothing is proven lossless — `replicas = 1` has no failover durability, and
    /// `min-sync < replicas` can drop acked records on single-survivor promotion until #411 lands.
    record OutsideProvenDurableConfig(int replicas, int minSyncReplicas) implements TopicConfigError {
        @Override
        public String message() {
            return "durable topic requires replicas >= 2 and min_sync_replicas == replicas"
                 + " (durable-pubsub-spec §3, v1 constraint until #411); got replicas=" + replicas
                 + ", min_sync_replicas=" + minSyncReplicas;
        }
    }

    /// Stream knobs on an ephemeral topic are inert — nothing reads them, so accepting them would
    /// promise durability the runtime does not provide (the config-honesty stance of #576: reject
    /// loudly instead of silently ignoring).
    record InertEphemeralKeys(String declaredKeys) implements TopicConfigError {
        @Override
        public String message() {
            return "ephemeral topic declares stream keys that have no effect: " + declaredKeys
                 + "; either declare durability = \"durable\" or remove them (durable-pubsub-spec §3)";
        }
    }

    static TopicConfigError missingTopicName() {
        return new MissingTopicName();
    }

    static TopicConfigError invalidPartitions(int partitions) {
        return new InvalidPartitions(partitions);
    }

    static TopicConfigError outsideProvenDurableConfig(int replicas, int minSyncReplicas) {
        return new OutsideProvenDurableConfig(replicas, minSyncReplicas);
    }

    static TopicConfigError inertEphemeralKeys(String declaredKeys) {
        return new InertEphemeralKeys(declaredKeys);
    }
}
