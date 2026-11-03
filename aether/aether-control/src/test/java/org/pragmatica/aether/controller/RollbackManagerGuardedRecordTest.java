// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.controller;

import io.netty.buffer.ByteBuf;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.config.RollbackConfig;
import org.pragmatica.aether.controller.RollbackManagerOverridePreservationTest.AlwaysLeaderManager;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.PreviousVersionKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.PreviousVersionValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/// #2613: the rollback record is written as a guarded leader transaction derived from the committed target and
/// record, never as a plain put computed from a read that a rollback can overtake.
///
/// Every test drives the real [RollbackManager] over a real [KVStore] through a [HeldClusterNode] that CAPTURES each
/// submitted command list and applies it to the store only when the test says. The store's router has no route to the
/// manager, so notifications reach it only by hand (no second delivery from the store itself).
@Timeout(60)
class RollbackManagerGuardedRecordTest {
    private static final NodeId SELF = NodeId.nodeId("node-1").unwrap();
    private static final NodeId OTHER = NodeId.nodeId("node-2").unwrap();
    private static final ArtifactBase BASE = Artifact.artifact("org.test:my-slice:1.0.0").unwrap().base();
    private static final PreviousVersionKey RECORD_KEY = PreviousVersionKey.previousVersionKey(BASE);
    private static final SliceTargetKey TARGET_KEY = SliceTargetKey.sliceTargetKey(BASE);
    private static final LeaderValue LEADER = new LeaderValue(SELF, 1);
    private static final Version X = Version.version("0.9.0").unwrap();
    private static final Version A = Version.version("1.0.0").unwrap();
    private static final Version B = Version.version("2.0.0").unwrap();
    private static final Version C = Version.version("3.0.0").unwrap();
    private static final Version D = Version.version("4.0.0").unwrap();
    private static final RollbackConfig CONFIG = RollbackConfig.rollbackConfig();

    private KVStore<AetherKey, AetherValue> store;
    private HeldClusterNode held;
    private SliceTargetValue targetA;
    private PreviousVersionValue recordZero;
    private RollbackManager manager;

    @BeforeEach
    void setUp() {
        store = new KVStore<>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
        held = new HeldClusterNode(SELF, store);
        targetA = SliceTargetValue.sliceTargetValue(A, 3);
        recordZero = PreviousVersionValue.previousVersionValue(BASE, X, A);

        RollbackManagerOverridePreservationTest.seedCommittedLeader(store, SELF, 1);
        commit(TARGET_KEY, targetA);
        commit(RECORD_KEY, recordZero);
    }

    /// The case the issue names. Step 4 is the capture guard: with no captured write the test fails THERE, so the survival
    /// assertion below can never pass vacuously.
    @Test
    void onSliceTargetPut_keepsCommittedRollback_whenRollbackCommitsBetweenReadAndPut() {
        startManager();
        var targetB = commit(TARGET_KEY, targetA.withVersion(B));

        manager.onSliceTargetPut(put(targetB, targetA));

        assertThat(held.captured()).as("arming: the version change submitted exactly one write").hasSize(1);
        assertThat(held.recordKeysWrittenBy(0)).as("arming: that write is the record's").containsExactly(RECORD_KEY);

        var rolledBack = recordZero.withRollback(B, A, System.currentTimeMillis());

        assertThat(rollback(recordZero, rolledBack, targetB)).as("arming: the rollback commits first").isTrue();
        held.applyToStore(0);
        held.applyAllPending();

        assertThat(record()).isEqualTo(rolledBack);
        assertThat(record().rollbackCount()).isEqualTo(1);
        assertThat(record().failedVersions()).contains(B);
    }

    /// R4.1, retry arm: a SCALE change refuses the (A, B) write through the target witness, and the refusal's own retry
    /// rewrites (A, B) on the fresh target.
    @Test
    void onSliceTargetPut_retriesRefusedWrite_whenNextTargetWriteIsNotAVersionChange() {
        startManager();
        var targetB = commit(TARGET_KEY, targetA.withVersion(B));

        manager.onSliceTargetPut(put(targetB, targetA));
        assertThat(held.captured()).as("arming").hasSize(1);
        commit(TARGET_KEY, targetB.withInstances(targetB.targetInstances() + 1));
        held.applyToStore(0);
        held.awaitCaptured(2);
        held.applyToStore(1);
        held.settle();

        assertRecord(A, B);
    }

    /// R4.1, re-derive arm: the refusal's retry is DISCARDED, and the scale change's own notification re-derives the
    /// change from the committed record (the mirror says B already; the early exit is the committed record's).
    @Test
    void onSliceTargetPut_rederivesLostVersionChange_whenScaleChangeNotificationArrives() {
        startManager();
        var targetB = commit(TARGET_KEY, targetA.withVersion(B));

        manager.onSliceTargetPut(put(targetB, targetA));
        assertThat(held.captured()).as("arming").hasSize(1);
        var scaled = commit(TARGET_KEY, targetB.withInstances(targetB.targetInstances() + 1));
        held.applyToStore(0);
        held.awaitCaptured(2);
        manager.onSliceTargetPut(put(scaled, targetB));
        held.awaitCaptured(3);
        held.applyToStore(2);
        held.settle();

        assertRecord(A, B);
    }

    /// R5.1: a later VERSION change refuses the (A, B) write, and the retry writes NOTHING (the later notification derives
    /// from its own `oldValue`): the record is unchanged until N2 records (B, C).
    @Test
    void onSliceTargetPut_recordsLaterChangeFromItsOwnOldValue_whenVersionChangeRefusedTheWrite() {
        startManager();
        var targetB = commit(TARGET_KEY, targetA.withVersion(B));

        manager.onSliceTargetPut(put(targetB, targetA));
        assertThat(held.captured()).as("arming").hasSize(1);
        var targetC = commit(TARGET_KEY, targetB.withVersion(C));
        held.applyToStore(0);
        held.settle();

        assertThat(held.captured()).as("the retry of a refused version change writes nothing").hasSize(1);
        assertThat(record()).isEqualTo(recordZero);

        manager.onSliceTargetPut(put(targetC, targetB));
        held.awaitCaptured(2);
        held.applyToStore(1);
        held.settle();

        assertRecord(B, C);
    }

    /// R5.1, two-change variant: B -> C -> D committed before the refusal. N2 (value C, HEAD D) is a read-ahead and writes
    /// nothing; N3 records (C, D) from its own `oldValue` - not (B, D).
    @Test
    void onSliceTargetPut_writesNothingForReadAheadNotification_whenTwoVersionChangesFollowARefusal() {
        startManager();
        var targetB = commit(TARGET_KEY, targetA.withVersion(B));

        manager.onSliceTargetPut(put(targetB, targetA));
        assertThat(held.captured()).as("arming").hasSize(1);
        var targetC = commit(TARGET_KEY, targetB.withVersion(C));
        var targetD = commit(TARGET_KEY, targetC.withVersion(D));
        held.applyToStore(0);
        held.settle();
        manager.onSliceTargetPut(put(targetC, targetB));
        held.settle();

        assertThat(held.captured()).as("a read-ahead notification (value C, HEAD D) writes nothing").hasSize(1);

        manager.onSliceTargetPut(put(targetD, targetC));
        held.awaitCaptured(2);
        held.applyToStore(1);
        held.settle();

        assertRecord(C, D);
    }

    /// Leader change: the old leader's write is refused once the committed leader moved, and the new leader's activation
    /// records the version change no leader recorded.
    @Test
    void activate_recordsUnrecordedVersionChange_whenOldLeaderWriteWasRefusedByLeaderChange() {
        startManager();
        var targetB = commit(TARGET_KEY, targetA.withVersion(B));

        manager.onSliceTargetPut(put(targetB, targetA));
        assertThat(held.captured()).as("arming").hasSize(1);
        RollbackManagerOverridePreservationTest.seedCommittedLeader(store, OTHER, 2);
        held.applyToStore(0);
        held.settle();

        assertThat(held.captured()).as("the old leader neither lands nor retries its write").hasSize(1);
        assertThat(record()).isEqualTo(recordZero);

        var newLeaderNode = new HeldClusterNode(OTHER, store);
        var newLeader = RollbackManager.rollbackManager(OTHER, CONFIG, newLeaderNode, store, new AlwaysLeaderManager(OTHER));

        newLeader.activate();
        newLeaderNode.awaitCaptured(1);
        newLeaderNode.applyToStore(0);
        newLeaderNode.settle();

        assertRecord(A, B);
    }

    /// First deployment after a leader change: the artifact was deployed once, so no record exists and the new leader's
    /// mirror does not hold it. The gate is the notification's `oldValue` and the committed record, never the mirror.
    @Test
    void onSliceTargetPut_recordsFirstVersionChange_whenNoRecordExistedAtActivation() {
        commitRemoval(RECORD_KEY);
        startManager();

        assertThat(held.captured()).as("activation with a target and no record writes nothing").isEmpty();

        var targetB = commit(TARGET_KEY, targetA.withVersion(B));

        manager.onSliceTargetPut(put(targetB, targetA));
        held.awaitCaptured(1);
        held.applyToStore(0);
        held.settle();

        assertRecord(A, B);
        assertThat(record().rollbackCount()).isZero();
    }

    /// Item 6 refusal retry: `resetRollbackCount` commits between the version change's read and its write; the retry records
    /// the version change on top of the reset counters.
    @Test
    void onSliceTargetPut_recordsVersionChangeOnTopOfResetCounters_whenResetCommittedBeforeTheWrite() {
        var withCounters = new PreviousVersionValue(BASE, X, A, System.currentTimeMillis(), 1, 5L, List.of(X));

        commit(RECORD_KEY, withCounters);
        startManager();
        var targetB = commit(TARGET_KEY, targetA.withVersion(B));

        manager.onSliceTargetPut(put(targetB, targetA));
        manager.resetRollbackCount(BASE);
        assertThat(held.captured()).as("arming: the version change and the reset each submitted one write").hasSize(2);
        held.applyToStore(1);
        held.applyToStore(0);
        held.awaitCaptured(3);
        held.applyToStore(2);
        held.settle();

        assertRecord(A, B);
        assertThat(record().rollbackCount()).as("the reset survives the version change").isZero();
        assertThat(record().failedVersions()).as("the failed-version record is kept").containsExactly(X);
    }

    /// Item 6: the reset is a record-only guarded write; a record writer that commits between its read and its write
    /// refuses it, and the retry resets the record that is there.
    @Test
    void resetRollbackCount_resetsFreshRecord_whenRecordChangedBetweenReadAndWrite() {
        var withCounters = new PreviousVersionValue(BASE, X, A, System.currentTimeMillis(), 1, 5L, List.of(X));

        commit(RECORD_KEY, withCounters);
        startManager();
        manager.resetRollbackCount(BASE);
        assertThat(held.captured()).as("arming").hasSize(1);
        commit(RECORD_KEY, withCounters.withRollback(A, X, 9L));
        held.applyToStore(0);
        held.awaitCaptured(2);
        held.applyToStore(1);
        held.settle();

        assertThat(record().rollbackCount()).isZero();
        assertThat(record().lastRollbackAt()).isZero();
        assertThat(record().failedVersions()).as("the concurrent writer's failed version is kept").contains(X, A);
    }

    /// Item 6: a reset by a node that is not the committed leader cannot land, so it submits nothing.
    @Test
    void resetRollbackCount_submitsNothing_whenNodeIsNotTheCommittedLeader() {
        RollbackManagerOverridePreservationTest.seedCommittedLeader(store, OTHER, 2);
        startManager();

        manager.resetRollbackCount(BASE);
        held.settle();

        assertThat(held.captured()).isEmpty();
    }

    /// Verifier v2622: a REVERT (B -> A) commits after the deploy's (A, B) write was submitted and before it lands. The revert's
    /// notification is at HEAD and names `oldValue` B, so the record must become (B, A) - the previous of A is B. A version-only
    /// "already recorded" check sees the stale (X, A) record as current and writes nothing, and nothing later re-derives it.
    @Test
    void onSliceTargetPut_recordsRevertedVersion_whenRevertCommitsBeforeTheDeployWriteLands() {
        startManager();
        var targetB = commit(TARGET_KEY, targetA.withVersion(B));

        manager.onSliceTargetPut(put(targetB, targetA));
        assertThat(held.captured()).as("arming: the deploy submitted one write").hasSize(1);
        var reverted = commit(TARGET_KEY, targetB.withVersion(A));

        manager.onSliceTargetPut(put(reverted, targetB));
        held.applyAllPending();
        held.applyAllPending();

        assertRecord(B, A);
    }

    /// Verifier v2622, retry arm of the revert: the revert's own (B, A) write is refused by a scale change, and its retry must
    /// rewrite (B, A) although the stale record (X, A) already names version A.
    @Test
    void onSliceTargetPut_retriesRevertWrite_whenScaleChangeRefusesIt() {
        startManager();
        var targetB = commit(TARGET_KEY, targetA.withVersion(B));

        manager.onSliceTargetPut(put(targetB, targetA));
        var reverted = commit(TARGET_KEY, targetB.withVersion(A));

        manager.onSliceTargetPut(put(reverted, targetB));
        held.awaitCaptured(2);
        held.applyToStore(0);
        held.settle();
        commit(TARGET_KEY, reverted.withInstances(reverted.targetInstances() + 1));
        held.applyToStore(1);
        held.settle();
        held.applyAllPending();
        held.applyAllPending();

        assertRecord(B, A);
    }

    /// Verifier v2622: B -> C -> B commits before the deploy's (A, B) write lands. N3 records (C, B); the stale (A, B) write is
    /// refused, and its retry must not overwrite the newer (C, B) although the target names B again (R6.1's ABA case).
    @Test
    void onSliceTargetPut_keepsNewerRecord_whenStaleRetryFindsTheSameVersionAgain() {
        startManager();
        var targetB = commit(TARGET_KEY, targetA.withVersion(B));

        manager.onSliceTargetPut(put(targetB, targetA));
        var targetC = commit(TARGET_KEY, targetB.withVersion(C));
        var targetB2 = commit(TARGET_KEY, targetC.withVersion(B));

        manager.onSliceTargetPut(put(targetC, targetB));
        manager.onSliceTargetPut(put(targetB2, targetC));
        held.awaitCaptured(2);
        held.applyToStore(1);
        assertRecord(C, B);
        held.applyToStore(0);
        held.settle();
        held.applyAllPending();
        held.applyAllPending();

        assertRecord(C, B);
    }

    private void startManager() {
        manager = RollbackManager.rollbackManager(SELF, CONFIG, held, store, new AlwaysLeaderManager(SELF));
        manager.activate();
    }

    private void assertRecord(Version previous, Version current) {
        assertThat(record().previousVersion()).as("previous version").isEqualTo(previous);
        assertThat(record().currentVersion()).as("current version").isEqualTo(current);
    }

    private PreviousVersionValue record() {
        return store.getTyped(RECORD_KEY, PreviousVersionValue.class).unwrap();
    }

    private <V extends AetherValue> V commit(AetherKey key, V value) {
        store.process(store.createBatch(List.<KVCommand<AetherKey>>of(new KVCommand.Put<AetherKey, AetherValue>(key, value))));

        return value;
    }

    private void commitRemoval(AetherKey key) {
        store.process(store.createBatch(List.<KVCommand<AetherKey>>of(new KVCommand.Remove<AetherKey>(key))));
    }

    /// A rollback exactly as `RollbackManager.rollbackTransaction` builds it: record and target in one leader transaction.
    private boolean rollback(PreviousVersionValue before, PreviousVersionValue after, SliceTargetValue target) {
        var mutations = List.of(new KVCommand.Mutation<AetherKey, AetherValue>(RECORD_KEY,
                                                                               Option.<AetherValue>some(before),
                                                                               Option.<AetherValue>some(after)),
                                new KVCommand.Mutation<AetherKey, AetherValue>(TARGET_KEY,
                                                                               Option.<AetherValue>some(target),
                                                                               Option.<AetherValue>some(target.withVersion(A))));
        var transaction = new KVCommand.LeaderTransaction<AetherKey, AetherValue>(TARGET_KEY,
                                                                                  "rollback-1",
                                                                                  LEADER,
                                                                                  List.of(),
                                                                                  mutations);
        List<Object> results = store.process(store.createBatch(List.<KVCommand<AetherKey>>of(transaction)));

        return results.stream()
                      .anyMatch(result -> result instanceof KVCommand.TransactionResult outcome && outcome.accepted());
    }

    private static ValuePut<SliceTargetKey, SliceTargetValue> put(SliceTargetValue value, SliceTargetValue replaced) {
        return new ValuePut<>(new KVCommand.Put<>(TARGET_KEY, value), Option.some(replaced));
    }

    private static Serializer stubSerializer() {
        return new Serializer() {
            @Override
            public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    private static Deserializer stubDeserializer() {
        return new Deserializer() {
            @Override
            public <T> T read(ByteBuf byteBuf) {
                return null;
            }
        };
    }

    /// Captures every submitted command list and answers it only when the test applies it to the store. A resolved
    /// promise runs its handlers asynchronously, so [#settle] waits for the captured count to stop changing.
    static final class HeldClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        record Captured(List<KVCommand<AetherKey>> commands, Promise<List<Object>> promise) {}

        private final NodeId self;
        private final KVStore<AetherKey, AetherValue> store;
        private final List<Captured> captured = new CopyOnWriteArrayList<>();
        private final List<Integer> applied = new CopyOnWriteArrayList<>();

        HeldClusterNode(NodeId self, KVStore<AetherKey, AetherValue> store) {
            this.self = self;
            this.store = store;
        }

        List<Captured> captured() {
            return captured;
        }

        /// The record keys the captured list writes, whether as a plain put (rc5) or as a leader transaction's mutations.
        List<AetherKey> recordKeysWrittenBy(int index) {
            var keys = new ArrayList<AetherKey>();

            captured.get(index).commands().forEach(command -> keys.addAll(writtenKeys(command)));

            return keys.stream().filter(PreviousVersionKey.class::isInstance).toList();
        }

        private static List<AetherKey> writtenKeys(KVCommand<AetherKey> command) {
            return switch (command) {
                case KVCommand.Put<?, ?> put -> List.of((AetherKey) put.key());
                case KVCommand.LeaderTransaction<?, ?> transaction -> transaction.mutations()
                                                                                 .stream()
                                                                                 .map(mutation -> (AetherKey) mutation.key())
                                                                                 .toList();
                default -> List.of();
            };
        }

        void applyToStore(int index) {
            var entry = captured.get(index);
            List<Object> results = store.process(store.createBatch(entry.commands()));

            applied.add(index);
            entry.promise().succeed(results);
        }

        void applyAllPending() {
            settle();
            for (var index = 0; index < captured.size(); index++) {
                if (!applied.contains(index)) {
                    applyToStore(index);
                    settle();
                }
            }
        }

        void awaitCaptured(int count) {
            var deadline = System.nanoTime() + 5_000_000_000L;

            while (captured.size() < count && System.nanoTime() < deadline) {
                pause(10);
            }
            assertThat(captured).as("captured command lists after waiting for %s", count).hasSizeGreaterThanOrEqualTo(count);
        }

        /// Returns once no new command list has been captured for 300 ms.
        void settle() {
            var seen = -1;

            while (seen != captured.size()) {
                seen = captured.size();
                pause(300);
            }
        }

        private static void pause(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public NodeId self() {
            return self;
        }

        @Override
        public TopologyManager topologyManager() {
            return null;
        }

        @Override
        public Promise<Unit> start() {
            return Promise.unitPromise();
        }

        @Override
        public Promise<Unit> stop() {
            return Promise.unitPromise();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
            var promise = Promise.<List<Object>>promise();

            captured.add(new Captured(commands, promise));

            return (Promise<List<R>>) (Promise<?>) promise;
        }
    }
}
