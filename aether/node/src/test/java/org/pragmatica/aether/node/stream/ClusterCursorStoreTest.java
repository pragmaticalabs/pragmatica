// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.stream;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamCursorCheckpointValue;
import org.pragmatica.aether.stream.segment.ConsumerCursorStore;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
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
                                                              _ -> Option.some(70L),
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

        /// #654 round 2: `commit(...)`'s own Promise settles successfully even though the consensus
        /// publish failed — [#lastRecoveredFailure] is the only way the runtime can still learn that,
        /// since `onFailure` on `commit(...)` never fires for this case.
        @Test
        void commit_recordsRecoveredFailure_whenConsensusProposalFails() {
            var store = ClusterCursorStore.clusterCursorStore(recordingLocal(new AtomicReference<>()),
                                                              _ -> Option.none(),
                                                              _ -> CheckpointRejected.INSTANCE.promise());

            store.commit(GROUP, STREAM, PARTITION, 5L).await();

            assertThat(store.lastRecoveredFailure(GROUP, STREAM, PARTITION))
                    .describedAs("the recovered failure's detail must be readable right after commit(...) resolves")
                    .isEqualTo(Option.some(CheckpointRejected.INSTANCE.message()));
        }

        @Test
        void commit_reportsNoRecoveredFailure_whenConsensusProposalSucceeds() {
            var store = ClusterCursorStore.clusterCursorStore(recordingLocal(new AtomicReference<>()),
                                                              _ -> Option.none(),
                                                              _ -> Promise.unitPromise());

            store.commit(GROUP, STREAM, PARTITION, 5L).await();

            assertThat(store.lastRecoveredFailure(GROUP, STREAM, PARTITION)).isEqualTo(Option.none());
        }

        /// A stale detail from an earlier attempt must not linger once the store recovers — otherwise a
        /// transient consensus hiccup would keep reporting "failed" forever after it actually cleared.
        @Test
        void commit_clearsRecoveredFailure_onASubsequentSuccessfulProposal() {
            var attempt = new AtomicReference<>(CheckpointRejected.INSTANCE.<Unit>promise());
            var store = ClusterCursorStore.clusterCursorStore(recordingLocal(new AtomicReference<>()),
                                                              _ -> Option.none(),
                                                              _ -> attempt.get());

            store.commit(GROUP, STREAM, PARTITION, 5L).await();
            assertThat(store.lastRecoveredFailure(GROUP, STREAM, PARTITION)).isNotEqualTo(Option.none());

            attempt.set(Promise.unitPromise());
            store.commit(GROUP, STREAM, PARTITION, 6L).await();

            assertThat(store.lastRecoveredFailure(GROUP, STREAM, PARTITION))
                    .describedAs("a clean publish clears the previously recorded failure for this key")
                    .isEqualTo(Option.none());
        }

        /// A separate (group, stream, partition) key must not see another key's recovered failure — the
        /// map is keyed by the full checkpoint identity, not a single shared slot.
        @Test
        void lastRecoveredFailure_isScopedPerKey() {
            var store = ClusterCursorStore.clusterCursorStore(recordingLocal(new AtomicReference<>()),
                                                              _ -> Option.none(),
                                                              _ -> CheckpointRejected.INSTANCE.promise());

            store.commit(GROUP, STREAM, PARTITION, 5L).await();

            assertThat(store.lastRecoveredFailure(GROUP, STREAM, PARTITION + 1))
                    .describedAs("a different partition's key must read empty")
                    .isEqualTo(Option.none());
        }
    }

    private static Promise<Unit> capture(AtomicReference<KVCommand<AetherKey>> sink, KVCommand<AetherKey> command) {
        sink.set(command);

        return Promise.unitPromise();
    }

    private static ConsumerCursorStore recordingLocal(AtomicReference<Long> sink) {
        record recordingLocal(AtomicReference<Long> sink) implements ConsumerCursorStore {
            @Override
            public Promise<Unit> commit(String consumerGroup, String streamName, int partition, long offset) {
                sink.set(offset);

                return Promise.unitPromise();
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
            public Promise<Unit> commit(String consumerGroup, String streamName, int partition, long value) {
                return Promise.unitPromise();
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
