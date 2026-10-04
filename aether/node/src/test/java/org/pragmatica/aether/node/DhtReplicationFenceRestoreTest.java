// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DhtReplicationChangeValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DhtReplicationStage;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.dht.ConsistentHashRing;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.dht.DHTMessage;
import org.pragmatica.dht.DHTNode;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;

/// #1777, v1882 round 5: a restored replica's DHT fence becomes known — and it accepts writes — only once consensus has
/// applied its log up to the commit point it observed (`RabiaNode.isPendingCatchUp` false), never at snapshot restore.
/// A restored prefix can hold an older replication change while a newer one waits in the log tail; until the tail is
/// applied, the fence would be too old and would accept writes stamped under the factors the cluster has left.
class DhtReplicationFenceRestoreTest {
    private static final NodeId SELF = new NodeId("core-1");
    private static final SliceCodec CODEC = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());
    private static final LeaderValue LEADER = LeaderValue.leaderValue(SELF, 1L);
    private static final byte[] KEY = "restore-key".getBytes(StandardCharsets.UTF_8);
    private static final DHTConfig OLD = DHTConfig.DEFAULT.withFactors(3, 1).unwrap();
    private static final DHTConfig NEW = DHTConfig.DEFAULT.withFactors(3, 2).unwrap();
    private static final long OLD_CHANGE = 3L;
    private static final long NEW_CHANGE = 7L;

    @Test
    void restoredPrefixWithAPendingChangeInTheTail_refusesWritesUntilTheTailIsApplied() {
        var kvStore = new KVStore<AetherKey, AetherValue>(MessageRouter.mutable(), CODEC, CODEC);
        var dhtNode = DHTNode.dhtNodeAwaitingReplication(SELF, memoryStorageEngine(), ring(), DHTConfig.DEFAULT, HlcClock.hlcClock(SELF));
        var caughtUp = new AtomicBoolean(false);
        var settlement = settlement(kvStore, dhtNode, caughtUp);

        commitLeader(kvStore);
        commitChange(kvStore, Option.none(), settled(OLD_CHANGE, 3, 1));
        // the restored prefix: config and change record at the OLD factors; a newer change is still in the log tail
        dhtNode.resolveReplication(OLD, OLD_CHANGE);
        settlement.reapply();

        assertThat(dhtNode.replicationFence()).as("arming: the prefix's change is adopted").isEqualTo(OLD_CHANGE);
        assertThat(dhtNode.acceptsWrites()).as("restored, but consensus still applying its tail: unknown").isFalse();
        assertThat(put(dhtNode, OLD_CHANGE).fenceUnknown()).as("an old-stamped write is refused, not accepted").isTrue();

        settlement.report();
        assertThat(dhtNode.acceptsWrites()).as("the tick re-checks: still catching up").isFalse();

        // the tail applies: the newer configuration, then its committed change record
        dhtNode.resolveReplication(NEW, NEW_CHANGE);
        var change = settled(NEW_CHANGE, 3, 2);

        commitChange(kvStore, Option.some(settled(OLD_CHANGE, 3, 1)), change);
        settlement.onChangeCommitted(Option.some(settled(OLD_CHANGE, 3, 1)), change);
        caughtUp.set(true);
        settlement.report();

        assertThat(dhtNode.acceptsWrites()).as("caught up: known").isTrue();
        assertThat(put(dhtNode, OLD_CHANGE).replicationStale()).as("now refused as stale, by the newer fence").isTrue();
        assertThat(put(dhtNode, NEW_CHANGE).success()).isTrue();
    }

    /// A restore with no committed change at all is a baseline: known once caught up, accepting baseline stamps.
    @Test
    void restoreWithNoCommittedChange_isKnownOnceCaughtUp() {
        var kvStore = new KVStore<AetherKey, AetherValue>(MessageRouter.mutable(), CODEC, CODEC);
        var dhtNode = DHTNode.dhtNodeAwaitingReplication(SELF, memoryStorageEngine(), ring(), DHTConfig.DEFAULT, HlcClock.hlcClock(SELF));
        var caughtUp = new AtomicBoolean(true);
        var settlement = settlement(kvStore, dhtNode, caughtUp);

        dhtNode.resolveReplication(OLD, 1L);
        assertThat(dhtNode.acceptsWrites()).as("not restored yet").isFalse();

        settlement.reapply();

        assertThat(dhtNode.acceptsWrites()).isTrue();
        assertThat(put(dhtNode, DHTNode.NO_CHANGE).success()).isTrue();
    }

    /// v1882 r6 (M6): a caught-up consensus alone does not make the fence known — the state must have been restored first.
    /// The tick (`report`) runs before any restore here, and must not confirm a fence nothing has adopted.
    @Test
    void consensusCaughtUpButStateNotRestored_isNotKnown() {
        var kvStore = new KVStore<AetherKey, AetherValue>(MessageRouter.mutable(), CODEC, CODEC);
        var dhtNode = DHTNode.dhtNodeAwaitingReplication(SELF, memoryStorageEngine(), ring(), DHTConfig.DEFAULT, HlcClock.hlcClock(SELF));
        var settlement = settlement(kvStore, dhtNode, new AtomicBoolean(true));

        dhtNode.resolveReplication(OLD, OLD_CHANGE);
        settlement.report();

        assertThat(dhtNode.acceptsWrites()).as("caught up but nothing restored: unknown").isFalse();
        assertThat(put(dhtNode, OLD_CHANGE).fenceUnknown()).isTrue();

        settlement.reapply();

        assertThat(dhtNode.acceptsWrites()).as("control: restored and caught up: known").isTrue();
    }

    private static DHTMessage.PutResponse put(DHTNode node, long stamp) {
        var response = new AtomicReference<DHTMessage.PutResponse>();

        node.handlePutRequest(new DHTMessage.PutRequest(UUID.randomUUID().toString(),
                                                        new NodeId("writer"),
                                                        KEY,
                                                        "v".getBytes(StandardCharsets.UTF_8),
                                                        System.nanoTime(),
                                                        0L,
                                                        0L,
                                                        0L,
                                                        stamp),
                              response::set);

        return response.get();
    }

    private static DhtReplicationSettlement settlement(KVStore<AetherKey, AetherValue> kvStore,
                                                       DHTNode dhtNode,
                                                       AtomicBoolean caughtUp) {
        return DhtReplicationSettlement.dhtReplicationSettlement(new DhtReplicationSettlement.Inputs(SELF,
                                                                                                    true,
                                                                                                    kvStore,
                                                                                                    dhtNode,
                                                                                                    DHTConfig.DEFAULT,
                                                                                                    () -> false,
                                                                                                    _ -> Promise.success(List.of()),
                                                                                                    Set::of,
                                                                                                    _ -> false,
                                                                                                    _ -> {},
                                                                                                    System::currentTimeMillis,
                                                                                                    caughtUp::get));
    }

    private static DhtReplicationChangeValue settled(long version, int rf, int cf) {
        return new DhtReplicationChangeValue(version, rf, cf, cf, rf - cf + 1, rf, DhtReplicationStage.SETTLED, 0L, false);
    }

    private static void commitChange(KVStore<AetherKey, AetherValue> kvStore,
                                     Option<DhtReplicationChangeValue> expected,
                                     DhtReplicationChangeValue value) {
        var key = AetherKey.DhtReplicationChangeKey.dhtReplicationChangeKey();

        kvStore.process(kvStore.createBatch(List.of(new KVCommand.LeaderTransaction<AetherKey, AetherValue>(key,
                                                                                                           UUID.randomUUID().toString(),
                                                                                                           LEADER,
                                                                                                           List.of(),
                                                                                                           List.of(new KVCommand.Mutation<>(key,
                                                                                                                                            expected.map(AetherValue.class::cast),
                                                                                                                                            Option.some(value)))))));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void commitLeader(KVStore<AetherKey, AetherValue> kvStore) {
        kvStore.process(kvStore.createBatch((List) List.of(new KVCommand.Put<>(LeaderKey.INSTANCE, LEADER))));
    }

    private static ConsistentHashRing<NodeId> ring() {
        var ring = ConsistentHashRing.<NodeId>consistentHashRing();

        ring.addNode(SELF);

        return ring;
    }
}
