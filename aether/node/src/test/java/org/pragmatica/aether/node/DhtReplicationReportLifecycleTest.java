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
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
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
    private static final NodeId WORKER = new NodeId("worker-1");
    private static final long CHANGE = 3L;
    private static final long BACKOFF_MS = 1_000L;

    private final List<DhtReplicationReportValue> memberPuts = new ArrayList<>();
    private final List<Long> memberPutTimes = new ArrayList<>();
    private final Set<NodeId> departed = new HashSet<>();
    private final AtomicInteger failNext = new AtomicInteger();
    private final AtomicLong clock = new AtomicLong(1_000_000L);
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

    /// The leader drops the report of a member its membership view holds Dead (`liveReports`). A live process may rejoin
    /// under its own id (4e3f3f663); it is then expected again, and the open change can advance only once it re-files.
    /// The member sees its report absent from the store while a change is open, and files it again once its backoff (1 s)
    /// has elapsed (#2553).
    @Test
    void departedMembersReportDropped_sameLiveProcessRejoins_refilesWithinBackoff() {
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
        assertThat(kvStore.getTyped(DhtReplicationReportKey.dhtReplicationReportKey(MEMBER), DhtReplicationReportValue.class).isEmpty())
            .as("inside the backoff nothing is re-filed").isTrue();
        clock.addAndGet(BACKOFF_MS);
        tick(member, 5);
        leader.evaluate();

        assertThat(storedReport(MEMBER).appliedVersion()).as("the member's report is back in the store").isGreaterThanOrEqualTo(CHANGE);
        assertThat(committed().stage()).as("so the change advances").isEqualTo(DhtReplicationStage.WRITERS_SWITCHED);
        assertThat(member.refileCount()).isEqualTo(1L);
    }

    /// A ready worker reads its own replica store as a core reads its KV, and re-files a dropped report the same way.
    @Test
    void workerArm_readyReplica_refilesADroppedReport() {
        openChange();
        var workerPuts = new ArrayList<DhtReplicationReportValue>();
        var worker = settlement(WORKER, new AtomicBoolean(false), workerPuts, false, new AtomicBoolean(true));

        worker.applied(CHANGE);
        dropReport(WORKER);
        clock.addAndGet(BACKOFF_MS);
        tick(worker, 5);

        assertThat(workerPuts).as("filed, then filed again after the drop").hasSize(2);
        assertThat(storedApplied(WORKER)).isEqualTo(CHANGE);
        assertThat(worker.refileCount()).isEqualTo(1L);
    }

    /// The readiness guard is for workers: a core reads its own KV, which has no replica to wait for.
    @Test
    void coreArm_replicaNotReady_stillRefiles() {
        openChange();
        var corePuts = new ArrayList<DhtReplicationReportValue>();
        var core = settlement(new NodeId("core-7"), new AtomicBoolean(false), corePuts, true, new AtomicBoolean(false));

        core.applied(CHANGE);
        dropReport(new NodeId("core-7"));
        clock.addAndGet(BACKOFF_MS);
        tick(core, 5);

        assertThat(corePuts).as("filed, then filed again").hasSize(2);
    }

    /// While the replica is not ready (a snapshot half installed, a refused feed) an absent key proves nothing: the
    /// worker goes by what it filed, as before.
    @Test
    void workerArm_replicaNotReady_doesNotRefile() {
        openChange();
        var workerPuts = new ArrayList<DhtReplicationReportValue>();
        var ready = new AtomicBoolean(true);
        var worker = settlement(WORKER, new AtomicBoolean(false), workerPuts, false, ready);

        worker.applied(CHANGE);
        dropReport(WORKER);
        ready.set(false);
        clock.addAndGet(BACKOFF_MS);
        tick(worker, 5);

        assertThat(workerPuts).as("only the first filing").hasSize(1);
        assertThat(worker.refileCount()).isZero();
    }

    /// A member the leader holds Dead while it keeps committing is re-filed at most once per backoff interval, never at the
    /// 100 ms tick: the gaps between its re-files are 1, 2, 4, 8, 16, 32 s, then the 60 s cap. The trickle stops when the
    /// change settles.
    @Test
    void heldDeadMemberStillCommitting_refilesAtMostOncePerBackoff_andStopsWhenChangeSettles() {
        openChange();
        departed.add(MEMBER);
        member.applied(CHANGE);

        for (var elapsed = 0L; elapsed < 400_000L; elapsed += 100) {
            leader.evaluate();
            tick(member, 1);
            clock.addAndGet(100);
        }

        var times = memberPutTimes.subList(2, memberPutTimes.size());
        var gaps = new ArrayList<Long>();

        for (var i = 1; i < times.size(); i++) {
            gaps.add(times.get(i) - times.get(i - 1));
        }

        var expected = List.of(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L);

        assertThat(gaps).as("re-file gaps (a 100 ms tick of slack each)").hasSizeGreaterThanOrEqualTo(expected.size());
        for (var i = 0; i < expected.size(); i++) {
            assertThat(gaps.get(i)).as("gap %d", i).isBetween(expected.get(i), expected.get(i) + 100);
        }
        assertThat(member.refileCount()).as("one per re-file").isEqualTo(times.size());
        assertThat(memberPuts).as("the baseline, the filing that reaches the change, and each re-file").hasSize((int) member.refileCount() + 2);

        settleChange();
        var putsAtSettle = memberPuts.size();

        for (var elapsed = 0L; elapsed < 120_000L; elapsed += 100) {
            leader.evaluate();
            tick(member, 1);
            clock.addAndGet(100);
        }
        assertThat(memberPuts).as("nothing waits, so nothing is re-filed").hasSize(putsAtSettle);
    }

    /// Seeing the report present, once the interval has passed, resets the backoff: the next re-file, once the interval already armed has passed, is
    /// followed by a 1 s interval, not by the doubled one the earlier drops had grown.
    @Test
    void backoffResets_whenTheReportIsSeenPresent() {
        openChange();
        member.applied(CHANGE);
        for (var round = 0; round < 4; round++) {
            dropReport(MEMBER);
            clock.addAndGet(60_000L);
            tick(member, 1);
        }
        assertThat(member.refileCount()).as("each drop was re-filed once its (grown) backoff passed").isEqualTo(4L);
        clock.addAndGet(60_000L);
        tick(member, 1);
        assertThat(kvStore.getTyped(DhtReplicationReportKey.dhtReplicationReportKey(MEMBER), DhtReplicationReportValue.class).isPresent())
            .as("the key is seen present once the interval has passed").isTrue();

        dropReport(MEMBER);
        clock.addAndGet(60_000L);
        tick(member, 1);
        tick(member, 1);
        dropReport(MEMBER);
        clock.addAndGet(BACKOFF_MS);
        tick(member, 1);

        assertThat(member.refileCount()).as("after the reset the interval is 1 s again, not 16 s").isEqualTo(6L);
    }

    /// An absent report costs nothing while no change waits: a change opened later is filed anyway, because its version
    /// exceeds what the member filed.
    @Test
    void absentReport_noOpenChange_doesNotRefile() {
        var before = memberPuts.size();

        dropReport(MEMBER);
        clock.addAndGet(120_000L);
        tick(member, 10);

        assertThat(memberPuts).as("no change is open").hasSize(before);
        assertThat(member.refileCount()).isZero();
    }

    /// #2556 verifier: the leader removes a re-filed report on the commit of the put itself (`onPut(DhtReplicationReportKey)`
    /// -> `evaluate` -> `liveReports`), so a 100 ms tick of the member can fall between the put and the remove and see its
    /// report present. That glimpse is the member's own re-file, not a sighting of a report the leader keeps: if it reset the
    /// backoff, a member held Dead would re-file about once a second instead of about once a minute.
    @Test
    void heldDeadMemberGlimpsingItsOwnRefile_staysWithinTheChurnBound() {
        openChange();
        departed.add(MEMBER);
        member.applied(CHANGE);
        var putsBefore = memberPuts.size();

        for (var elapsed = 0L; elapsed < 600_000L; elapsed += 100) {
            tick(member, 1);
            tick(member, 1);
            leader.evaluate();
            clock.addAndGet(100);
        }

        assertThat(committed().settled()).as("arming: the change stayed open the whole time").isFalse();
        assertThat(member.refileCount()).as("arming: the member did re-file").isPositive();
        assertThat(memberPuts.size() - putsBefore).as("re-files in 10 min held Dead: the 1..32 s ramp, then one per 60 s")
                                                  .isLessThanOrEqualTo(20);
    }

    /// #2556 verifier: a re-file whose write fails is not a sighting. It does not reset the backoff, and the tick retries it
    /// once the interval it armed has passed, never at the tick.
    @Test
    void failedRefile_isRetriedAfterItsInterval_andDoesNotResetTheBackoff() {
        openChange();
        member.applied(CHANGE);
        dropReport(MEMBER);
        clock.addAndGet(BACKOFF_MS);
        failNext.set(1);
        tick(member, 1);
        var afterFailure = memberPuts.size();

        tick(member, 10);
        assertThat(memberPuts).as("inside the interval the failed re-file armed, nothing").hasSize(afterFailure);

        clock.addAndGet(BACKOFF_MS);
        tick(member, 1);
        assertThat(storedApplied(MEMBER)).as("the retry landed").isEqualTo(CHANGE);

        dropReport(MEMBER);
        clock.addAndGet(BACKOFF_MS);
        tick(member, 1);
        assertThat(member.refileCount()).as("the retry armed 2 s: the failed re-file did not reset the interval to 1 s").isEqualTo(2L);
        clock.addAndGet(BACKOFF_MS);
        tick(member, 1);
        assertThat(member.refileCount()).as("then the 2 s interval passed").isEqualTo(3L);
    }

    /// #2556 verifier: while a worker's replica is not ready its store proves nothing, either way. An absent key is not a drop
    /// (no re-file), and a not-ready tick is not a sighting either: it must not reset the backoff the drops have grown.
    @Test
    void workerArm_replicaNotReady_isNotASighting() {
        openChange();
        var workerPuts = new ArrayList<DhtReplicationReportValue>();
        var ready = new AtomicBoolean(true);
        var worker = settlement(WORKER, new AtomicBoolean(false), workerPuts, false, ready);

        worker.applied(CHANGE);
        dropReport(WORKER);
        clock.addAndGet(BACKOFF_MS);
        tick(worker, 1);
        dropReport(WORKER);
        clock.addAndGet(2 * BACKOFF_MS);
        tick(worker, 1);
        assertThat(worker.refileCount()).as("arming: two re-files, the next interval is 4 s").isEqualTo(2L);

        ready.set(false);
        clock.addAndGet(2 * BACKOFF_MS);
        tick(worker, 3);
        ready.set(true);
        dropReport(WORKER);
        tick(worker, 1);
        assertThat(worker.refileCount()).as("the interval armed before the outage has passed: re-filed").isEqualTo(3L);

        dropReport(WORKER);
        clock.addAndGet(BACKOFF_MS);
        tick(worker, 1);
        assertThat(worker.refileCount()).as("the not-ready ticks did not reset the backoff to 1 s").isEqualTo(3L);
    }

    /// #2556 verifier: concurrent callers of `report` (the 100 ms tick, a change notification, a projection) at the moment a
    /// re-file falls due decide it once: one put, one count.
    @Test
    void concurrentReportsAtTheDueMoment_refileOnce() throws InterruptedException {
        openChange();
        var self = new NodeId("core-5");
        var puts = java.util.Collections.synchronizedList(new ArrayList<DhtReplicationReportValue>());
        var node = settlement(self, new AtomicBoolean(false), puts);
        var contenders = 4;
        var rounds = 5_000;
        var deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
        var duplicated = 0;
        var missed = 0;

        node.applied(CHANGE);
        for (var round = 0; round < rounds && System.nanoTime() < deadline; round++) {
            dropReport(self);
            clock.addAndGet(61_000L);
            var before = puts.size();
            var gate = new java.util.concurrent.atomic.AtomicInteger(contenders);
            var threads = new ArrayList<Thread>();

            for (var i = 0; i < contenders; i++) {
                threads.add(Thread.ofPlatform().start(() -> {
                    gate.decrementAndGet();
                    while (gate.get() > 0 && System.nanoTime() < deadline) {
                        Thread.onSpinWait();
                    }
                    node.report();
                }));
            }
            for (var thread : threads) {
                thread.join();
            }
            if (puts.size() - before > 1) {
                duplicated++;
            }
            if (puts.size() == before) {
                missed++;
            }
        }

        assertThat(duplicated).as("rounds where the due re-file was filed more than once").isZero();
        assertThat(missed).as("rounds where the due re-file was not filed at all").isZero();
        assertThat(node.refileCount()).as("one count per put after the first filing").isEqualTo(puts.size() - 1L);
    }

    // ---- harness ----------------------------------------------------------------------------------------------------
    private DhtReplicationSettlement settlement(NodeId self, AtomicBoolean isLeader) {
        return settlement(self, isLeader, self.equals(MEMBER) ? memberPuts : new ArrayList<>());
    }

    private DhtReplicationSettlement settlement(NodeId self, AtomicBoolean isLeader, List<DhtReplicationReportValue> puts) {
        return settlement(self, isLeader, puts, true, new AtomicBoolean(true));
    }

    private DhtReplicationSettlement settlement(NodeId self,
                                                AtomicBoolean isLeader,
                                                List<DhtReplicationReportValue> puts,
                                                boolean replica,
                                                AtomicBoolean replicaReady) {
        var dhtNode = DHTNode.dhtNodeAwaitingReplication(self, memoryStorageEngine(), ring(), DHTConfig.DEFAULT, HlcClock.hlcClock(self));

        dhtNode.resolveReplication(OLD, 1L);
        return DhtReplicationSettlement.dhtReplicationSettlement(new DhtReplicationSettlement.Inputs(self,
                                                                                                     replica,
                                                                                                     kvStore,
                                                                                                     dhtNode,
                                                                                                     DHTConfig.DEFAULT,
                                                                                                     isLeader::get,
                                                                                                     commands -> submit(commands, puts),
                                                                                                     Set::of,
                                                                                                     departed::contains,
                                                                                                     _ -> {},
                                                                                                     clock::get,
                                                                                                     () -> true,
                                                                                                     replicaReady::get));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Promise<List<Object>> submit(List<KVCommand<AetherKey>> commands, List<DhtReplicationReportValue> puts) {
        commands.forEach(command -> {
            if (command instanceof KVCommand.Put<?, ?> put && put.value() instanceof DhtReplicationReportValue report) {
                puts.add(report);
                if (puts == memberPuts) {
                    memberPutTimes.add(clock.get());
                }
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

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void dropReport(NodeId node) {
        kvStore.process(kvStore.createBatch((List) List.of(new KVCommand.Remove<>(DhtReplicationReportKey.dhtReplicationReportKey(node)))));
    }

    private void settleChange() {
        var key = DhtReplicationChangeKey.dhtReplicationChangeKey();
        var current = committed();
        var settled = current.withStage(DhtReplicationStage.SETTLED);

        kvStore.process(kvStore.createBatch(List.of(new KVCommand.LeaderTransaction<AetherKey, AetherValue>(key,
                                                                                                           UUID.randomUUID().toString(),
                                                                                                           leaderValue,
                                                                                                           List.of(),
                                                                                                           List.of(new KVCommand.Mutation<>(key,
                                                                                                                                            Option.some((AetherValue) current),
                                                                                                                                            Option.some(settled)))))));
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
