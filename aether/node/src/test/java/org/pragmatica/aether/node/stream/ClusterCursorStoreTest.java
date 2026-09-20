// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.stream;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import org.pragmatica.aether.slice.generation.RewindEpoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamCursorCheckpointValue;
import org.pragmatica.aether.stream.segment.ConsumerCursorStore;
import org.pragmatica.aether.stream.segment.ConsumerCursorStore.CommitOutcome;
import org.pragmatica.aether.stream.segment.ConsumerCursorStore.Cursor;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

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

        /// #1333: the epoch ranks FIRST. A rewind puts `(epoch', fromOffset)` into KV; the local cursor
        /// from before it is higher but older, and must lose — otherwise a node that crashed before
        /// applying the rewind would resurrect its stale-high cursor and replay nothing.
        @Test
        void resumeCursor_prefersTheRewoundClusterCursor_overAHigherLocalOneAtAnOlderEpoch() {
            var local = Cursor.cursor(900L, RewindEpoch.NONE);
            var cluster = Cursor.cursor(0L, RewindEpoch.rewindEpoch(1L, 1L));

            assertThat(ClusterCursorStore.resumeCursor(Option.some(local), Option.some(cluster)))
                    .isEqualTo(Option.some(cluster));
        }

        /// And in the other direction: a local cursor committed under the NEWER epoch (this node applied
        /// the rewind and progressed, the cluster checkpoint lags) is kept over a lower cluster cursor at
        /// that same epoch, and over a higher cluster cursor at an older one.
        @Test
        void resumeCursor_prefersTheLocalCursor_whenItsEpochIsNewerOrEqualAndItIsAhead() {
            var rewound = RewindEpoch.rewindEpoch(1L, 1L);

            assertThat(ClusterCursorStore.resumeCursor(Option.some(Cursor.cursor(5L, rewound)),
                                                       Option.some(Cursor.cursor(2L, rewound))))
                    .isEqualTo(Option.some(Cursor.cursor(5L, rewound)));
            assertThat(ClusterCursorStore.resumeCursor(Option.some(Cursor.cursor(5L, rewound)),
                                                       Option.some(Cursor.cursor(900L, RewindEpoch.NONE))))
                    .describedAs("a stale-epoch cluster cursor never outranks a rewound local one, however high")
                    .isEqualTo(Option.some(Cursor.cursor(5L, rewound)));
        }
    }

    @Nested
    class CommitFanout {

        @Test
        void commit_writesLocalStore_andProposesConsensusCheckpoint() {
            var localOffset = new AtomicReference<Long>();
            var proposed = new AtomicReference<KVCommand<AetherKey>>();
            var store = ClusterCursorStore.clusterCursorStore(recordingLocal(localOffset),
                                                              _ -> Option.none(),
                                                              command -> capture(proposed, command));

            store.commit(GROUP, STREAM, PARTITION, 314L).await();

            assertThat(localOffset.get()).isEqualTo(314L);
            assertThat(proposed.get())
                    .describedAs("the checkpoint must reach consensus, otherwise failover replays from 0")
                    .isInstanceOf(KVCommand.Put.class);
        }

        @Test
        void commit_writesCheckpointValue_carryingTheCommittedOffset() {
            var proposed = new AtomicReference<KVCommand<AetherKey>>();
            var store = ClusterCursorStore.clusterCursorStore(recordingLocal(new AtomicReference<>()),
                                                              _ -> Option.none(),
                                                              command -> capture(proposed, command));

            store.commit(GROUP, STREAM, PARTITION, 99L).await();

            assertThat(proposed.get()).isInstanceOfSatisfying(KVCommand.Put.class,
                                                              put -> assertThat(((StreamCursorCheckpointValue) put.value()).committedOffset())
                                                                      .isEqualTo(99L));
        }

        @Test
        void fetch_composesLocalAndClusterCursors() {
            var store = ClusterCursorStore.clusterCursorStore(fixedLocal(Option.some(10L)),
                                                              _ -> Option.some(StreamCursorCheckpointValue.streamCursorCheckpointValue(70L)),
                                                              _ -> Promise.unitPromise());

            store.fetch(GROUP, STREAM, PARTITION)
                 .await()
                 .onFailure(cause -> Assertions.fail(cause.message()))
                 .onSuccess(offset -> assertThat(offset).isEqualTo(Option.some(70L)));
        }

        @Test
        void commit_stillSucceedsLocally_whenConsensusProposalFails() {
            var localOffset = new AtomicReference<Long>();
            var store = ClusterCursorStore.clusterCursorStore(recordingLocal(localOffset),
                                                              _ -> Option.none(),
                                                              _ -> CheckpointRejected.INSTANCE.promise());

            store.commit(GROUP, STREAM, PARTITION, 5L)
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
            var store = ClusterCursorStore.clusterCursorStore(recordingLocal(new AtomicReference<>()),
                                                              _ -> Option.none(),
                                                              _ -> CheckpointRejected.INSTANCE.promise());

            assertThat(store.commit(GROUP, STREAM, PARTITION, 5L).await())
                    .describedAs("the recovered failure travels on this commit's own outcome")
                    .isEqualTo(Result.success(CommitOutcome.localOnly(CheckpointRejected.INSTANCE)));
        }

        @Test
        void commit_reportsPersisted_whenConsensusProposalSucceeds() {
            var store = ClusterCursorStore.clusterCursorStore(recordingLocal(new AtomicReference<>()),
                                                              _ -> Option.none(),
                                                              _ -> Promise.unitPromise());

            assertThat(store.commit(GROUP, STREAM, PARTITION, 5L).await())
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
            var store = ClusterCursorStore.clusterCursorStore(recordingLocal(new AtomicReference<>()),
                                                              _ -> Option.none(),
                                                              _ -> calls.incrementAndGet() == 1 ? publishA : Promise.unitPromise());

            var commitA = store.commit(GROUP, STREAM, PARTITION, 5L);
            var commitB = store.commit(GROUP, STREAM, PARTITION, 6L);

            assertThat(commitB.await()).describedAs("B succeeded early and reports its own success")
                                      .isEqualTo(Result.success(CommitOutcome.persisted()));
            publishA.fail(CheckpointRejected.INSTANCE);
            assertThat(commitA.await()).describedAs("A failed late and reports its own cause, not lost to B")
                                      .isEqualTo(Result.success(CommitOutcome.localOnly(CheckpointRejected.INSTANCE)));
        }
    }

    private static Promise<Unit> capture(AtomicReference<KVCommand<AetherKey>> sink, KVCommand<AetherKey> command) {
        sink.set(command);

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

    private enum CheckpointRejected implements Cause {
        INSTANCE;

        @Override
        public String message() {
            return "no quorum";
        }
    }
}
