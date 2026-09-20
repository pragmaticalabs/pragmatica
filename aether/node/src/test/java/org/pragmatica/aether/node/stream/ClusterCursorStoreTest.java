// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.stream;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import org.pragmatica.aether.node.stream.ConsumerAssignmentWriter.CommittedAssignments;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ConsumerAssignmentKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamCursorCheckpointKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ConsumerAssignmentValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ConsumerAssignmentValue.AssignmentToken;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamCursorCheckpointValue;
import org.pragmatica.aether.stream.segment.ConsumerCursorStore;
import org.pragmatica.aether.stream.segment.ConsumerCursorStore.CommitOutcome;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;

/// Cluster-visible cursor composition (#488).
///
/// The resume offset is `max(local, cluster)` and the choice is load-bearing in BOTH directions, so
/// each direction is pinned separately:
///   - cluster ahead of local  → the failover case; another node consumed further and this node must
///     not replay from its own stale (or absent) local cursor.
///   - local ahead of cluster  → the same-node-restart case; the local cursor is newer than the last
///     consensus checkpoint, and taking the cluster value would silently redeliver processed events.
class ClusterCursorStoreTest {
    private static final String GROUP = "orders-onOrderEvent";
    private static final String STREAM = "orders";
    private static final int PARTITION = 2;
    private static final NodeId SELF = NodeId.nodeId("node-1").unwrap();
    private static final NodeId PEER = NodeId.nodeId("node-2").unwrap();
    private static final Epoch EPOCH = Epoch.epoch(1L, 1L);
    private static final Epoch NEXT_EPOCH = Epoch.epoch(1L, 2L);
    private static final AssignmentToken SELF_TOKEN = AssignmentToken.assignmentToken(SELF, EPOCH);
    private static final AssignmentToken PEER_TOKEN = AssignmentToken.assignmentToken(PEER, NEXT_EPOCH);
    private static final ConsumerAssignmentKey ASSIGNMENT_KEY = ConsumerAssignmentKey.consumerAssignmentKey(STREAM, PARTITION, GROUP);
    private static final StreamCursorCheckpointKey CHECKPOINT_KEY = StreamCursorCheckpointKey.streamCursorCheckpointKey(STREAM, PARTITION, GROUP);

    @Nested
    class ResumeOffsetSelection {

        @Test
        void resumeOffset_prefersCluster_whenClusterIsAhead() {
            assertThat(ClusterCursorStore.resumeOffset(Option.some(100L), Option.some(500L)))
                    .isEqualTo(Option.some(500L));
        }

        @Test
        void resumeOffset_prefersLocal_whenLocalIsAhead() {
            assertThat(ClusterCursorStore.resumeOffset(Option.some(900L), Option.some(500L)))
                    .describedAs("a local cursor newer than the last checkpoint must not be regressed")
                    .isEqualTo(Option.some(900L));
        }

        @Test
        void resumeOffset_usesLocal_whenClusterAbsent() {
            assertThat(ClusterCursorStore.resumeOffset(Option.some(42L), Option.none()))
                    .isEqualTo(Option.some(42L));
        }

        @Test
        void resumeOffset_usesCluster_whenLocalAbsent() {
            assertThat(ClusterCursorStore.resumeOffset(Option.none(), Option.some(42L)))
                    .describedAs("the node that takes over ownership has no local cursor at all")
                    .isEqualTo(Option.some(42L));
        }

        @Test
        void resumeOffset_isEmpty_whenNeitherRecorded() {
            assertThat(ClusterCursorStore.resumeOffset(Option.none(), Option.none()))
                    .describedAs("no cursor anywhere means create-from-earliest, not start-at-head")
                    .isEqualTo(Option.none());
        }

        @Test
        void resumeOffset_isStable_whenBothAgree() {
            assertThat(ClusterCursorStore.resumeOffset(Option.some(7L), Option.some(7L)))
                    .isEqualTo(Option.some(7L));
        }
    }

    @Nested
    class CommitFanout {

        @Test
        void commit_writesLocalStore_andProposesConsensusCheckpoint() {
            var localOffset = new AtomicReference<Long>();
            var proposed = new AtomicReference<KVCommand<AetherKey>>();
            var store = storeWith(recordingLocal(localOffset), _ -> Option.none(), commands -> capture(proposed, commands));

            store.commit(GROUP, STREAM, PARTITION, 314L, EPOCH).await();

            assertThat(localOffset.get()).isEqualTo(314L);
            assertThat(proposed.get())
                    .describedAs("the checkpoint must reach consensus, otherwise failover replays from 0")
                    .isInstanceOf(KVCommand.Put.class);
        }

        /// #1271: the checkpoint carries the token the applier's guard checks — this node at the epoch the
        /// consumer was admitted under.
        @Test
        void commit_writesCheckpointValue_carryingOffsetAndAssignmentToken() {
            var proposed = new AtomicReference<KVCommand<AetherKey>>();
            var store = storeWith(recordingLocal(new AtomicReference<>()), _ -> Option.none(), commands -> capture(proposed, commands));

            store.commit(GROUP, STREAM, PARTITION, 99L, EPOCH).await();

            assertThat(proposed.get()).isInstanceOfSatisfying(KVCommand.Put.class,
                                                              put -> assertThat((StreamCursorCheckpointValue) put.value())
                                                                      .extracting(StreamCursorCheckpointValue::committedOffset,
                                                                                  StreamCursorCheckpointValue::token)
                                                                      .containsExactly(99L, SELF_TOKEN));
        }

        @Test
        void fetch_composesLocalAndClusterCursors() {
            var store = storeWith(fixedLocal(Option.some(10L)), _ -> Option.some(checkpoint(70L, SELF_TOKEN)), _ -> Promise.unitPromise());

            store.fetch(GROUP, STREAM, PARTITION, EPOCH)
                 .await()
                 .onFailure(cause -> Assertions.fail(cause.message()))
                 .onSuccess(offset -> assertThat(offset).isEqualTo(Option.some(70L)));
        }

        /// #1271: under an assignment, the local half of `max(local, cluster)` counts only when written
        /// under THAT assignment's epoch — a local cursor from an earlier tenure can be ahead of what the
        /// successor committed, and resuming from it would skip events.
        @Test
        void fetch_underAnAssignment_ignoresTheLocalCursorFromAnotherTenure() {
            var store = storeWith(tenureAwareLocal(900L), _ -> Option.some(checkpoint(70L, SELF_TOKEN)), _ -> Promise.unitPromise());

            store.fetch(GROUP, STREAM, PARTITION, EPOCH)
                 .await()
                 .onFailure(cause -> Assertions.fail(cause.message()))
                 .onSuccess(offset -> assertThat(offset).describedAs("the committed cursor, not the stale local 900")
                                                        .isEqualTo(Option.some(70L)));
        }

        @Test
        void commit_stillSucceedsLocally_whenConsensusProposalFails() {
            var localOffset = new AtomicReference<Long>();
            var store = storeWith(recordingLocal(localOffset), _ -> Option.none(), _ -> CheckpointRejected.INSTANCE.promise());

            store.commit(GROUP, STREAM, PARTITION, 5L, EPOCH)
                 .await()
                 .onFailure(cause -> Assertions.fail(cause.message()));

            assertThat(localOffset.get())
                    .describedAs("a consensus hiccup degrades the failover bound but must not fail the local checkpoint")
                    .isEqualTo(5L);
        }

        /// #654 round 2 / #1239: `commit(...)`'s own Promise settles successfully even though the
        /// consensus publish failed — its OUTCOME is how the runtime still learns that, since `onFailure`
        /// on `commit(...)` never fires for this case.
        @Test
        void commit_reportsLocalOnly_carryingTheCause_whenConsensusProposalFails() {
            var store = storeWith(recordingLocal(new AtomicReference<>()), _ -> Option.none(), _ -> CheckpointRejected.INSTANCE.promise());

            assertThat(store.commit(GROUP, STREAM, PARTITION, 5L, EPOCH).await())
                    .describedAs("the recovered failure travels on this commit's own outcome")
                    .isEqualTo(Result.success(CommitOutcome.localOnly(CheckpointRejected.INSTANCE)));
        }

        @Test
        void commit_reportsPersisted_whenTheCommittedCheckpointCarriesOurToken() {
            var store = storeWith(recordingLocal(new AtomicReference<>()),
                                  _ -> Option.some(checkpoint(5L, SELF_TOKEN)),
                                  _ -> Promise.unitPromise());

            assertThat(store.commit(GROUP, STREAM, PARTITION, 5L, EPOCH).await())
                    .isEqualTo(Result.success(CommitOutcome.persisted()));
        }

        /// #1239 (review claim P2): two overlapping commits for ONE key. A's publish fails LATE, B's
        /// succeeds EARLY. The per-key side map this replaces let B report A's cause (misattribution) or
        /// let B's success clear A's entry before A read it (loss). Each commit must carry only its own
        /// outcome.
        @Test
        void overlappingCommits_forOneKey_eachReportOnlyTheirOwnOutcome() {
            Promise<Unit> publishA = Promise.promise();
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            var store = storeWith(recordingLocal(new AtomicReference<>()),
                                  _ -> Option.some(checkpoint(6L, SELF_TOKEN)),
                                  _ -> calls.incrementAndGet() == 1 ? publishA : Promise.unitPromise());

            var commitA = store.commit(GROUP, STREAM, PARTITION, 5L, EPOCH);
            var commitB = store.commit(GROUP, STREAM, PARTITION, 6L, EPOCH);

            assertThat(commitB.await()).describedAs("B succeeded early and reports its own success")
                                      .isEqualTo(Result.success(CommitOutcome.persisted()));
            publishA.fail(CheckpointRejected.INSTANCE);
            assertThat(commitA.await()).describedAs("A failed late and reports its own cause, not lost to B")
                                      .isEqualTo(Result.success(CommitOutcome.localOnly(CheckpointRejected.INSTANCE)));
        }
    }

    /// #1271 against the REAL applier fence: a `KVStore` whose `staleWrite` includes the assignment guard.
    @Nested
    class AssignmentFence {
        private KVStore<AetherKey, AetherValue> kv;

        @BeforeEach
        void setUp() {
            kv = new KVStore<>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
        }

        private ConsumerCursorStore storeFor(NodeId node, boolean forwarding, List<KVCommand<AetherKey>> sent) {
            return ClusterCursorStore.clusterCursorStore(recordingLocal(new AtomicReference<>()),
                                                         node,
                                                         key -> kv.getTyped(key, StreamCursorCheckpointValue.class),
                                                         assignmentsIn(kv),
                                                         commands -> applyAndRecord(commands, sent),
                                                         () -> forwarding);
        }

        private Promise<Unit> applyAndRecord(List<KVCommand<AetherKey>> commands, List<KVCommand<AetherKey>> sent) {
            sent.addAll(commands);
            kv.process(kv.createBatch(commands));

            return Promise.unitPromise();
        }

        private void assign(NodeId assignee, Epoch epoch) {
            kv.process(kv.createBatch(List.of(new KVCommand.Put<AetherKey, AetherValue>(ASSIGNMENT_KEY,
                                                                                       ConsumerAssignmentValue.consumerAssignmentValue(assignee,
                                                                                                                                       epoch,
                                                                                                                                       epoch.localCounter(),
                                                                                                                                       HlcTimestamp.ZERO)))));
        }

        private Option<StreamCursorCheckpointValue> committed() {
            return kv.getTyped(CHECKPOINT_KEY, StreamCursorCheckpointValue.class);
        }

        @Test
        void commit_isPersisted_whenThisNodeIsTheCommittedAssignee() {
            assign(SELF, EPOCH);

            assertThat(storeFor(SELF, false, new ArrayList<>()).commit(GROUP, STREAM, PARTITION, 100L, EPOCH).await())
                    .isEqualTo(Result.success(CommitOutcome.persisted()));
            assertThat(committed().map(StreamCursorCheckpointValue::committedOffset)).isEqualTo(Option.some(100L));
        }

        /// The acceptance property: after the assignment moves, the deposed node's commits are refused —
        /// whether they would advance or regress the cursor — and it learns so from the outcome.
        @Test
        void commit_isFenced_andNeverRegressesTheCursor_whenTheAssignmentMovedAway() {
            var deposed = storeFor(SELF, false, new ArrayList<>());
            var successor = storeFor(PEER, false, new ArrayList<>());

            assign(SELF, EPOCH);
            deposed.commit(GROUP, STREAM, PARTITION, 100L, EPOCH).await();
            assign(PEER, NEXT_EPOCH);
            successor.commit(GROUP, STREAM, PARTITION, 100L, NEXT_EPOCH).await();

            assertThat(deposed.commit(GROUP, STREAM, PARTITION, 40L, EPOCH).await()).describedAs("a regressing deposed commit")
                                                                                      .isEqualTo(Result.success(CommitOutcome.fenced("checkpoint " + CHECKPOINT_KEY
                                                                                                                                     + " refused for " + SELF
                                                                                                                                     + " at " + EPOCH)));
            assertThat(deposed.commit(GROUP, STREAM, PARTITION, 150L, EPOCH).await()
                              .map(outcome -> outcome instanceof CommitOutcome.Fenced)).describedAs("an advancing deposed commit")
                                                                                         .isEqualTo(Result.success(true));
            assertThat(committed().map(checkpoint -> List.<Object>of(checkpoint.committedOffset(), checkpoint.token())))
                    .describedAs("the successor's checkpoint stands")
                    .isEqualTo(Option.some(List.<Object>of(100L, PEER_TOKEN)));
        }

        /// Refused BEFORE the successor has written anything — the window a per-key epoch fence leaves open.
        @Test
        void commit_isFenced_beforeTheSuccessorsFirstCheckpoint() {
            var deposed = storeFor(SELF, false, new ArrayList<>());

            assign(SELF, EPOCH);
            deposed.commit(GROUP, STREAM, PARTITION, 100L, EPOCH).await();
            assign(PEER, NEXT_EPOCH);

            assertThat(deposed.commit(GROUP, STREAM, PARTITION, 150L, EPOCH).await()
                              .map(outcome -> outcome instanceof CommitOutcome.Fenced)).isEqualTo(Result.success(true));
            assertThat(committed().map(StreamCursorCheckpointValue::committedOffset)).isEqualTo(Option.some(100L));
        }

        /// A→B→A: the node's FIRST tenure is fenced out even though it is the assignee again — only the
        /// current epoch's token passes.
        @Test
        void commit_isFenced_forAnEarlierTenureOfTheSameNode() {
            var node = storeFor(SELF, false, new ArrayList<>());

            assign(SELF, EPOCH);
            assign(PEER, NEXT_EPOCH);
            assign(SELF, Epoch.epoch(1L, 3L));

            assertThat(node.commit(GROUP, STREAM, PARTITION, 150L, EPOCH).await()
                           .map(outcome -> outcome instanceof CommitOutcome.Fenced)).isEqualTo(Result.success(true));
        }

        @Test
        void commit_sendsNoopBarrierBeforeTheVerdict_onlyWhenForwarding() {
            var forwardingSent = new ArrayList<KVCommand<AetherKey>>();
            var coreSent = new ArrayList<KVCommand<AetherKey>>();

            assign(SELF, EPOCH);
            storeFor(SELF, true, forwardingSent).commit(GROUP, STREAM, PARTITION, 1L, EPOCH).await();
            storeFor(SELF, false, coreSent).commit(GROUP, STREAM, PARTITION, 2L, EPOCH).await();

            assertThat(forwardingSent).hasSize(2);
            assertThat(forwardingSent.get(1)).isInstanceOf(KVCommand.Noop.class);
            assertThat(coreSent).hasSize(1);
        }

        /// A commit made without an assignment cannot pass the guard, so it is not published at all.
        @Test
        void commitWithoutAssignment_isFenced_andPublishesNothing() {
            var sent = new ArrayList<KVCommand<AetherKey>>();

            assign(SELF, EPOCH);

            assertThat(storeFor(SELF, false, sent).commit(GROUP, STREAM, PARTITION, 1L).await()
                                                .map(outcome -> outcome instanceof CommitOutcome.Fenced)).isEqualTo(Result.success(true));
            assertThat(sent).isEmpty();
        }

        /// The assignment record itself is gone: the applier admits nobody, and a record that names nobody
        /// does not name this node — Fenced, not the retryable verdict a lagging mirror gets (#1335 B1). The
        /// record is `EpochBearing`, so deleting it takes the committed value as witness; a witnessless
        /// remove is refused and the assignment stands (`KVStore.staleRemove`).
        @Test
        void commit_isFenced_whenTheAssignmentRecordIsGone() {
            var node = storeFor(SELF, false, new ArrayList<>());

            assign(SELF, EPOCH);
            node.commit(GROUP, STREAM, PARTITION, 100L, EPOCH).await();
            var record = kv.getTyped(ASSIGNMENT_KEY, ConsumerAssignmentValue.class);

            kv.process(kv.createBatch(List.of(new KVCommand.Remove<AetherKey>(ASSIGNMENT_KEY, record.map(value -> (Object) value)))));
            assertThat(kv.getTyped(ASSIGNMENT_KEY, ConsumerAssignmentValue.class)).describedAs("precondition: the witnessed remove landed")
                                                                                   .isEqualTo(Option.none());

            assertThat(node.commit(GROUP, STREAM, PARTITION, 150L, EPOCH).await()
                           .map(outcome -> outcome instanceof CommitOutcome.Fenced)).isEqualTo(Result.success(true));
            assertThat(committed().map(StreamCursorCheckpointValue::committedOffset)).isEqualTo(Option.some(100L));
        }
    }

    /// #1335 B1: a FORWARDING (worker) node's publish resolves on a core peer's reply, and its verdict reads
    /// its OWN committed-state mirror, which can trail that reply by a decision. `core` is the applier that
    /// admits or refuses; `mirror` is what the worker reads; `mirrorLags` withholds the latest decision from
    /// the mirror, which is the window the barrier narrows but does not close.
    @Nested
    class WorkerMirrorLag {
        private KVStore<AetherKey, AetherValue> core;
        private KVStore<AetherKey, AetherValue> mirror;
        private final AtomicBoolean mirrorLags = new AtomicBoolean(false);

        @BeforeEach
        void setUp() {
            core = new KVStore<>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
            mirror = new KVStore<>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
        }

        private ConsumerCursorStore worker() {
            return ClusterCursorStore.clusterCursorStore(recordingLocal(new AtomicReference<>()),
                                                         SELF,
                                                         key -> mirror.getTyped(key, StreamCursorCheckpointValue.class),
                                                         assignmentsIn(mirror),
                                                         this::applyOnCoreThenMirror,
                                                         () -> true);
        }

        private Promise<Unit> applyOnCoreThenMirror(List<KVCommand<AetherKey>> commands) {
            core.process(core.createBatch(commands));
            if (!mirrorLags.get()) {
                mirror.process(mirror.createBatch(commands));
            }

            return Promise.unitPromise();
        }

        /// A decision taken on the core while the mirror lagged reaches the mirror now.
        private void mirrorCatchesUp(List<KVCommand<AetherKey>> decision) {
            mirrorLags.set(false);
            mirror.process(mirror.createBatch(decision));
        }

        private void assignEverywhere(NodeId assignee, Epoch epoch) {
            var command = assignment(assignee, epoch);

            core.process(core.createBatch(List.of(command)));
            mirror.process(mirror.createBatch(List.of(command)));
        }

        private Option<Long> coreCheckpoint() {
            return core.getTyped(CHECKPOINT_KEY, StreamCursorCheckpointValue.class)
                       .map(StreamCursorCheckpointValue::committedOffset);
        }

        /// The defect (rev1335 B1): the applier ADMITTED the checkpoint, the mirror still shows this node's
        /// own previous one — same token, older offset — and the base called that Fenced, which is terminal
        /// and stops delivery on the node the record still names. The verdict must be retryable, and the
        /// retry must persist once the mirror shows the decision.
        @Test
        void commit_onAWorkerWhoseMirrorLagsTheCoreReply_isLocalOnly_notFenced() {
            var worker = worker();

            assignEverywhere(SELF, EPOCH);
            assertThat(worker.commit(GROUP, STREAM, PARTITION, 10L, EPOCH).await())
                    .describedAs("control: with a caught-up mirror the same commit is Persisted")
                    .isEqualTo(Result.success(CommitOutcome.persisted()));

            mirrorLags.set(true);
            var lagging = worker.commit(GROUP, STREAM, PARTITION, 20L, EPOCH).await();

            assertThat(coreCheckpoint()).describedAs("control: the authoritative applier ADMITTED the worker's checkpoint")
                                        .isEqualTo(Option.some(20L));
            assertThat(lagging.map(outcome -> outcome instanceof CommitOutcome.LocalOnly))
                    .describedAs("a checkpoint the applier admitted is retryable, never the terminal Fenced; got %s", lagging)
                    .isEqualTo(Result.success(true));

            mirrorCatchesUp(List.of(new KVCommand.Put<AetherKey, AetherValue>(CHECKPOINT_KEY, checkpoint(20L, SELF_TOKEN))));
            assertThat(worker.commit(GROUP, STREAM, PARTITION, 20L, EPOCH).await())
                    .describedAs("the retry persists once the mirror shows the decision")
                    .isEqualTo(Result.success(CommitOutcome.persisted()));
        }

        /// The other direction of the same lag: the assignment MOVED on the core, which refused the write,
        /// and the mirror has not shown the move yet. The verdict is delayed by one retry, not skipped — the
        /// applier stays the authority, and the retry is Fenced the moment the mirror shows the reassignment.
        @Test
        void commit_onAWorkerWhoseMirrorLagsAReassignment_isFencedOnTheRetry_notBypassed() {
            var worker = worker();

            assignEverywhere(SELF, EPOCH);
            worker.commit(GROUP, STREAM, PARTITION, 10L, EPOCH).await();
            mirrorLags.set(true);
            var reassignment = List.<KVCommand<AetherKey>>of(assignment(PEER, NEXT_EPOCH));
            core.process(core.createBatch(reassignment));

            var lagging = worker.commit(GROUP, STREAM, PARTITION, 20L, EPOCH).await();

            assertThat(coreCheckpoint()).describedAs("control: the applier REFUSED the deposed write")
                                        .isEqualTo(Option.some(10L));
            assertThat(lagging.map(outcome -> outcome instanceof CommitOutcome.LocalOnly))
                    .describedAs("the mirror still names this node, so the loser cannot yet tell refused from not-yet-visible")
                    .isEqualTo(Result.success(true));

            mirrorCatchesUp(reassignment);
            assertThat(worker.commit(GROUP, STREAM, PARTITION, 20L, EPOCH).await()
                             .map(outcome -> outcome instanceof CommitOutcome.Fenced))
                    .describedAs("the retry is refused again, and now the mirror shows why")
                    .isEqualTo(Result.success(true));
            assertThat(coreCheckpoint()).describedAs("the retry bypassed nothing").isEqualTo(Option.some(10L));
        }
    }

    private static KVCommand.Put<AetherKey, AetherValue> assignment(NodeId assignee, Epoch epoch) {
        return new KVCommand.Put<>(ASSIGNMENT_KEY,
                                   ConsumerAssignmentValue.consumerAssignmentValue(assignee,
                                                                                   epoch,
                                                                                   epoch.localCounter(),
                                                                                   HlcTimestamp.ZERO));
    }

    /// The committed consumer assignments as `kv` holds them — the same read the manager's admission makes.
    private static CommittedAssignments assignmentsIn(KVStore<AetherKey, AetherValue> kv) {
        return (stream, partition, group) -> kv.getTyped(ConsumerAssignmentKey.consumerAssignmentKey(stream, partition, group),
                                                         ConsumerAssignmentValue.class);
    }

    /// The steady state for the stubbed-reader tests: this node IS the committed assignee at [#EPOCH].
    private static CommittedAssignments assignedToSelf() {
        return (_, _, _) -> Option.some(ConsumerAssignmentValue.consumerAssignmentValue(SELF, EPOCH, 1L, HlcTimestamp.ZERO));
    }

    private static StreamCursorCheckpointValue checkpoint(long offset, AssignmentToken token) {
        return StreamCursorCheckpointValue.streamCursorCheckpointValue(offset, token);
    }

    private static ConsumerCursorStore storeWith(ConsumerCursorStore local,
                                                 Fn1<Option<StreamCursorCheckpointValue>, StreamCursorCheckpointKey> reader,
                                                 Fn1<Promise<Unit>, List<KVCommand<AetherKey>>> writer) {
        return ClusterCursorStore.clusterCursorStore(local, SELF, reader, assignedToSelf(), writer, () -> false);
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
                throw new UnsupportedOperationException("never snapshotted in this test");
            }
        };
    }

    private static Promise<Unit> capture(AtomicReference<KVCommand<AetherKey>> sink, List<KVCommand<AetherKey>> commands) {
        sink.set(commands.getFirst());

        return Promise.unitPromise();
    }

    private static ConsumerCursorStore recordingLocal(AtomicReference<Long> sink) {
        record recordingLocal(AtomicReference<Long> sink) implements ConsumerCursorStore {
            @Override
            public Promise<CommitOutcome> commit(String consumerGroup, String streamName, int partition, long offset) {
                sink.set(offset);

                return Promise.success(CommitOutcome.persisted());
            }

            @Override
            public Promise<Option<Long>> fetch(String consumerGroup, String streamName, int partition) {
                return Promise.success(Option.option(sink.get()));
            }
        }

        return new recordingLocal(sink);
    }

    private static ConsumerCursorStore fixedLocal(Option<Long> offset) {
        record fixedLocal(Option<Long> offset) implements ConsumerCursorStore {
            @Override
            public Promise<CommitOutcome> commit(String consumerGroup, String streamName, int partition, long value) {
                return Promise.success(CommitOutcome.persisted());
            }

            @Override
            public Promise<Option<Long>> fetch(String consumerGroup, String streamName, int partition) {
                return Promise.success(offset);
            }
        }

        return new fixedLocal(offset);
    }

    /// A local store holding a cursor from ANOTHER tenure: visible to the unfenced (pull-API) fetch, not
    /// to a fetch under the current assignment epoch — what the disk store's epoch record gives.
    private static ConsumerCursorStore tenureAwareLocal(long staleOffset) {
        record tenureAwareLocal(long staleOffset) implements ConsumerCursorStore {
            @Override
            public Promise<CommitOutcome> commit(String consumerGroup, String streamName, int partition, long value) {
                return Promise.success(CommitOutcome.persisted());
            }

            @Override
            public Promise<Option<Long>> fetch(String consumerGroup, String streamName, int partition) {
                return Promise.success(Option.some(staleOffset));
            }

            @Override
            public Promise<Option<Long>> fetch(String consumerGroup, String streamName, int partition, Epoch epoch) {
                return Promise.success(Option.none());
            }
        }

        return new tenureAwareLocal(staleOffset);
    }

    private enum CheckpointRejected implements Cause {
        INSTANCE;

        @Override
        public String message() {
            return "no quorum";
        }
    }
}
