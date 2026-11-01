// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DhtReplicationChangeValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DhtReplicationReportValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DhtReplicationStage;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.dht.ConsistentHashRing;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.dht.DHTNode;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;

/// #2545: a node files its `DhtReplicationReportKey` when its replication state changes, not on every configuration
/// version it applies. The leader reads `appliedVersion` only as "at or above the version of the open change", so a
/// version bump decides nothing until an open change names a version the bump now covers.
class DhtReplicationReportDedupeTest {
    private static final NodeId SELF = new NodeId("core-1");
    private static final SliceCodec CODEC = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());
    private static final LeaderValue LEADER = LeaderValue.leaderValue(SELF, 1L);
    private static final DHTConfig OLD = DHTConfig.DEFAULT.withFactors(3, 1).unwrap();

    private final List<DhtReplicationReportValue> filed = new ArrayList<>();
    private KVStore<AetherKey, AetherValue> kvStore;
    private DhtReplicationSettlement settlement;

    @BeforeEach
    void settle() {
        kvStore = new KVStore<>(MessageRouter.mutable(), CODEC, CODEC);
        var dhtNode = DHTNode.dhtNodeAwaitingReplication(SELF, memoryStorageEngine(), ring(), DHTConfig.DEFAULT, HlcClock.hlcClock(SELF));

        dhtNode.resolveReplication(OLD, 1L);
        settlement = DhtReplicationSettlement.dhtReplicationSettlement(new DhtReplicationSettlement.Inputs(SELF,
                                                                                                          true,
                                                                                                          kvStore,
                                                                                                          dhtNode,
                                                                                                          DHTConfig.DEFAULT,
                                                                                                          () -> false,
                                                                                                          this::file,
                                                                                                          Set::of,
                                                                                                          _ -> false,
                                                                                                          _ -> {},
                                                                                                          System::currentTimeMillis,
                                                                                                          () -> true));
        commitLeader();
    }

    @Test
    void applied_laterConfigVersionsWithNoOpenChange_fileNothingNew() {
        settlement.applied(1L);
        settlement.applied(2L);
        settlement.applied(3L);

        assertThat(appliedVersions()).as("only the first version is filed; 2 and 3 decide nothing").containsExactly(1L);
    }

    @Test
    void applied_versionBelowTheOpenChange_isNotFiled_theVersionReachingItIs() {
        settlement.applied(1L);
        commitChange(Option.none(), change(3L, DhtReplicationStage.APPLYING));

        settlement.applied(2L);
        settlement.applied(3L);

        assertThat(appliedVersions()).as("2 is below the open change 3 and cannot settle it; 3 can").containsExactly(1L, 3L);
    }

    @Test
    void changeCommittedAfterTheVersionWasApplied_isFiledOnceTheChangeIsKnown() {
        settlement.applied(1L);
        settlement.applied(5L);
        var open = change(4L, DhtReplicationStage.APPLYING);

        commitChange(Option.none(), open);
        settlement.onChangeCommitted(Option.none(), open);

        assertThat(appliedVersions()).as("applied 5 covers the open change 4 that arrived after it").containsExactly(1L, 5L);
    }

    @Test
    void settledChange_doesNotForceAFiling() {
        settlement.applied(1L);
        commitChange(Option.none(), change(3L, DhtReplicationStage.SETTLED));

        settlement.applied(4L);

        assertThat(appliedVersions()).as("a settled change waits for nobody").containsExactly(1L);
    }

    @Test
    void workerProjectionNamingAnOpenChange_isFiledOnceItsVersionIsReached() {
        settlement.applied(1L, DHTNode.NO_CHANGE);
        settlement.applied(2L, 4L);
        settlement.applied(4L, 4L);

        assertThat(appliedVersions()).containsExactly(1L, 4L);
    }

    @Test
    void workerProjectionThatSettledTheChange_stopsWaitingForIt() {
        settlement.applied(1L, DHTNode.NO_CHANGE);
        settlement.applied(2L, 4L);
        settlement.applied(5L, DHTNode.NO_CHANGE);

        assertThat(appliedVersions()).containsExactly(1L);
    }

    @Test
    void readsAsFiled_decidesOnlyByTheOpenChangeAndTheOtherFields() {
        var last = new DhtReplicationReportValue(5L, 3L, true);

        assertThat(DhtReplicationSettlement.readsAsFiled(last, last, 4L)).as("identical").isTrue();
        assertThat(DhtReplicationSettlement.readsAsFiled(last, new DhtReplicationReportValue(9L, 3L, true), DHTNode.NO_CHANGE)).as("no open change").isTrue();
        assertThat(DhtReplicationSettlement.readsAsFiled(last, new DhtReplicationReportValue(9L, 3L, true), 4L)).as("open change already reached by last").isTrue();
        assertThat(DhtReplicationSettlement.readsAsFiled(last, new DhtReplicationReportValue(9L, 3L, true), 12L)).as("open change not reached yet").isTrue();
        assertThat(DhtReplicationSettlement.readsAsFiled(last, new DhtReplicationReportValue(9L, 3L, true), 7L)).as("the bump reaches the open change").isFalse();
        assertThat(DhtReplicationSettlement.readsAsFiled(last, new DhtReplicationReportValue(9L, 3L, true), 9L)).as("the bump reaches it exactly").isFalse();
        assertThat(DhtReplicationSettlement.readsAsFiled(last, new DhtReplicationReportValue(9L, 4L, true), DHTNode.NO_CHANGE)).as("caught-up version moved").isFalse();
        assertThat(DhtReplicationSettlement.readsAsFiled(last, new DhtReplicationReportValue(9L, 3L, false), DHTNode.NO_CHANGE)).as("replica flag moved").isFalse();
        assertThat(DhtReplicationSettlement.readsAsFiled(last, new DhtReplicationReportValue(4L, 3L, true), DHTNode.NO_CHANGE)).as("applied version never reads as going back").isFalse();
    }

    private Promise<List<Object>> file(List<KVCommand<AetherKey>> commands) {
        commands.forEach(command -> {
            if (command instanceof KVCommand.Put<?, ?> put && put.value() instanceof DhtReplicationReportValue report) {
                filed.add(report);
            }
        });

        return Promise.success(List.of());
    }

    private List<Long> appliedVersions() {
        return filed.stream().map(DhtReplicationReportValue::appliedVersion).toList();
    }

    private static DhtReplicationChangeValue change(long version, DhtReplicationStage stage) {
        return new DhtReplicationChangeValue(version, 3, 2, 2, 2, 3, stage, 0L, false);
    }

    private void commitChange(Option<DhtReplicationChangeValue> expected, DhtReplicationChangeValue value) {
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
    private void commitLeader() {
        kvStore.process(kvStore.createBatch((List) List.of(new KVCommand.Put<>(LeaderKey.INSTANCE, LEADER))));
    }

    private static ConsistentHashRing<NodeId> ring() {
        var ring = ConsistentHashRing.<NodeId>consistentHashRing();

        ring.addNode(SELF);

        return ring;
    }
}
