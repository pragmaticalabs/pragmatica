// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.List;
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

    /// #596 review S4: `ensureLog` is idempotent, and idempotent must not mean SHAPE-BLIND. A redeploy
    /// declaring a different partition_count re-hashes every key against a stream laid out for the old
    /// count — keys land on partitions whose history lives elsewhere and read back as absent. The
    /// mismatch is refused at provisioning, naming both shapes.
    @Test
    void ensureLog_existingStreamWithDifferentShape_isRefused_namingBothShapes() {
        var partitionManager = StreamPartitionManager.streamPartitionManager(64L * 1024 * 1024);
        var substrate = streamEntityLogSubstrate(partitionManager, (_, _) -> new StreamPartitionManager.ReplicaCatchupSource.CatchupView(0,
                                                                                                                                          false),
                                                 null,
                                                 null,
                                                 null);

        substrate.ensureLog("orders", 8, 3, 2).unwrap();

        var mismatched = substrate.ensureLog("orders", 4, 3, 2);

        assertThat(mismatched.isFailure()).isTrue();

        String refusal = mismatched.fold(cause -> cause.message(), _ -> "unexpectedly succeeded");

        assertThat(refusal).contains("partitions=8")
                           .contains("partitions=4");
    }

    @Test
    void ensureLog_existingStreamWithTheSameShape_staysIdempotent() {
        var partitionManager = StreamPartitionManager.streamPartitionManager(64L * 1024 * 1024);
        var substrate = streamEntityLogSubstrate(partitionManager, (_, _) -> new StreamPartitionManager.ReplicaCatchupSource.CatchupView(0,
                                                                                                                                          false),
                                                 null,
                                                 null,
                                                 null);

        substrate.ensureLog("orders", 8, 3, 2).unwrap();

        assertThat(substrate.ensureLog("orders", 8, 3, 2).isSuccess())
            .as("the same declaration must keep re-ensuring cleanly — every node hosting the keyspace calls it")
            .isTrue();
    }

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

    /// #1235: the fold replays up to `headOffset` — the APPENDED head — and reads a short batch below it as
    /// a truncated log, so the substrate's read must reach records that are not yet consumer-visible. The
    /// fake reports the barrier met (so the append returns) while acknowledging nothing in `replicatedThrough`
    /// (so the record stays invisible to a stream consumer).
    @Test
    void read_servesAnAppendedRecord_beforeItIsConsumerVisible() {
        var partitionManager = StreamPartitionManager.streamPartitionManager(64L * 1024 * 1024,
                                                                              EvictionListener.NOOP,
                                                                              capturingReplicationManager(new AtomicInteger(),
                                                                                                          -1L));
        var substrate = streamEntityLogSubstrate(partitionManager, (_, _) -> new StreamPartitionManager.ReplicaCatchupSource.CatchupView(0,
                                                                                                                                          false),
                                                 null,
                                                 null,
                                                 null);

        substrate.ensureLog("orders", 1, 2, 2).unwrap();
        substrate.append("orders", 0, new byte[] {1, 2, 3}).await().unwrap();

        assertThat(substrate.headOffset("orders", 0)).isEqualTo(0L);
        assertThat(substrate.read("orders", 0, 0L, 10).await().map(List::size).or(-1)).isEqualTo(1);
    }

    private static ReplicationManager capturingReplicationManager(AtomicInteger capturedMinAcks) {
        return capturingReplicationManager(capturedMinAcks, Long.MAX_VALUE);
    }

    private static ReplicationManager capturingReplicationManager(AtomicInteger capturedMinAcks, long acknowledgedThrough) {
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

            @Override
            public long replicatedThrough(String streamName, int partition, int minAcks) {
                return acknowledgedThrough;
            }

            @Override
            public long replicatedThrough(ReplicationMessage.ReplicateAck pending, int minAcks) {
                return acknowledgedThrough;
            }

            @Override
            public void observeAcks(AckObserver observer) {}
        };
    }
}
