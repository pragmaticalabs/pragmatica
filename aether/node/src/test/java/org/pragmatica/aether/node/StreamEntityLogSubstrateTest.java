// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.resource.entity.EntityLogError;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.stream.EvictionListener;
import org.pragmatica.aether.stream.OffHeapRingBuffer;
import org.pragmatica.aether.stream.StreamError;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.replication.ReplicationManager;
import org.pragmatica.aether.stream.replication.ReplicationMessage;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
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
/// fake `StreamPartitionManager` itself. `tieredReader`, `segmentIndex`, `storage`, `kvStore`, and `applier` are real
/// constructor parameters of `StreamEntityLogSubstrate` but are never touched by `ensureLog`/`append` — both methods
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
                                                 EvictionListener.NOOP,
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
                                                 EvictionListener.NOOP,
                                                 null,
                                                 null,
                                                 null);

        substrate.ensureLog("orders", 8, 3, 2).unwrap();

        assertThat(substrate.ensureLog("orders", 8, 3, 2).isSuccess())
            .as("the same declaration must keep re-ensuring cleanly — every node hosting the keyspace calls it")
            .isTrue();
    }

    /// #1230: the stream's owner admission can refuse an entity append in the race after
    /// `EntityOwnerAdmission` passed — the committed record moved to another node between the two reads.
    /// That is a stale-owner condition the entity caller can act on (re-resolve and go to the owner), so it
    /// must arrive in the entity vocabulary as [EntityLogError.StaleOwnerAppend], naming the committed owner,
    /// not fall through untranslated and surface as `StorageFailed`.
    @Test
    void append_translatesNotOwnerAppend_toStaleOwnerAppend_namingTheCommittedOwner() {
        var partitionManager = StreamPartitionManager.streamPartitionManager(64L * 1024 * 1024);
        var substrate = streamEntityLogSubstrate(partitionManager, (_, _) -> new StreamPartitionManager.ReplicaCatchupSource.CatchupView(0,
                                                                                                                                          false),
                                                 null,
                                                 null,
                                                 null);

        substrate.ensureLog("orders", 1, 1, 0).unwrap();
        partitionManager.ownerWriteAdmission((_, _) -> Option.some(new NodeId("node-owner")));

        var refusal = substrate.append("orders", 0, new byte[] {1, 2, 3}).await();

        assertThat(refusal.isFailure()).isTrue();
        refusal.onFailure(cause -> assertThat(cause).isInstanceOf(EntityLogError.StaleOwnerAppend.class)
                                                    .extracting(Cause::message)
                                                    .asString()
                                                    .contains("node-owner"));
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
                                                 EvictionListener.NOOP,
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
                                                 EvictionListener.NOOP,
                                                 null,
                                                 null,
                                                 null);

        // minSyncReplicas=3 ("owner plus two peers") must await exactly TWO non-self acks — guards
        // against a mutant that hardcodes `1` rather than computing `minSyncReplicas - 1`.
        substrate.ensureLog("orders", 1, 3, 3).unwrap();
        substrate.append("orders", 0, new byte[] {1, 2, 3}).await().unwrap();

        assertThat(capturedMinAcks.get()).isEqualTo(2);
    }

    /// #1233: an entity keyspace log is durable state even at replicationFactor = 1 with no WAL (Forge,
    /// embedded, or the non-durable opt-in). A record the frozen partition ring cannot store must FAIL the
    /// append, never be acked as a committed write. Driven through the real `ensureLog`/`append` path so
    /// the `entity:` naming the drop rule keys on (`EntityPartitionArc.arcName` here,
    /// `StreamPartitionManager`'s entity prefix there) cannot drift apart unnoticed.
    @Test
    void append_failsWithEventDropped_whenFrozenRingCannotFitRecord_evenAtReplicationFactorOneWithoutWal() {
        // Exactly one entity partition floor (10k index slots + the 256 KiB first segment of the 64 MiB
        // cap, per StreamEntityLogSubstrate's ring sizing): creation fits, every growth is refused.
        var floor = OffHeapRingBuffer.floorBytes(10_000L, 64L * 1024 * 1024);
        var partitionManager = StreamPartitionManager.streamPartitionManager(floor);
        var substrate = streamEntityLogSubstrate(partitionManager, (_, _) -> new StreamPartitionManager.ReplicaCatchupSource.CatchupView(0,
                                                                                                                                          false),
                                                 null,
                                                 null,
                                                 EvictionListener.NOOP,
                                                 null,
                                                 null,
                                                 null);

        substrate.ensureLog("ledger", 1, 1, 1).unwrap();
        substrate.append("ledger", 0, new byte[16]).await().unwrap();

        substrate.append("ledger", 0, new byte[300_000])
                 .await()
                 .onSuccess(offset -> fail("an entity record the frozen ring dropped was acked at offset " + offset))
                 .onFailure(cause -> assertThat(cause).isEqualTo(StreamError.General.EVENT_DROPPED));
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
