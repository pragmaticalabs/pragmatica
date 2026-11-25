// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.projection;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.resource.projection.ProjectionStore.ReplayStatus;
import org.pragmatica.aether.resource.projection.ProjectionStore.RewindToken;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.generation.RewindEpoch;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamCursorCheckpointKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.TopicSubscriptionKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ConsumerAssignmentValue.AssignmentToken;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamCursorCheckpointValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.TopicSubscriptionValue;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.aether.stream.segment.ConsumerCursorStore;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #1400: the group's COMMITTED cursor reaches the projection hosted on a node that did not commit it. A projection
/// whose partition moved to another assignee sees no local commit, so it leaves REBUILDING only through this route.
/// The harness-level behaviour (a real store, a real applier, the move itself) is pinned in
/// `DurableProjectionRebuildTest`; here the hook is pinned in isolation, including a partition this node never
/// committed (captured by the rewind, held by someone else).
class ProjectionAwareCursorStoreCheckpointPutTest {
    private static final String GROUP = "slice#method";
    private static final String STREAM = "topic";
    private static final NodeId OTHER = NodeId.nodeId("node-2").unwrap();
    private static final RewindEpoch REWOUND = RewindEpoch.rewindEpoch(1L, 3L, 2L);

    @Test
    void onCheckpointPut_reportsAPartitionTheNodeNeverCommitted_stampedWithTheRecordsRewindEpoch() {
        var handle = new RecordingHandle();
        var store = hookOver(registryOf(handle));

        store.onCheckpointPut(checkpointPut(3, 7L, REWOUND));

        assertThat(handle.reports).as("one report: the record's partition and offset, under the token of ITS rewind epoch")
                  .containsExactly(new Report(NodeReplayCursor.tokenOf(REWOUND), 3, 7L));
        assertThat(store.reportFailureCount()).isZero();
    }

    @Test
    void onCheckpointPut_ignoresAPutThatIsNotACursorCheckpoint() {
        var handle = new RecordingHandle();
        var store = hookOver(registryOf(handle));

        store.onCheckpointPut(subscriptionPut());
        assertThat(handle.reports).as("a subscription put is not a cursor").isEmpty();

        store.onCheckpointPut(checkpointPut(0, 1L, REWOUND));
        assertThat(handle.reports).as("control: a checkpoint put on the same hook does report").hasSize(1);
        assertThat(store.reportFailureCount()).isZero();
    }

    @Test
    void onCheckpointPut_isANoOp_whenNoProjectionIsHostedHere() {
        var store = hookOver(registryOf(Option.none()));

        store.onCheckpointPut(checkpointPut(0, 5L, REWOUND));

        assertThat(store.reportFailureCount()).as("a group with no projection on this node is not a failure").isZero();
    }

    @Test
    void onCheckpointPut_countsAThrowingProjection_andNeverPropagates() {
        var store = hookOver(registryOf(new ThrowingHandle()));

        store.onCheckpointPut(checkpointPut(0, 5L, REWOUND));

        assertThat(store.reportFailureCount()).isEqualTo(1L);
    }

    @Test
    void onCheckpointPut_countsAThrowingRegistry_andNeverPropagates() {
        var registry = new StubRegistry() {
            @Override
            public Option<ProjectionHandle> lookup(String topicStream, String groupId) {
                throw new IllegalStateException("custom registry failed");
            }
        };
        var store = hookOver(registry);

        store.onCheckpointPut(checkpointPut(0, 5L, REWOUND));

        assertThat(store.reportFailureCount()).isEqualTo(1L);
    }

    private static ProjectionAwareCursorStore hookOver(ProjectionRegistry registry) {
        return ProjectionAwareCursorStore.projectionAwareCursorStore(new NoDelegate(), registry);
    }

    private static ValuePut<StreamCursorCheckpointKey, StreamCursorCheckpointValue> checkpointPut(int partition,
                                                                                                  long offset,
                                                                                                  RewindEpoch epoch) {
        var token = AssignmentToken.assignmentToken(OTHER, Epoch.epoch(1L, 1L, 1L));

        return new ValuePut<>(new KVCommand.Put<>(StreamCursorCheckpointKey.streamCursorCheckpointKey(STREAM, partition, GROUP),
                                                  StreamCursorCheckpointValue.streamCursorCheckpointValue(offset,
                                                                                                          token,
                                                                                                          epoch,
                                                                                                          Epoch.ZERO)),
                              Option.none());
    }

    private static ValuePut<TopicSubscriptionKey, TopicSubscriptionValue> subscriptionPut() {
        var artifact = Artifact.artifact("org.example:orders:1.0.0").unwrap();
        var key = TopicSubscriptionKey.topicSubscriptionKey(ResourceAddress.resourceAddress("org.example:" + STREAM + ":1.0.0").unwrap(),
                                                            artifact,
                                                            MethodName.methodName("onEvent").unwrap(),
                                                            OTHER);

        return new ValuePut<>(new KVCommand.Put<>(key, TopicSubscriptionValue.topicSubscriptionValue(OTHER)), Option.none());
    }

    private static ProjectionRegistry registryOf(ProjectionHandle handle) {
        return registryOf(Option.some(handle));
    }

    private static ProjectionRegistry registryOf(Option<ProjectionHandle> hosted) {
        return new StubRegistry() {
            @Override
            public Option<ProjectionHandle> lookup(String topicStream, String groupId) {
                return hosted;
            }
        };
    }

    record Report(RewindToken token, int partition, long committedCursor) {}

    private static final class RecordingHandle implements ProjectionHandle {
        final List<Report> reports = new CopyOnWriteArrayList<>();

        @Override
        public String projectionName() {
            return "recording";
        }

        @Override
        public Promise<Unit> onCursorCommitted(RewindToken token, int partition, long committedCursor) {
            reports.add(new Report(token, partition, committedCursor));

            return Promise.success(Unit.unit());
        }

        @Override
        public Promise<Unit> rebuild() {
            return Promise.success(Unit.unit());
        }

        @Override
        public Promise<ReplayStatus> replayStatus() {
            throw new UnsupportedOperationException();
        }
    }

    private static final class ThrowingHandle implements ProjectionHandle {
        @Override
        public String projectionName() {
            return "throwing";
        }

        @Override
        public Promise<Unit> onCursorCommitted(RewindToken token, int partition, long committedCursor) {
            throw new IllegalStateException("projection refused the report");
        }

        @Override
        public Promise<Unit> rebuild() {
            return Promise.success(Unit.unit());
        }

        @Override
        public Promise<ReplayStatus> replayStatus() {
            throw new UnsupportedOperationException();
        }
    }

    private abstract static class StubRegistry implements ProjectionRegistry {
        @Override
        public Result<Unit> register(Registration registration) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void unregister(ArtifactBase slice, String topicStream) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Result<String> groupIdOf(ArtifactBase slice, String topicStream, Option<String> method) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Registration> registrations(String topicStream) {
            return List.of();
        }
    }

    private static final class NoDelegate implements ConsumerCursorStore {
        @Override
        public Promise<CommitOutcome> commit(String consumerGroup, String streamName, int partition, long offset) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Promise<Option<Long>> fetch(String consumerGroup, String streamName, int partition) {
            throw new UnsupportedOperationException();
        }
    }
}
