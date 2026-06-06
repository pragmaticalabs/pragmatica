// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamConfigValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// Verifies that {@link StreamPartitionManager} replicates stream configuration through the
/// KV-store consensus channel. This is the product-side fix for GitHub issue #215: stream
/// metadata reads (`streamInfo`, `listStreams`) used to be served from a per-instance in-memory
/// map populated only on the governor that ran `createStream`. After this change every node
/// that observes the {@code StreamConfigKey} `ValuePut` notification hydrates its local map
/// so that follow-up `streamInfo` calls on non-governor nodes return the same entry.
class StreamConfigReplicationTest {
    private static final NodeId SELF = NodeId.nodeId("node-self").unwrap();

    private RecordingClusterNode clusterNode;

    @BeforeEach
    void setUp() {
        clusterNode = new RecordingClusterNode();
    }

    @Nested
    class WritePath {

        @Test
        void createStream_writesStreamConfigKey_toClusterNode() {
            var manager = streamPartitionManager(Long.MAX_VALUE, clusterNode);
            try {
                var retention = RetentionPolicy.retentionPolicy(1000, 1024 * 1024, 60_000);
                var config = StreamConfig.streamConfig("orders", 4, retention, "latest");

                manager.createStream(config)
                       .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"));

                var puts = clusterNode.streamConfigPuts();
                assertThat(puts).as("createStream must publish a Put for StreamConfigKey").hasSize(1);
                var put = puts.getFirst();
                assertThat(put.key().streamName()).isEqualTo("orders");
                assertThat(put.value().config().name()).isEqualTo("orders");
                assertThat(put.value().config().partitions()).isEqualTo(4);
            } finally {
                manager.close();
            }
        }

        @Test
        void destroyStream_writesRemoveCommand_toClusterNode() {
            var manager = streamPartitionManager(Long.MAX_VALUE, clusterNode);
            try {
                var config = StreamConfig.streamConfig("orders");
                manager.createStream(config);

                manager.destroyStream("orders")
                       .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"));

                var removes = clusterNode.streamConfigRemoves();
                assertThat(removes).as("destroyStream must publish a Remove for StreamConfigKey").hasSize(1);
                assertThat(removes.getFirst().key().streamName()).isEqualTo("orders");
            } finally {
                manager.close();
            }
        }

        @Test
        void createStream_writesStreamConfigKey_visible_via_ValuePut_hydration() {
            // Mirrors the cluster path: node A (writer) writes, node B (receiver) consumes
            // the resulting ValuePut and must hydrate its local map identically.
            var writer = streamPartitionManager(Long.MAX_VALUE, clusterNode);
            try {
                var retention = RetentionPolicy.retentionPolicy(500, 512 * 1024, 30_000);
                var config = StreamConfig.streamConfig("events", 2, retention, "earliest");
                writer.createStream(config);

                var receiver = streamPartitionManager(Long.MAX_VALUE);
                try {
                    var put = clusterNode.streamConfigPuts().getFirst();
                    receiver.onStreamConfigPut(new ValuePut<>(put, Option.empty()));

                    var info = receiver.streamInfo("events");
                    assertThat(info.isPresent()).as("receiver must see hydrated stream after ValuePut").isTrue();
                    info.onPresent(si -> {
                        assertThat(si.name()).isEqualTo("events");
                        assertThat(si.partitions()).isEqualTo(2);
                    });
                } finally {
                    receiver.close();
                }
            } finally {
                writer.close();
            }
        }
    }

    @Nested
    class ReadPath {

        @Test
        void streamInfo_after_remote_createStream_returns_entry() {
            // Canonical assertion for issue #215: a node that never ran createStream
            // must still answer streamInfo() with the entry once it has observed the
            // ValuePut emitted by the governor.
            var nonGovernor = streamPartitionManager(Long.MAX_VALUE);
            try {
                var config = StreamConfig.streamConfig("orders");
                var put = new KVCommand.Put<StreamConfigKey, StreamConfigValue>(StreamConfigKey.streamConfigKey("orders"),
                                                                                StreamConfigValue.streamConfigValue(config));

                nonGovernor.onStreamConfigPut(new ValuePut<>(put, Option.empty()));

                assertThat(nonGovernor.streamInfo("orders").isPresent())
                    .as("non-governor must answer streamInfo after observing a remote createStream")
                    .isTrue();
            } finally {
                nonGovernor.close();
            }
        }

        @Test
        void listStreams_after_remote_createStream_returns_entry() {
            var nonGovernor = streamPartitionManager(Long.MAX_VALUE);
            try {
                var config = StreamConfig.streamConfig("orders");
                var put = new KVCommand.Put<StreamConfigKey, StreamConfigValue>(StreamConfigKey.streamConfigKey("orders"),
                                                                                StreamConfigValue.streamConfigValue(config));

                nonGovernor.onStreamConfigPut(new ValuePut<>(put, Option.empty()));

                assertThat(nonGovernor.listStreams()).hasSize(1);
                assertThat(nonGovernor.listStreams().getFirst().name()).isEqualTo("orders");
            } finally {
                nonGovernor.close();
            }
        }

        @Test
        void onStreamConfigPut_duplicateConfig_keepsExistingEntry() {
            // computeIfAbsent semantics: a re-issued ValuePut for the same stream
            // must NOT clobber the existing entry (which may already hold partition data).
            var nonGovernor = streamPartitionManager(Long.MAX_VALUE);
            try {
                var config = StreamConfig.streamConfig("orders");
                var put = new KVCommand.Put<StreamConfigKey, StreamConfigValue>(StreamConfigKey.streamConfigKey("orders"),
                                                                                StreamConfigValue.streamConfigValue(config));
                nonGovernor.onStreamConfigPut(new ValuePut<>(put, Option.empty()));
                var firstAlloc = nonGovernor.totalAllocatedBytes();

                nonGovernor.onStreamConfigPut(new ValuePut<>(put, Option.empty()));

                assertThat(nonGovernor.totalAllocatedBytes())
                    .as("duplicate ValuePut must not double-allocate")
                    .isEqualTo(firstAlloc);
            } finally {
                nonGovernor.close();
            }
        }

        @Test
        void onStreamConfigRemove_removesEntry() {
            var nonGovernor = streamPartitionManager(Long.MAX_VALUE);
            try {
                var config = StreamConfig.streamConfig("orders");
                var put = new KVCommand.Put<StreamConfigKey, StreamConfigValue>(StreamConfigKey.streamConfigKey("orders"),
                                                                                StreamConfigValue.streamConfigValue(config));
                nonGovernor.onStreamConfigPut(new ValuePut<>(put, Option.empty()));
                assertThat(nonGovernor.streamInfo("orders").isPresent()).isTrue();

                var remove = new KVCommand.Remove<StreamConfigKey>(StreamConfigKey.streamConfigKey("orders"));
                nonGovernor.onStreamConfigRemove(new ValueRemove<>(remove, Option.empty()));

                assertThat(nonGovernor.streamInfo("orders").isEmpty())
                    .as("receiver must drop the entry after observing a ValueRemove")
                    .isTrue();
            } finally {
                nonGovernor.close();
            }
        }

        @Test
        void onStreamConfigRemove_missingEntry_isNoOp() {
            var nonGovernor = streamPartitionManager(Long.MAX_VALUE);
            try {
                var remove = new KVCommand.Remove<StreamConfigKey>(StreamConfigKey.streamConfigKey("missing"));
                // Must not throw or fail when the stream was never seen.
                nonGovernor.onStreamConfigRemove(new ValueRemove<>(remove, Option.empty()));

                assertThat(nonGovernor.streamInfo("missing").isEmpty()).isTrue();
            } finally {
                nonGovernor.close();
            }
        }
    }

    @Nested
    class CommitFailurePropagation {

        @Test
        void createStream_failure_whenConsensusCommitFails() {
            // The masking bug: a failed StreamConfigKey commit used to be logged and swallowed,
            // so createStream returned success and the publish path looked accepted while the
            // stream was never durably created. The commit failure must now be honest.
            var failingNode = new FailingClusterNode();
            var manager = streamPartitionManager(Long.MAX_VALUE, failingNode);
            try {
                var config = StreamConfig.streamConfig("orders");

                manager.createStream(config)
                       .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("Expected failure"))
                       .onFailure(cause -> assertThat(cause).isEqualTo(StreamError.General.STREAM_CONFIG_COMMIT_FAILED));
            } finally {
                manager.close();
            }
        }

        @Test
        void createStream_keepsLocalEntry_whenConsensusCommitFails() {
            // Fix #2: a transient consensus-publish failure must NOT destroy the already-materialized
            // local partition. The previous behaviour (rollbackOptimisticEntry) wiped the local ring on
            // a commit timeout, so the owner could no longer serve/publish locally and the system-stream
            // could never recover. The local ring + its byte accounting must now SURVIVE the failure so
            // the owner keeps serving and the leader-pinned retry can re-publish the config.
            var failingNode = new FailingClusterNode();
            var manager = streamPartitionManager(Long.MAX_VALUE, failingNode);
            try {
                manager.createStream(StreamConfig.streamConfig("orders"))
                       .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("Expected commit failure"))
                       .onFailure(cause -> assertThat(cause).isEqualTo(StreamError.General.STREAM_CONFIG_COMMIT_FAILED));

                assertThat(manager.streamInfo("orders").isPresent())
                    .as("failed commit must KEEP the local partition (decoupled from publish)")
                    .isTrue();
                assertThat(manager.totalAllocatedBytes())
                    .as("failed commit must keep the local ring's allocated bytes")
                    .isGreaterThan(0L);
            } finally {
                manager.close();
            }
        }

        @Test
        void createStream_retryAfterCommitFailure_returnsAlreadyExists_withoutDoubleAllocating() {
            // Fix #2: after a transient commit failure left the local ring intact, a retry (the
            // leader-pinned SystemStreamRegistrar's re-attempt) must (a) NOT re-materialize / double-
            // allocate the partition, and (b) report STREAM_ALREADY_EXISTS once the (re-)publish
            // succeeds — the "stop" signal the registrar treats as DONE. Here the node recovers between
            // the two attempts (failing → recording).
            var failingNode = new FailingClusterNode();
            var manager = streamPartitionManager(Long.MAX_VALUE, failingNode);
            try {
                manager.createStream(StreamConfig.streamConfig("orders"))
                       .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("Expected first attempt to fail"));
                var allocatedAfterFirstAttempt = manager.totalAllocatedBytes();
                assertThat(allocatedAfterFirstAttempt).isGreaterThan(0L);

                // Second attempt: the local ring already exists, so createStream must re-publish the
                // (idempotent) config and report ALREADY_EXISTS on a now-healthy commit path.
                failingNode.recover();
                manager.createStream(StreamConfig.streamConfig("orders"))
                       .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("Expected STREAM_ALREADY_EXISTS"))
                       .onFailure(cause -> assertThat(cause).isEqualTo(StreamError.General.STREAM_ALREADY_EXISTS));

                assertThat(manager.totalAllocatedBytes())
                    .as("retry must not double-allocate the already-materialized partition")
                    .isEqualTo(allocatedAfterFirstAttempt);
            } finally {
                manager.close();
            }
        }
    }

    @Nested
    class HotPathNoRepublish {

        @Test
        void createStream_onCommittedStream_issuesNoAdditionalConsensusPut() {
            // Regression guard: StreamRoutes.ensureStreamExists calls createStream on EVERY publish.
            // Once the config is committed, a duplicate create must return STREAM_ALREADY_EXISTS without
            // any consensus round-trip — re-publishing here floods consensus and surfaces a transient
            // commit failure as a publish failure (the 04/08 "100% publish error" regression).
            var manager = streamPartitionManager(Long.MAX_VALUE, clusterNode);
            try {
                var config = StreamConfig.streamConfig("orders");
                manager.createStream(config)
                       .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected fresh create to succeed"));
                assertThat(clusterNode.streamConfigPuts()).as("fresh create publishes exactly one config Put").hasSize(1);

                manager.createStream(config)
                       .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("Expected STREAM_ALREADY_EXISTS"))
                       .onFailure(cause -> assertThat(cause).isEqualTo(StreamError.General.STREAM_ALREADY_EXISTS));
                manager.createStream(config);
                manager.createStream(config);

                assertThat(clusterNode.streamConfigPuts())
                    .as("createStream on a committed stream must NOT re-publish to consensus (hot per-publish path)")
                    .hasSize(1);
            } finally {
                manager.close();
            }
        }

        @Test
        void createStream_onConsensusHydratedStream_issuesNoConsensusPut() {
            // A follower hydrates a stream from a committed ValuePut. A subsequent createStream on that
            // follower (e.g. it handles a publish for a stream the leader created) must short-circuit to
            // STREAM_ALREADY_EXISTS without re-publishing — the hydrated config is already committed.
            var follower = streamPartitionManager(Long.MAX_VALUE, clusterNode);
            try {
                var config = StreamConfig.streamConfig("orders");
                var put = new KVCommand.Put<StreamConfigKey, StreamConfigValue>(StreamConfigKey.streamConfigKey("orders"),
                                                                                StreamConfigValue.streamConfigValue(config));
                follower.onStreamConfigPut(new ValuePut<>(put, Option.empty()));

                follower.createStream(config)
                        .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("Expected STREAM_ALREADY_EXISTS"))
                        .onFailure(cause -> assertThat(cause).isEqualTo(StreamError.General.STREAM_ALREADY_EXISTS));

                assertThat(clusterNode.streamConfigPuts())
                    .as("createStream on a consensus-hydrated stream must not re-publish")
                    .isEmpty();
            } finally {
                follower.close();
            }
        }
    }

    @AfterEach
    void tearDown() {
        // streams owned by each test are closed inline via try/finally
    }

    /// Captures every cluster-node apply() so the test can inspect issued KV commands.
    private static final class RecordingClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        private final List<KVCommand<AetherKey>> applied = Collections.synchronizedList(new ArrayList<>());

        @SuppressWarnings({"unchecked", "rawtypes"})
        List<KVCommand.Put<StreamConfigKey, StreamConfigValue>> streamConfigPuts() {
            synchronized (applied) {
                return applied.stream()
                              .filter(c -> c instanceof KVCommand.Put<?, ?> put
                                           && put.key() instanceof StreamConfigKey)
                              .map(c -> (KVCommand.Put) c)
                              .map(p -> (KVCommand.Put<StreamConfigKey, StreamConfigValue>) p)
                              .toList();
            }
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        List<KVCommand.Remove<StreamConfigKey>> streamConfigRemoves() {
            synchronized (applied) {
                return applied.stream()
                              .filter(c -> c instanceof KVCommand.Remove<?> rm
                                           && rm.key() instanceof StreamConfigKey)
                              .map(c -> (KVCommand.Remove) c)
                              .map(r -> (KVCommand.Remove<StreamConfigKey>) r)
                              .toList();
            }
        }

        @Override public NodeId self() {return SELF;}

        @Override public TopologyManager topologyManager() {throw new UnsupportedOperationException();}

        @Override public Promise<Unit> start() {return Promise.unitPromise();}

        @Override public Promise<Unit> stop() {return Promise.unitPromise();}

        @SuppressWarnings("unchecked")
        @Override public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
            applied.addAll(commands);
            return (Promise<List<R>>) (Promise<?>) Promise.success(List.of());
        }
    }

    /// Simulates a cluster node whose consensus commit fails (e.g. no quorum). Used to prove
    /// that {@link StreamPartitionManager#createStream} surfaces the failure instead of masking it.
    /// [`#recover()`] flips it to a healthy commit path so a Fix-#2 retry can succeed.
    private static final class FailingClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        private static final Cause COMMIT_REJECTED = Causes.cause("Consensus commit rejected");
        private volatile boolean healthy = false;

        void recover() {this.healthy = true;}

        @Override public NodeId self() {return SELF;}

        @Override public TopologyManager topologyManager() {throw new UnsupportedOperationException();}

        @Override public Promise<Unit> start() {return Promise.unitPromise();}

        @Override public Promise<Unit> stop() {return Promise.unitPromise();}

        @SuppressWarnings("unchecked")
        @Override public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
            if (healthy) {return (Promise<List<R>>) (Promise<?>) Promise.success(List.of());}
            return COMMIT_REJECTED.promise();
        }
    }
}
