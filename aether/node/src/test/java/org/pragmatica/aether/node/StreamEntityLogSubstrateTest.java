// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.stream.EvictionListener;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.replication.ReplicationManager;
import org.pragmatica.aether.stream.replication.ReplicationMessage;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.node.StreamEntityLogSubstrate.streamEntityLogSubstrate;

/// #345 I3 — `awaitBarrier` must pass `minSyncReplicas - 1` to `StreamPartitionManager.awaitReplication`,
/// never the raw `minSyncReplicas`: the config counts the OWNER, `awaitReplication` counts DISTINCT
/// NON-SELF acks (see `DurableEntityConfig#minSyncReplicas` javadoc). The off-by-one made every entity
/// write wait for one peer ack too many — at the default `replicationFactor = 3` that is unsatisfiable
/// the instant a single peer is lost, and at `replicationFactor = 2` it never succeeds at all.
///
/// `StreamPartitionManager` is a concrete `final` class with no test seam of its own, but the collaborator
/// that actually receives the barrier count — `ReplicationManager` — is an interface the manager is built
/// with, so a capturing fake there observes the exact argument `awaitBarrier` passes without needing to
/// fake `StreamPartitionManager` itself. `storage`, `kvStore`, and `applier` are real constructor
/// parameters of `StreamEntityLogSubstrate` but are never touched by `ensureLog`/`append` — both methods
/// go through `partitionManager` alone — so they are passed as `null` rather than built for no purpose.
class StreamEntityLogSubstrateTest {

    @Test
    void append_awaitsMinSyncReplicasMinusOne_notRawMinSyncReplicas() {
        var capturedMinAcks = new AtomicInteger(Integer.MIN_VALUE);
        var partitionManager = StreamPartitionManager.streamPartitionManager(64L * 1024 * 1024,
                                                                              EvictionListener.NOOP,
                                                                              capturingReplicationManager(capturedMinAcks));
        var substrate = streamEntityLogSubstrate(partitionManager, (_, _) -> new StreamPartitionManager.ReplicaCatchupSource.CatchupView(0,
                                                                                                                                          false),
                                                 null,
                                                 null,
                                                 null);

        // minSyncReplicas=2 ("owner plus one peer") must await exactly ONE non-self ack.
        substrate.ensureLog("orders", 1, 2, 2).unwrap();
        substrate.append("orders", 0, new byte[] {1, 2, 3}).await().unwrap();

        assertThat(capturedMinAcks.get()).isEqualTo(1);
    }

    @Test
    void append_awaitsMinSyncReplicasMinusOne_scalesWithConfiguredValue() {
        var capturedMinAcks = new AtomicInteger(Integer.MIN_VALUE);
        var partitionManager = StreamPartitionManager.streamPartitionManager(64L * 1024 * 1024,
                                                                              EvictionListener.NOOP,
                                                                              capturingReplicationManager(capturedMinAcks));
        var substrate = streamEntityLogSubstrate(partitionManager, (_, _) -> new StreamPartitionManager.ReplicaCatchupSource.CatchupView(0,
                                                                                                                                          false),
                                                 null,
                                                 null,
                                                 null);

        // minSyncReplicas=3 ("owner plus two peers") must await exactly TWO non-self acks — guards
        // against a mutant that hardcodes `1` rather than computing `minSyncReplicas - 1`.
        substrate.ensureLog("orders", 1, 3, 3).unwrap();
        substrate.append("orders", 0, new byte[] {1, 2, 3}).await().unwrap();

        assertThat(capturedMinAcks.get()).isEqualTo(2);
    }

    private static ReplicationManager capturingReplicationManager(AtomicInteger capturedMinAcks) {
        var registry = ReplicaRegistry.replicaRegistry();

        return new ReplicationManager() {
            @Override
            public void replicateEvent(String streamName,
                                       int partition,
                                       long offset,
                                       byte[] payload,
                                       long timestamp,
                                       Epoch ownerEpoch) {}

            @Override
            public void handleAck(ReplicationMessage.ReplicateAck ack) {}

            @Override
            public ReplicaRegistry registry() {
                return registry;
            }

            @Override
            public Promise<Unit> awaitReplication(String streamName, int partition, long offset, int minAcks) {
                capturedMinAcks.set(minAcks);

                return Promise.success(Unit.unit());
            }
        };
    }
}
