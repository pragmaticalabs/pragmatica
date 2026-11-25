// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.stream;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.pragmatica.aether.slice.ConsumerConfig;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.generation.RewindEpoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamCursorCheckpointKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ConsumerAssignmentValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ConsumerAssignmentValue.AssignmentToken;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamCursorCheckpointValue;
import org.pragmatica.aether.stream.ConsumerFence;
import org.pragmatica.aether.stream.DeadLetterHandler;
import org.pragmatica.aether.stream.StreamConsumerRuntime;
import org.pragmatica.aether.stream.StreamConsumerRuntime.IdlePolicy;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.segment.ConsumerCursorStore;
import org.pragmatica.aether.stream.segment.ConsumerCursorStore.CommitOutcome;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.testing.PollUntil;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.StreamConsumerRuntime.streamConsumerRuntime;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// #3283: a worker's replica excludes cursor checkpoints, so a worker-hosted consumer resolves its committed cursor (and the
/// rewind epoch) through [CommittedCursorRead] — a consensus-ordered read answered by a core — never from its own mirror.
/// Only an ABSENT answer may start a partition at the group's reset point; an UNREADABLE one holds the partition, retries,
/// and raises `stream-consumer-cursor-unreadable` once, closed by `stream-consumer-cursor-readable`.
///
/// The worker here is the real [ClusterCursorStore] in forwarding mode under the real [StreamConsumerRuntime]; the core is
/// the `read` function, and the worker's own mirror always reads nothing for the key, as a worker's does.
class WorkerCursorResumeTest {
    private static final String STREAM = "orders";
    private static final String GROUP = "group-1";
    private static final int PARTITION = 0;
    private static final NodeId SELF = NodeId.nodeId("worker-1").unwrap();
    private static final Epoch EPOCH = Epoch.epoch(0L, 1L, 1L);
    private static final StreamCursorCheckpointKey KEY = StreamCursorCheckpointKey.streamCursorCheckpointKey(STREAM, PARTITION, GROUP);
    private static final RewindEpoch REWOUND = RewindEpoch.rewindEpoch(0L, 1L, 1L);
    private static final Duration AWAIT_DEADLINE = Duration.ofSeconds(5);

    private static final ConsumerFence ADMITTED = new ConsumerFence() {
        @Override
        public Epoch epoch() {
            return EPOCH;
        }

        @Override
        public boolean admitted() {
            return true;
        }
    };

    private StreamPartitionManager manager;

    @BeforeEach
    void setUp() {
        manager = streamPartitionManager();
        manager.createStream(StreamConfig.streamConfig(STREAM,
                                                        1,
                                                        RetentionPolicy.retentionPolicy(10_000, 1024 * 1024, 60_000),
                                                        "earliest"));
        for (var event = 0; event < 5; event++) {
            manager.publishLocal(STREAM, PARTITION, ("e" + event).getBytes(UTF_8), 1000L + event);
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        manager.close();
    }

    @Nested
    class Resume {
        @Test
        void workerSuccessor_committedCursorFound_resumesFromIt() {
            var reads = new AtomicInteger();
            var runtime = runtimeOver(workerStore(countingRead(reads, () -> found(3L))));
            var delivered = new CopyOnWriteArrayList<Long>();

            try {
                subscribe(runtime, delivered);
                awaitSize(delivered, 2);

                assertThat(reads.get()).describedAs("the worker asked a core, not its own mirror").isGreaterThanOrEqualTo(1);
                assertThat(delivered).describedAs("resumes at the committed cursor: no redelivery of 0..2").containsExactly(3L, 4L);
            } finally {
                runtime.close();
            }
        }

        @Test
        void freshGroupOnWorker_readProvesAbsent_startsAtTheResetPoint() {
            var runtime = runtimeOver(workerStore(_ -> Promise.success(Option.none())));
            var delivered = new CopyOnWriteArrayList<Long>();

            try {
                subscribe(runtime, delivered);
                awaitSize(delivered, 5);

                assertThat(delivered).describedAs("ABSENT proven by the read: the group's reset point (earliest)")
                                     .containsExactly(0L, 1L, 2L, 3L, 4L);
            } finally {
                runtime.close();
            }
        }

        /// The partition is held while the read cannot answer, the read is retried, and once it answers the consumer starts
        /// at the committed cursor: no redelivery below it and no skip past it. The warning opens once and closes once.
        @Test
        void unreadableRead_doesNotStart_retriesAndWarnsOnce_thenResumesFromTheCommittedCursor() {
            var unreadable = new AtomicBoolean(true);
            var reads = new AtomicInteger();
            var warnings = new CopyOnWriteArrayList<OperatorWarning>();
            var runtime = runtimeOver(workerStore(countingRead(reads,
                                                               () -> unreadable.get()
                                                                     ? Causes.cause("no core peers available").promise()
                                                                     : found(2L))));
            var delivered = new CopyOnWriteArrayList<Long>();

            runtime.operatorWarnings(OperatorWarningSink.handingOffTo(warnings::add));
            try {
                subscribe(runtime, delivered);
                awaitAtLeast(reads, 3);
                awaitWarning(warnings, OperatorWarningCode.STREAM_CONSUMER_CURSOR_UNREADABLE);

                assertThat(delivered).describedAs("UNREADABLE never starts the partition at the reset point").isEmpty();
                assertThat(runtime.subscriptions()).singleElement()
                                                   .satisfies(snapshot -> assertThat(snapshot.awaitingCursorFetch()).isTrue());
                assertThat(codes(warnings)).describedAs("raised once per episode, not per retry")
                                           .containsExactly(OperatorWarningCode.STREAM_CONSUMER_CURSOR_UNREADABLE);

                unreadable.set(false);
                awaitSize(delivered, 3);
                awaitWarning(warnings, OperatorWarningCode.STREAM_CONSUMER_CURSOR_READABLE);

                assertThat(delivered).describedAs("starts at the committed cursor once the core answers").containsExactly(2L, 3L, 4L);
                assertThat(codes(warnings)).describedAs("the episode is closed exactly once")
                                           .containsExactly(OperatorWarningCode.STREAM_CONSUMER_CURSOR_UNREADABLE,
                                                            OperatorWarningCode.STREAM_CONSUMER_CURSOR_READABLE);
            } finally {
                runtime.close();
            }
        }

        /// A worker that owns the partition, loses it and owns it again resolves the committed cursor again at the second
        /// start: it resumes where the interim owner committed, never from what it read or held at its first tenure.
        @Test
        void workerBecomesOwnerTwice_secondTenureReadsTheCommittedCursorAgain() {
            var committed = new AtomicReference<Option<StreamCursorCheckpointValue>>(found(1L).await().unwrap());
            var reads = new AtomicInteger();
            var runtime = runtimeOver(workerStore(countingRead(reads, () -> Promise.success(committed.get()))));
            var first = new CopyOnWriteArrayList<Long>();
            var second = new CopyOnWriteArrayList<Long>();

            try {
                subscribe(runtime, first);
                awaitSize(first, 4);
                runtime.unsubscribe(STREAM, PARTITION, GROUP);
                awaitGone(runtime);
                committed.set(Option.some(checkpoint(3L)));

                subscribe(runtime, second);
                awaitSize(second, 2);

                assertThat(first).containsExactly(1L, 2L, 3L, 4L);
                assertThat(second).describedAs("the second tenure starts at the interim owner's committed cursor")
                                  .containsExactly(3L, 4L);
                assertThat(reads.get()).describedAs("one authoritative read per tenure").isEqualTo(2);
            } finally {
                runtime.close();
            }
        }

        /// A worker's local mirror is never the cluster half of its resume, even when it does show a checkpoint (a mirror
        /// lagging behind the core): the read answer wins.
        @Test
        void workerMirrorShowsAnOlderCheckpoint_readAnswerIsUsed() {
            var store = ClusterCursorStore.clusterCursorStore(emptyLocal(),
                                                              SELF,
                                                              _ -> Option.some(checkpoint(0L)),
                                                              assignedToSelf(),
                                                              _ -> Promise.unitPromise(),
                                                              () -> true,
                                                              _ -> found(4L));

            assertThat(store.fetchCursor(GROUP, STREAM, PARTITION, EPOCH)
                            .await()
                            .unwrap()
                            .map(ConsumerCursorStore.Cursor::offset)).isEqualTo(Option.some(4L));
        }

        /// A core node keeps its own path: its mirror holds the key, and the read is never consulted.
        @Test
        void coreNode_resumeReadsItsOwnCommittedState() {
            var reads = new AtomicInteger();
            var store = ClusterCursorStore.clusterCursorStore(emptyLocal(),
                                                              SELF,
                                                              _ -> Option.some(checkpoint(2L)),
                                                              assignedToSelf(),
                                                              _ -> Promise.unitPromise(),
                                                              () -> false,
                                                              countingRead(reads, () -> found(4L)));

            assertThat(store.fetchCursor(GROUP, STREAM, PARTITION, EPOCH)
                            .await()
                            .unwrap()
                            .map(ConsumerCursorStore.Cursor::offset)).isEqualTo(Option.some(2L));
            assertThat(store.fetch(GROUP, STREAM, PARTITION)
                            .await()
                            .unwrap()).isEqualTo(Option.some(2L));
            assertThat(reads.get()).isZero();
        }

        /// The unfenced fetch takes the same path as the fenced one on a worker: FOUND is used, UNREADABLE fails it.
        @Test
        void workerUnfencedFetch_usesTheReadAndFailsWhenUnreadable() {
            var found = workerStore(_ -> found(4L));
            var unreadable = workerStore(_ -> Causes.cause("forward timed out").promise());

            assertThat(found.fetch(GROUP, STREAM, PARTITION).await().unwrap()).isEqualTo(Option.some(4L));
            assertThat(unreadable.fetch(GROUP, STREAM, PARTITION).await().isFailure()).isTrue();
            assertThat(unreadable.fetchCursor(GROUP, STREAM, PARTITION, EPOCH).await().isFailure()).isTrue();
        }

        /// A store built without the read answers UNREADABLE on a worker, never "no cursor".
        @Test
        void workerStoreWithoutTheRead_isUnreadable() {
            var store = ClusterCursorStore.clusterCursorStore(emptyLocal(),
                                                              SELF,
                                                              _ -> Option.none(),
                                                              assignedToSelf(),
                                                              _ -> Promise.unitPromise(),
                                                              () -> true);

            assertThat(store.fetchCursor(GROUP, STREAM, PARTITION, EPOCH).await().isFailure()).isTrue();
        }
    }

    /// #3283 round 2: the unreadable-cursor episode belongs to the consumer key, not to one consumer. Each read below is a
    /// pending promise the test answers, so the order of the old consumer's retry and its successor's fetch is fixed.
    @Nested
    class WarningEpisode {
        @Test
        void successorTakesTheEpisodeOver_oldRetryFailing_closesNothing() {
            successorTakesTheEpisodeOver(Causes.cause("no core peers available").promise());
        }

        @Test
        void successorTakesTheEpisodeOver_oldRetrySucceeding_closesNothing() {
            successorTakesTheEpisodeOver(found(2L));
        }

        /// The old consumer is abandoned while its fetch fails and re-subscribed; the successor's first fetch fails too. The
        /// old consumer's retry then answers: it neither closes the episode nor starts. One raise in total, one close when
        /// the successor starts.
        private void successorTakesTheEpisodeOver(Promise<Option<StreamCursorCheckpointValue>> oldRetryAnswer) {
            var reads = new CopyOnWriteArrayList<Promise<Option<StreamCursorCheckpointValue>>>();
            var warnings = new CopyOnWriteArrayList<OperatorWarning>();
            var runtime = runtimeOver(workerStore(pendingRead(reads)));
            var delivered = new CopyOnWriteArrayList<Long>();

            runtime.operatorWarnings(OperatorWarningSink.handingOffTo(warnings::add));
            try {
                subscribe(runtime, delivered);
                failRead(reads, 0);
                awaitReads(reads, 2);
                runtime.abandon(STREAM, PARTITION, GROUP);
                subscribe(runtime, delivered);
                failRead(reads, 2);
                awaitReads(reads, 4);
                answerRead(reads, 1, oldRetryAnswer);

                assertThat(codes(warnings)).describedAs("the successor took the episode over: no second raise, and the old "
                                                        + "consumer's retry does not close it")
                                           .containsExactly(OperatorWarningCode.STREAM_CONSUMER_CURSOR_UNREADABLE);
                assertThat(delivered).describedAs("nothing started").isEmpty();

                answerRead(reads, 3, found(2L));
                awaitSize(delivered, 3);
                awaitWarning(warnings, OperatorWarningCode.STREAM_CONSUMER_CURSOR_READABLE);

                assertThat(delivered).containsExactly(2L, 3L, 4L);
                assertThat(codes(warnings)).describedAs("closed once, by the successor's start")
                                           .containsExactly(OperatorWarningCode.STREAM_CONSUMER_CURSOR_UNREADABLE,
                                                            OperatorWarningCode.STREAM_CONSUMER_CURSOR_READABLE);
                assertThat(warnings.getLast().message()).contains("started");
            } finally {
                runtime.close();
            }
        }

        /// The successor's first fetch succeeds while the old consumer's episode is open: its start closes it, once, and the
        /// old consumer's later retry closes nothing.
        @Test
        void successorStartsAtItsFirstAttempt_closesTheOpenEpisodeOnce() {
            var reads = new CopyOnWriteArrayList<Promise<Option<StreamCursorCheckpointValue>>>();
            var warnings = new CopyOnWriteArrayList<OperatorWarning>();
            var runtime = runtimeOver(workerStore(pendingRead(reads)));
            var delivered = new CopyOnWriteArrayList<Long>();

            runtime.operatorWarnings(OperatorWarningSink.handingOffTo(warnings::add));
            try {
                subscribe(runtime, delivered);
                failRead(reads, 0);
                awaitReads(reads, 2);
                runtime.abandon(STREAM, PARTITION, GROUP);
                subscribe(runtime, delivered);
                awaitReads(reads, 3);
                answerRead(reads, 2, found(3L));
                awaitSize(delivered, 2);
                awaitWarning(warnings, OperatorWarningCode.STREAM_CONSUMER_CURSOR_READABLE);
                failRead(reads, 1);

                assertThat(delivered).containsExactly(3L, 4L);
                assertThat(codes(warnings)).describedAs("opened once, closed once by the successor's start")
                                           .containsExactly(OperatorWarningCode.STREAM_CONSUMER_CURSOR_UNREADABLE,
                                                            OperatorWarningCode.STREAM_CONSUMER_CURSOR_READABLE);
                assertThat(warnings.getLast().message()).contains("started");
            } finally {
                runtime.close();
            }
        }

        /// No successor: the cancelled consumer's next answer closes its own episode, once, and says it was cancelled.
        @Test
        void cancelledWithoutSuccessor_closesItsEpisodeOnce_asCancelled() {
            var reads = new CopyOnWriteArrayList<Promise<Option<StreamCursorCheckpointValue>>>();
            var warnings = new CopyOnWriteArrayList<OperatorWarning>();
            var runtime = runtimeOver(workerStore(pendingRead(reads)));
            var delivered = new CopyOnWriteArrayList<Long>();

            runtime.operatorWarnings(OperatorWarningSink.handingOffTo(warnings::add));
            try {
                subscribe(runtime, delivered);
                failRead(reads, 0);
                awaitReads(reads, 2);
                runtime.abandon(STREAM, PARTITION, GROUP);
                failRead(reads, 1);
                awaitWarning(warnings, OperatorWarningCode.STREAM_CONSUMER_CURSOR_READABLE);

                assertThat(codes(warnings)).containsExactly(OperatorWarningCode.STREAM_CONSUMER_CURSOR_UNREADABLE,
                                                            OperatorWarningCode.STREAM_CONSUMER_CURSOR_READABLE);
                assertThat(warnings.getLast().message()).contains("cancelled")
                                                        .doesNotContain("started");
                assertThat(delivered).isEmpty();
                assertThat(reads).describedAs("the cancelled consumer stops retrying").hasSize(2);
            } finally {
                runtime.close();
            }
        }
    }

    @Nested
    class ConsensusRead {
        @Test
        void read_sendsOneGetForTheKey_andDecodesFound() {
            var sent = new CopyOnWriteArrayList<KVCommand<AetherKey>>();
            var read = CommittedCursorRead.consensusRead(commands -> answering(sent, commands, List.of(Option.some(checkpoint(7L)))));

            assertThat(read.read(KEY).await().unwrap().map(StreamCursorCheckpointValue::committedOffset)).isEqualTo(Option.some(7L));
            assertThat(sent).singleElement().isEqualTo(new KVCommand.Get<AetherKey>(KEY));
        }

        @Test
        void read_getAnsweredWithNothing_isAbsent() {
            var read = CommittedCursorRead.consensusRead(commands -> answering(new CopyOnWriteArrayList<>(), commands, List.of(Option.none())));

            assertThat(read.read(KEY).await().unwrap()).isEqualTo(Option.none());
        }

        /// ABSENT is only the Get's own applied answer. A reply carrying no Get answer (nothing applied for it), more than
        /// one, or a value of another type did not come from applying the Get, so it is UNREADABLE.
        @Test
        void read_replyThatIsNotOneGetAnswer_isUnreadableNotAbsent() {
            List<List<Object>> replies = List.of(List.of(),
                                                 List.of(Option.none(), Option.none()),
                                                 List.of(Option.some("not a checkpoint")),
                                                 List.of(Unit.unit()));

            for (var reply : replies) {
                var read = CommittedCursorRead.consensusRead(commands -> answering(new CopyOnWriteArrayList<>(), commands, reply));

                assertThat(read.read(KEY).await().isFailure()).describedAs("reply %s", reply).isTrue();
            }
        }

        @Test
        void read_clusterFails_isUnreadable() {
            var read = CommittedCursorRead.consensusRead(_ -> Causes.cause("No core peers available for forwarding").promise());

            assertThat(read.read(KEY).await().isFailure()).isTrue();
        }
    }

    @Nested
    class RewindEpochSource {
        /// The rewind epoch takes the same path as the cursor: a worker's reconcile pass acts on the epoch the read found,
        /// never on its mirror. The pass is synchronous, so it acts on the previous pass's answer.
        @Test
        void worker_epochComesFromTheRead_oneAnswerBehind() {
            var answer = new AtomicReference<Promise<Option<StreamCursorCheckpointValue>>>(Promise.success(Option.some(rewound(REWOUND))));
            var source = CommittedCursorRead.committedEpochs(() -> true, _ -> Option.none(), _ -> answer.get());

            assertThat(source.committedEpoch(STREAM, PARTITION, GROUP)).describedAs("nothing read yet").isEqualTo(Option.none());
            assertThat(source.committedEpoch(STREAM, PARTITION, GROUP)).describedAs("the previous read's FOUND")
                                                                     .isEqualTo(Option.some(REWOUND));

            answer.set(Causes.cause("forward timed out").promise());
            assertThat(source.committedEpoch(STREAM, PARTITION, GROUP)).isEqualTo(Option.some(REWOUND));
            assertThat(source.committedEpoch(STREAM, PARTITION, GROUP)).describedAs("UNREADABLE keeps the last FOUND, claims nothing new")
                                                                     .isEqualTo(Option.some(REWOUND));

            answer.set(Promise.success(Option.none()));
            assertThat(source.committedEpoch(STREAM, PARTITION, GROUP)).isEqualTo(Option.some(REWOUND));
            assertThat(source.committedEpoch(STREAM, PARTITION, GROUP)).describedAs("ABSENT forgets the key")
                                                                     .isEqualTo(Option.none());
        }

        @Test
        void worker_mirrorEpochIsIgnored() {
            var source = CommittedCursorRead.committedEpochs(() -> true,
                                                             _ -> Option.some(rewound(REWOUND)),
                                                             _ -> Promise.success(Option.none()));

            source.committedEpoch(STREAM, PARTITION, GROUP);

            assertThat(source.committedEpoch(STREAM, PARTITION, GROUP)).isEqualTo(Option.none());
        }

        @Test
        void core_epochComesFromItsOwnCommittedState_andTheReadIsNotUsed() {
            var reads = new AtomicInteger();
            var source = CommittedCursorRead.committedEpochs(() -> false,
                                                             _ -> Option.some(rewound(REWOUND)),
                                                             countingRead(reads, () -> Promise.success(Option.none())));

            assertThat(source.committedEpoch(STREAM, PARTITION, GROUP)).isEqualTo(Option.some(REWOUND));
            assertThat(reads.get()).isZero();
        }
    }

    private static Promise<List<Object>> answering(List<KVCommand<AetherKey>> sent,
                                                   List<KVCommand<AetherKey>> commands,
                                                   List<Object> reply) {
        sent.addAll(commands);

        return Promise.success(reply);
    }

    /// Each read is a pending promise appended in issue order; the test answers it.
    private static CommittedCursorRead pendingRead(List<Promise<Option<StreamCursorCheckpointValue>>> reads) {
        return _ -> issuePending(reads);
    }

    private static Promise<Option<StreamCursorCheckpointValue>> issuePending(List<Promise<Option<StreamCursorCheckpointValue>>> reads) {
        var pending = Promise.<Option<StreamCursorCheckpointValue>> promise();

        reads.add(pending);

        return pending;
    }

    private static void failRead(List<Promise<Option<StreamCursorCheckpointValue>>> reads, int index) {
        answerRead(reads, index, Causes.cause("no core peers available").promise());
    }

    private static void answerRead(List<Promise<Option<StreamCursorCheckpointValue>>> reads,
                                   int index,
                                   Promise<Option<StreamCursorCheckpointValue>> answer) {
        awaitReads(reads, index + 1);
        answer.onResult(reads.get(index)::resolve);
    }

    private static void awaitReads(List<Promise<Option<StreamCursorCheckpointValue>>> reads, int count) {
        PollUntil.pollUntil(reads::size, size -> size >= count, AWAIT_DEADLINE)
                 .assertMet(count + " cursor reads issued");
    }

    private StreamConsumerRuntime runtimeOver(ConsumerCursorStore store) {
        return streamConsumerRuntime(manager, DeadLetterHandler.deadLetterHandler(), store);
    }

    private static void subscribe(StreamConsumerRuntime runtime, List<Long> delivered) {
        runtime.subscribe(STREAM,
                          PARTITION,
                          ConsumerConfig.consumerConfig(GROUP),
                          (offset, payload, ts) -> record(delivered, offset),
                          IdlePolicy.REAP_WHEN_IDLE,
                          ADMITTED);
    }

    private static Promise<Unit> record(List<Long> delivered, long offset) {
        delivered.add(offset);

        return Promise.unitPromise();
    }

    private static ConsumerCursorStore workerStore(CommittedCursorRead read) {
        return ClusterCursorStore.clusterCursorStore(emptyLocal(),
                                                     SELF,
                                                     _ -> Option.none(),
                                                     assignedToSelf(),
                                                     _ -> Promise.unitPromise(),
                                                     (BooleanSupplier) () -> true,
                                                     read);
    }

    private static CommittedCursorRead countingRead(AtomicInteger reads,
                                                    Supplier<Promise<Option<StreamCursorCheckpointValue>>> answer) {
        return _ -> countAndAnswer(reads, answer);
    }

    private static Promise<Option<StreamCursorCheckpointValue>> countAndAnswer(AtomicInteger reads,
                                                                               Supplier<Promise<Option<StreamCursorCheckpointValue>>> answer) {
        reads.incrementAndGet();

        return answer.get();
    }

    private static Promise<Option<StreamCursorCheckpointValue>> found(long offset) {
        return Promise.success(Option.some(checkpoint(offset)));
    }

    private static StreamCursorCheckpointValue checkpoint(long offset) {
        return StreamCursorCheckpointValue.streamCursorCheckpointValue(offset,
                                                                       AssignmentToken.assignmentToken(SELF, EPOCH, Option.none()),
                                                                       RewindEpoch.NONE);
    }

    private static StreamCursorCheckpointValue rewound(RewindEpoch epoch) {
        return StreamCursorCheckpointValue.streamCursorCheckpointValue(0L,
                                                                       AssignmentToken.assignmentToken(SELF, EPOCH, Option.none()),
                                                                       epoch);
    }

    private static ConsumerAssignmentWriter.CommittedAssignments assignedToSelf() {
        return (_, _, _) -> Option.some(ConsumerAssignmentValue.consumerAssignmentValue(SELF,
                                                                                        EPOCH,
                                                                                        1L,
                                                                                        HlcTimestamp.ZERO,
                                                                                        Option.none()));
    }

    private static ConsumerCursorStore emptyLocal() {
        record emptyLocal() implements ConsumerCursorStore {
            @Override
            public Promise<CommitOutcome> commit(String consumerGroup, String streamName, int partition, long offset) {
                return Promise.success(CommitOutcome.persisted());
            }

            @Override
            public Promise<Option<Long>> fetch(String consumerGroup, String streamName, int partition) {
                return Promise.success(Option.none());
            }
        }

        return new emptyLocal();
    }

    private static List<OperatorWarningCode> codes(List<OperatorWarning> warnings) {
        return warnings.stream()
                       .map(OperatorWarning::code)
                       .filter(code -> code == OperatorWarningCode.STREAM_CONSUMER_CURSOR_UNREADABLE
                                       || code == OperatorWarningCode.STREAM_CONSUMER_CURSOR_READABLE)
                       .toList();
    }

    private static void awaitSize(List<Long> delivered, int size) {
        PollUntil.pollUntil(delivered::size, count -> count >= size, AWAIT_DEADLINE)
                 .assertMet(size + " deliveries");
    }

    private static void awaitAtLeast(AtomicInteger counter, int count) {
        PollUntil.pollUntil(counter::get, value -> value >= count, AWAIT_DEADLINE)
                 .assertMet(count + " cursor reads");
    }

    private static void awaitWarning(List<OperatorWarning> warnings, OperatorWarningCode code) {
        PollUntil.pollUntil(() -> codes(warnings), raised -> raised.contains(code), AWAIT_DEADLINE)
                 .assertMet("warning " + code);
    }

    private static void awaitGone(StreamConsumerRuntime runtime) {
        PollUntil.pollUntil(runtime::subscriptions, List::isEmpty, AWAIT_DEADLINE)
                 .assertMet("the subscription to be gone");
    }
}
