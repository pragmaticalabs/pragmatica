// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DhtReplicationChangeKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DhtReplicationReportKey;
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
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;

/// #2545 verifier pins: the report dedupe across a node's lifecycle. Two cores share one store; every submission is
/// applied to it, so the leader's evaluation reads what was actually filed. Change version 3 moves RF 3 -> 5.
class DhtReplicationReportLifecycleTest {
    private static final NodeId LEAD = new NodeId("core-0");
    private static final NodeId MEMBER = new NodeId("core-1");
    private static final SliceCodec CODEC = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());
    private static final DHTConfig OLD = DHTConfig.DEFAULT.withFactors(3, 1).unwrap();
    private static final long CHANGE = 3L;

    private final List<DhtReplicationReportValue> memberPuts = new ArrayList<>();
    private final Set<NodeId> departed = new HashSet<>();
    private final AtomicInteger failNext = new AtomicInteger();
    private LeaderValue leaderValue = LeaderValue.leaderValue(LEAD, 1L);
    private KVStore<AetherKey, AetherValue> kvStore;
    private DhtReplicationSettlement leader;
    private DhtReplicationSettlement member;

    @BeforeEach
    void cluster() {
        kvStore = new KVStore<>(MessageRouter.mutable(), CODEC, CODEC);
        commitLeader(leaderValue);
        leader = settlement(LEAD, new AtomicBoolean(true));
        member = settlement(MEMBER, new AtomicBoolean(false));
        leader.applied(1L);
        member.applied(1L);
    }

    @Test
    void factorChange_memberFilesTheVersionReachingIt_exactlyOnce() {
        member.applied(CHANGE);
        openChange();
        member.onChangeCommitted(Option.none(), committed());
        member.onChangeCommitted(Option.none(), committed());
        tick(member, 5);
        member.applied(4L);
        member.applied(5L);
        tick(member, 5);

        assertThat(memberAppliedPuts()).as("baseline 1, then the version reaching change 3 once; nothing else").containsExactly(1L, CHANGE);
    }

    @Test
    void joinerAfterTheChangeOpened_filesOnce() {
        openChange();
        var joiner = new NodeId("core-9");
        var joinerPuts = new ArrayList<DhtReplicationReportValue>();
        var joined = settlement(joiner, new AtomicBoolean(false), joinerPuts);

        joined.applied(5L);
        tick(joined, 5);
        joined.applied(6L);

        assertThat(joinerPuts).extracting(DhtReplicationReportValue::appliedVersion).containsExactly(5L);
    }

    @Test
    void restartedProcess_refilesFromEmptyMemory_once() {
        openChange();
        member.applied(CHANGE);
        member.onChangeCommitted(Option.none(), committed());
        var restartedPuts = new ArrayList<DhtReplicationReportValue>();
        var restarted = settlement(MEMBER, new AtomicBoolean(false), restartedPuts);

        restarted.applied(CHANGE);
        restarted.reapply();
        tick(restarted, 1);
        var filedOnRestart = restartedPuts.size();
        tick(restarted, 5);

        assertThat(filedOnRestart).as("a fresh process files its state, with nothing in memory to dedupe against").isPositive();
        assertThat(restartedPuts).as("then dedupes").hasSize(filedOnRestart);
        assertThat(storedReport(MEMBER).appliedVersion()).isEqualTo(CHANGE);
    }

    @Test
    void leaderChange_newLeaderAdvancesFromTheStoredReports_withoutARefile() {
        openChange();
        leader.applied(CHANGE);
        member.applied(CHANGE);
        leader.onChangeCommitted(Option.none(), committed());
        member.onChangeCommitted(Option.none(), committed());
        var putsBefore = memberPuts.size();

        leaderValue = LeaderValue.leaderValue(MEMBER, 2L);
        commitLeader(leaderValue);
        var promoted = settlement(MEMBER, new AtomicBoolean(true), memberPuts);

        tick(member, 5);
        promoted.evaluate();

        assertThat(memberPuts).as("no report is filed again for a leader change").hasSize(putsBefore);
        assertThat(committed().stage()).as("the new leader advanced from the reports already in the store")
                                       .isEqualTo(DhtReplicationStage.WRITERS_SWITCHED);
    }

    @Test
    void failedFiling_isNotRecordedAsFiled_andTheTickRetriesIt() {
        openChange();
        failNext.set(1);
        member.applied(CHANGE);

        assertThat(storedApplied(MEMBER)).as("the failed write landed nothing").isEqualTo(1L);
        tick(member, 1);

        assertThat(storedApplied(MEMBER)).as("the tick re-filed the version reaching the open change").isEqualTo(CHANGE);
        tick(member, 5);
        assertThat(memberAppliedPuts()).as("1, the failed 3, its retry; then nothing").containsExactly(1L, CHANGE, CHANGE);
    }

    /// TRIPWIRE for F1, asserting today's behaviour: after the leader drops a departed member's report and the same live
    /// process rejoins, nothing re-files it, so the open change stays APPLYING. When this goes red, F1 is fixed: delete
    /// this test and enable [#reportDroppedForADepartedMember_isFiledAgainOnceTheMemberIsBack].
    @Test
    void reportDroppedForADepartedMember_staysDropped_tripwire() {
        openChange();
        member.applied(CHANGE);
        departed.add(MEMBER);
        leader.evaluate();
        departed.remove(MEMBER);
        leader.applied(CHANGE);
        leader.onChangeCommitted(Option.none(), committed());
        tick(member, 5);
        member.applied(4L);
        leader.evaluate();

        assertThat(kvStore.getTyped(DhtReplicationReportKey.dhtReplicationReportKey(MEMBER), DhtReplicationReportValue.class).isEmpty())
            .as("F1 still open: the rejoined member's report is not re-filed (if this fails, F1 is fixed: see the comment)").isTrue();
        assertThat(committed().stage()).as("F1 still open: the change cannot advance").isEqualTo(DhtReplicationStage.APPLYING);
    }

    /// The leader drops the report of a member its membership view holds Dead (`liveReports`). A live process may rejoin
    /// under its own id (4e3f3f663); it is then expected again, and the open change can advance only once it re-files.
    @Disabled("#2545 verifier finding F1: a dropped report is never re-filed by a live process that rejoins; enable when fixed and delete the tripwire")
    @Test
    void reportDroppedForADepartedMember_isFiledAgainOnceTheMemberIsBack() {
        openChange();
        member.applied(CHANGE);
        assertThat(storedApplied(MEMBER)).as("arming: the member filed the version reaching the change").isEqualTo(CHANGE);
        departed.add(MEMBER);
        leader.evaluate();
        assertThat(kvStore.getTyped(DhtReplicationReportKey.dhtReplicationReportKey(MEMBER), DhtReplicationReportValue.class).isEmpty())
            .as("arming: the leader dropped the departed member's report").isTrue();
        assertThat(committed().stage()).as("arming: the leader itself has not applied the change yet").isEqualTo(DhtReplicationStage.APPLYING);

        departed.remove(MEMBER);
        leader.applied(CHANGE);
        leader.onChangeCommitted(Option.none(), committed());
        tick(member, 5);
        member.applied(4L);
        leader.evaluate();

        assertThat(storedReport(MEMBER).appliedVersion()).as("the member's report is back in the store").isGreaterThanOrEqualTo(CHANGE);
        assertThat(committed().stage()).as("so the change advances").isEqualTo(DhtReplicationStage.WRITERS_SWITCHED);
    }

    // ---- harness ----------------------------------------------------------------------------------------------------
    private DhtReplicationSettlement settlement(NodeId self, AtomicBoolean isLeader) {
        return settlement(self, isLeader, self.equals(MEMBER) ? memberPuts : new ArrayList<>());
    }

    private DhtReplicationSettlement settlement(NodeId self, AtomicBoolean isLeader, List<DhtReplicationReportValue> puts) {
        var dhtNode = DHTNode.dhtNodeAwaitingReplication(self, memoryStorageEngine(), ring(), DHTConfig.DEFAULT, HlcClock.hlcClock(self));

        dhtNode.resolveReplication(OLD, 1L);
        return DhtReplicationSettlement.dhtReplicationSettlement(new DhtReplicationSettlement.Inputs(self,
                                                                                                     true,
                                                                                                     kvStore,
                                                                                                     dhtNode,
                                                                                                     DHTConfig.DEFAULT,
                                                                                                     isLeader::get,
                                                                                                     commands -> submit(commands, puts),
                                                                                                     Set::of,
                                                                                                     departed::contains,
                                                                                                     _ -> {},
                                                                                                     System::currentTimeMillis,
                                                                                                     () -> true));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Promise<List<Object>> submit(List<KVCommand<AetherKey>> commands, List<DhtReplicationReportValue> puts) {
        commands.forEach(command -> {
            if (command instanceof KVCommand.Put<?, ?> put && put.value() instanceof DhtReplicationReportValue report) {
                puts.add(report);
            }
        });
        if (failNext.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            return Causes.cause("injected: submission lost").promise();
        }
        return Promise.success(kvStore.process(kvStore.createBatch((List) commands)));
    }

    private static void tick(DhtReplicationSettlement settlement, int times) {
        for (var i = 0; i < times; i++) {
            settlement.report();
        }
    }

    private List<Long> memberAppliedPuts() {
        return memberPuts.stream().map(DhtReplicationReportValue::appliedVersion).toList();
    }

    private DhtReplicationReportValue storedReport(NodeId node) {
        return kvStore.getTyped(DhtReplicationReportKey.dhtReplicationReportKey(node), DhtReplicationReportValue.class).unwrap();
    }

    private long storedApplied(NodeId node) {
        return storedReport(node).appliedVersion();
    }

    private DhtReplicationChangeValue committed() {
        return kvStore.getTyped(DhtReplicationChangeKey.dhtReplicationChangeKey(), DhtReplicationChangeValue.class).unwrap();
    }

    private void openChange() {
        var key = DhtReplicationChangeKey.dhtReplicationChangeKey();
        var expected = kvStore.getTyped(key, DhtReplicationChangeValue.class).map(AetherValue.class::cast);
        var change = new DhtReplicationChangeValue(CHANGE, 5, 1, 1, 3, 5, DhtReplicationStage.APPLYING, System.currentTimeMillis(), false);

        kvStore.process(kvStore.createBatch(List.of(new KVCommand.LeaderTransaction<AetherKey, AetherValue>(key,
                                                                                                           UUID.randomUUID().toString(),
                                                                                                           leaderValue,
                                                                                                           List.of(),
                                                                                                           List.of(new KVCommand.Mutation<>(key,
                                                                                                                                            expected,
                                                                                                                                            Option.some(change)))))));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void commitLeader(LeaderValue value) {
        kvStore.process(kvStore.createBatch((List) List.of(new KVCommand.Put<>(LeaderKey.INSTANCE, value))));
    }

    private static ConsistentHashRing<NodeId> ring() {
        var ring = ConsistentHashRing.<NodeId>consistentHashRing();

        ring.addNode(LEAD);
        ring.addNode(MEMBER);
        return ring;
    }
}
