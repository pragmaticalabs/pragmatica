// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.dht.EntityPartitionArc;
import org.pragmatica.aether.resource.entity.EntityLogError;
import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamCompression;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.stream.RingGeometry;
import org.pragmatica.aether.stream.EntityRingWindow;
import org.pragmatica.aether.stream.EvictionListener;
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

    /// #1564 R8 pin (entities): a keyspace redeclared with different replication factors is refused, typed — the
    /// committed policy of a live resource is not changed in place.
    @Test
    void ensureLog_existingStreamWithDifferentFactors_isRefused() {
        var partitionManager = StreamPartitionManager.streamPartitionManager(64L * 1024 * 1024);
        var substrate = streamEntityLogSubstrate(partitionManager, (_, _) -> new StreamPartitionManager.ReplicaCatchupSource.CatchupView(0,
                                                                                                                                          false),
                                                 null,
                                                 null,
                                                 EvictionListener.NOOP,
                                                 null,
                                                 null,
                                                 null);

        substrate.ensureLog("orders", 8, new org.pragmatica.aether.slice.ReplicationFactors(3, 2)).unwrap();

        var changed = substrate.ensureLog("orders", 8, new org.pragmatica.aether.slice.ReplicationFactors(3, 3));

        assertThat(changed.<Object> fold(cause -> cause, _ -> "accepted")).isInstanceOf(org.pragmatica.aether.slice.ReplicationFactorsError.ChangedOnLiveResource.class);
    }

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

        substrate.ensureLog("orders", 8, new org.pragmatica.aether.slice.ReplicationFactors(3, 2)).unwrap();

        var mismatched = substrate.ensureLog("orders", 4, new org.pragmatica.aether.slice.ReplicationFactors(3, 2));

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

        substrate.ensureLog("orders", 8, new org.pragmatica.aether.slice.ReplicationFactors(3, 2)).unwrap();

        assertThat(substrate.ensureLog("orders", 8, new org.pragmatica.aether.slice.ReplicationFactors(3, 2)).isSuccess())
            .as("the same declaration must keep re-ensuring cleanly — every node hosting the keyspace calls it")
            .isTrue();
    }

    /// Pins the entity ring window as BUILT: the config a created entity log enforces carries the literals of
    /// `EntityRingWindow` (10 000 entries, 64 MiB, 365 days, 1 MiB record), so a drift of any one constant goes red here.
    /// `CAPACITY`, `MAX_BYTES`
    /// and `MAX_AGE_MS` are pinned here and nowhere else.
    @Test
    void ensureLog_buildsTheEntityRingWindowFromTheDeclaredLiterals() {
        var partitionManager = StreamPartitionManager.streamPartitionManager(64L * 1024 * 1024);
        var substrate = streamEntityLogSubstrate(partitionManager, (_, _) -> new StreamPartitionManager.ReplicaCatchupSource.CatchupView(0,
                                                                                                                                          false),
                                                 null,
                                                 null,
                                                 EvictionListener.NOOP,
                                                 null,
                                                 null,
                                                 null);

        substrate.ensureLog("orders", 8, new org.pragmatica.aether.slice.ReplicationFactors(3, 2)).unwrap();

        var config = partitionManager.enforcedConfig(EntityPartitionArc.arcName("orders")).unwrap();

        assertThat(config.retention().maxCount()).as("ring capacity").isEqualTo(10_000L);
        assertThat(config.retention().maxBytes()).as("ring data-region cap").isEqualTo(67_108_864L);
        assertThat(config.retention().maxAgeMs()).as("ring age bound").isEqualTo(31_536_000_000L);
        assertThat(config.maxEventSizeBytes()).as("entity max event size").isEqualTo(1_048_576L);
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
                                                 EvictionListener.NOOP,
                                                 null,
                                                 null,
                                                 null);

        substrate.ensureLog("orders", 1, new org.pragmatica.aether.slice.ReplicationFactors(3, 1)).unwrap();
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
        substrate.ensureLog("orders", 1, new org.pragmatica.aether.slice.ReplicationFactors(3, 2)).unwrap();
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
        substrate.ensureLog("orders", 1, new org.pragmatica.aether.slice.ReplicationFactors(3, 3)).unwrap();
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
                                                 EvictionListener.NOOP,
                                                 null,
                                                 null,
                                                 null);

        substrate.ensureLog("orders", 1, new org.pragmatica.aether.slice.ReplicationFactors(3, 2)).unwrap();
        substrate.append("orders", 0, new byte[] {1, 2, 3}).await().unwrap();

        assertThat(substrate.headOffset("orders", 0)).isEqualTo(0L);
        assertThat(substrate.read("orders", 0, 0L, 10).await().map(List::size).or(-1)).isEqualTo(1);
    }

    /// #1233: an entity keyspace log is durable state even at replicationFactor = 1 with no WAL (Forge,
    /// embedded, or the non-durable opt-in). A record the frozen partition ring cannot store must FAIL the
    /// append, never be acked as a committed write. Driven through the real `ensureLog`/`append` path so
    /// the `entity:` naming the drop rule keys on (`EntityPartitionArc.arcName` here,
    /// `StreamPartitionManager`'s entity prefix there) cannot drift apart unnoticed.
    @Test
    @Disabled("#1233 pin unreachable once the ring is sized from the hot window: the entity ring reserves its whole 1 MiB max_event_size up front, so no legal entity record can exceed its region; handed to S2b-2a phase 2, enable when a ring-level drop of a legal record exists again; the enabled tripwire below pins the behaviour that replaced it")
    void append_failsWithEventDropped_whenFrozenRingCannotFitRecord_evenAtReplicationFactorOneWithoutWal() {
        // The budget is the entity partition floor: 10k index slots + the first data region the entity ring reserves
        // (the 1 MiB entity max_event_size, within the 64 MiB cap, per StreamEntityLogSubstrate's ring sizing). Creation
        // fits, no growth beyond it is admitted, and the record below is exactly one max-size record, which cannot fit
        // beside the first one in that region whichever way the ring is sized (retention or hot window).
        var floor = RingGeometry.floorBytes(10_000L, 64L * 1024 * 1024, 1_048_576L);
        var partitionManager = StreamPartitionManager.streamPartitionManager(floor);
        var substrate = streamEntityLogSubstrate(partitionManager, (_, _) -> new StreamPartitionManager.ReplicaCatchupSource.CatchupView(0,
                                                                                                                                          false),
                                                 null,
                                                 null,
                                                 EvictionListener.NOOP,
                                                 null,
                                                 null,
                                                 null);

        substrate.ensureLog("ledger", 1, new org.pragmatica.aether.slice.ReplicationFactors(3, 1)).unwrap();
        substrate.append("ledger", 0, new byte[16]).await().unwrap();

        substrate.append("ledger", 0, new byte[1_048_576])
                 .await()
                 .onSuccess(offset -> fail("an entity record the frozen ring dropped was acked at offset " + offset))
                 .onFailure(cause -> assertThat(cause).isEqualTo(StreamError.General.EVENT_DROPPED));
    }

    /// Tripwire beside the disabled #1233 pin above: an entity record of exactly the ring's `max_event_size` is ACKED, never
    /// dropped, even at replicationFactor = 1 with no WAL (Forge, embedded, or the non-durable opt-in) and a budget that
    /// admits no growth, because the ring reserves a whole `max_event_size` region at creation. The ring-level drop that the
    /// disabled test drives is unreachable for a legal entity record; this goes red the day it becomes reachable again, and
    /// then the disabled test is the one to enable. Driven through the real `ensureLog`/`append` path (over a pre-created `entity:` ring with a 256 KiB `max_event_size`, see the test body) so
    /// the `entity:` naming the drop rule keys on (`EntityPartitionArc.arcName` here,
    /// `StreamPartitionManager`'s entity prefix there) cannot drift apart unnoticed.
    @Test
    void append_acksAnEntityRecordOfFullMaxEventSize_atReplicationFactorOneWithoutWalAndNoGrowthBudget() {
        // The `entity:` ring is created here BEFORE `ensureLog` (which tolerates an existing stream of the same shape) so that its
        // `max_event_size` is the first segment (256 KiB) instead of the entity 1 MiB: with the ring sized from the hot window the
        // entity ring reserves its whole `max_event_size` up front, so a legal 1 MiB record can no longer outgrow it and the drop
        // is only reachable through a smaller first region. Its window is the entity window (10k entries, 64 MiB; the entity
        // ring takes it from `EntityRingWindow`, never from a declaration, and before the ring reads the declaration it takes it
        // from this retention), so the floor is the same with and without the declaration read. The budget is exactly that floor:
        // creation fits, every growth is refused, and the record below is one full `max_event_size`.
        var arcName = EntityPartitionArc.arcName("ledger");
        var ring = StreamConfig.streamConfig(arcName,
                                             1,
                                             RetentionPolicy.retentionPolicy(EntityRingWindow.CAPACITY,
                                                                             EntityRingWindow.MAX_BYTES,
                                                                             EntityRingWindow.MAX_AGE_MS),
                                             "earliest",
                                             RingGeometry.SEGMENT_BYTES,
                                             ConsistencyMode.EVENTUAL,
                                             3,
                                             1,
                                             StreamCompression.NONE,
                                             Option.none());
        var floor = RingGeometry.floorBytes(EntityRingWindow.CAPACITY, EntityRingWindow.MAX_BYTES, RingGeometry.SEGMENT_BYTES);
        var partitionManager = StreamPartitionManager.streamPartitionManager(floor);
        var substrate = streamEntityLogSubstrate(partitionManager, (_, _) -> new StreamPartitionManager.ReplicaCatchupSource.CatchupView(0,
                                                                                                                                          false),
                                                 null,
                                                 null,
                                                 EvictionListener.NOOP,
                                                 null,
                                                 null,
                                                 null);

        partitionManager.createDeclaredStream(ring).unwrap();
        substrate.ensureLog("ledger", 1, new org.pragmatica.aether.slice.ReplicationFactors(3, 1)).unwrap();
        substrate.append("ledger", 0, new byte[16]).await().unwrap();

        substrate.append("ledger", 0, new byte[(int) RingGeometry.SEGMENT_BYTES])
                 .await()
                 .onSuccess(offset -> assertThat(offset).isEqualTo(1L))
                 .onFailure(cause -> fail("an entity record of exactly max_event_size was refused: " + cause.message()));
    }

    /// T-278 refusal arm (O19 ruled (c)): the entity `max_event_size` is 1 MiB. A record one byte over is refused with the
    /// typed `EventTooLarge(1_048_577, 1_048_576)` before anything is stored: the head does not move and the refusal is not
    /// the ring's `EVENT_DROPPED`. A record of exactly 1 MiB is acked at the next offset (substrate level only; the entity-level arm, which also reads it back and checks the key state and the log tail, is `EntityOversizedUpdateRefusalTest`).
    @Test
    void append_refusesAnEntityRecordOverOneMiB_andAcksOneOfExactlyOneMiB() {
        var partitionManager = StreamPartitionManager.streamPartitionManager(512L * 1024 * 1024,
                                                                              EvictionListener.NOOP,
                                                                              capturingReplicationManager(new AtomicInteger()));
        var substrate = streamEntityLogSubstrate(partitionManager, (_, _) -> new StreamPartitionManager.ReplicaCatchupSource.CatchupView(0,
                                                                                                                                          false),
                                                 null,
                                                 null,
                                                 EvictionListener.NOOP,
                                                 null,
                                                 null,
                                                 null);

        substrate.ensureLog("ledger", 1, new org.pragmatica.aether.slice.ReplicationFactors(3, 1)).unwrap();

        substrate.append("ledger", 0, new byte[1_048_577])
                 .await()
                 .onSuccess(offset -> fail("an entity record over 1 MiB was acked at offset " + offset))
                 .onFailure(cause -> assertThat(cause).isEqualTo(new StreamError.EventTooLarge(1_048_577, 1_048_576)));
        assertThat(substrate.headOffset("ledger", 0)).isEqualTo(-1L);

        assertThat(substrate.append("ledger", 0, new byte[1_048_576]).await().unwrap()).isEqualTo(0L);
        assertThat(substrate.headOffset("ledger", 0)).isEqualTo(0L);
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
