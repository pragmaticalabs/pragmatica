// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.pragmatica.aether.metrics.ClusterSyncCollector;
import org.pragmatica.aether.resource.entity.EntityCheckpointDriver;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.EntityCheckpointKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.EntityFoldCheckpointValue;
import org.pragmatica.cluster.state.kvstore.KVStore;


/// The node-side bindings of the durable-entity checkpoint-lag metric (#1302, #1330), named so each is
/// testable on its own and pinnable as reachable from production.
///
/// A lambda at the call site was reachable and still wrong in ways nothing caught: bound to a misspelled
/// metric name, it computed every lag and could never alert, and the bytecode reachability pin stayed
/// green. Here the metric name is asserted by a unit test against the collector the alert path reads.
public sealed interface EntityCheckpointLagMetric {
    /// Report the driver's lag into `collector` under [EntityCheckpointDriver#CHECKPOINT_LAG_METRIC], the
    /// exact name the alert threshold is seeded under.
    static EntityCheckpointDriver.CheckpointLagSink sinkFor(ClusterSyncCollector collector) {
        return lag -> collector.recordCustom(EntityCheckpointDriver.CHECKPOINT_LAG_METRIC, lag);
    }

    /// The COMMITTED checkpoint of a partition — the consensus-KV pointer the retention floor and every
    /// recovery read — as the lag's baseline. A local, synchronous read of committed state; empty when no
    /// checkpoint was ever committed.
    static EntityCheckpointDriver.CommittedCheckpoints committedCheckpoints(KVStore<AetherKey, AetherValue> kvStore) {
        return (keyspace, partition) -> kvStore.getTyped(EntityCheckpointKey.entityCheckpointKey(keyspace, partition),
                                                         EntityFoldCheckpointValue.class)
                                               .map(EntityFoldCheckpointValue::throughOffset);
    }

    record unused() implements EntityCheckpointLagMetric {}
}
