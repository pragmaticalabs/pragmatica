// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.machine;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.cluster.state.kvstore.KVCommand;


/// A KV command contributed by a local step to the batch the FSM submits next, tagged with the leg it belongs to so the
/// FSM can apply the last-replica guards (spec R-3.4.5) and drop the stream-ref ACQUIRE on reactivation (R-3.4.7).
public record LegCommand(LegKind kind, KVCommand<AetherKey> command) {
    /// The registry a command writes to.
    public enum LegKind {
        /// Node-scoped topic subscription key.
        TOPIC_SUBSCRIPTION,
        /// Cluster-scoped `StreamRegistrationKey`: removed only by the last replica (Q10).
        STREAM_REGISTRATION,
        /// Cluster-scoped scheduled-task key and its pause key: removed only by the last replica (B-145).
        SCHEDULED_TASK,
        /// Stream-ref ACQUIRE (rides in the ACTIVE batch) or RELEASE (rides in the teardown batch).
        STREAM_REF
    }

    public static LegCommand legCommand(LegKind kind, KVCommand<AetherKey> command) {
        return new LegCommand(kind, command);
    }
}
