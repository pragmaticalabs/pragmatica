// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.pragmatica.aether.stream.forward.StreamForwardClient;
import org.pragmatica.aether.stream.forward.StreamForwardError;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.PublishForwardResponse;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForwardResponse;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.slice.StreamConfig.streamConfig;


/// Owner-routing publish decision for {@link StreamWriteRouter}: a node that materializes the partition
/// appends locally; a metadata-only node (#265) write-forwards to the HRW owner instead of failing
/// PARTITION_NOT_LOCAL. Mirrors the read-side routing coverage.
class StreamWriteRouterTest {
    private static final NodeId SELF = new NodeId("self-node");
    private static final NodeId OWNER = new NodeId("owner-node");
    private static final String STREAM = "write-stream";
    private static final int PARTITION = 0;
    private static final long FORWARDED_OFFSET = 42L;

    private StreamPartitionManager partitionManager;

    @BeforeEach
    void setUp() {
        partitionManager = StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE);
    }

    private StreamWriteRouter router(StreamForwardClient forwardClient, Option<NodeId> owner) {
        return StreamWriteRouter.streamWriteRouter(partitionManager, Option.some(forwardClient), SELF, (_, _) -> owner);
    }

    @Test
    void publish_appendsLocally_whenSelfMaterializesPartition() {
        partitionManager.createStream(streamConfig(STREAM));
        var forwardClient = new RecordingForwardClient();

        router(forwardClient, Option.some(OWNER)).publish(STREAM, PARTITION, "e0".getBytes(), 1L).await().onFailureRun(Assertions::fail).onSuccess(offset -> assertThat(offset).isEqualTo(0L));
        assertThat(forwardClient.publishCalls).isZero();
        partitionManager.readLocal(STREAM, PARTITION, 0L, 10).onFailureRun(Assertions::fail).onSuccess(events -> assertThat(events).hasSize(1));
    }

    @Test
    void publish_forwardsToOwner_whenSelfIsMetadataOnly() {
        var forwardClient = new RecordingForwardClient();

        router(forwardClient, Option.some(OWNER)).publish(STREAM, PARTITION, "e0".getBytes(), 7L).await().onFailureRun(Assertions::fail).onSuccess(offset -> assertThat(offset).isEqualTo(FORWARDED_OFFSET));
        assertThat(forwardClient.publishCalls).isEqualTo(1);
        assertThat(forwardClient.lastOwner).isEqualTo(OWNER);
        assertThat(forwardClient.lastStream).isEqualTo(STREAM);
        assertThat(forwardClient.lastPartition).isEqualTo(PARTITION);
        assertThat(forwardClient.lastTimestamp).isEqualTo(7L);
    }

    @Test
    void publish_fallsBackToLocal_whenOwnerUnknown() {
        var forwardClient = new RecordingForwardClient();

        router(forwardClient, Option.none()).publish(STREAM, PARTITION, "e0".getBytes(), 1L).await().onSuccessRun(Assertions::fail).onFailureRun(() -> assertThat(forwardClient.publishCalls).isZero());
    }

    @Test
    void publish_retriesThenSucceeds_whenOwnerReportsRetryableThenSuccess() {
        var forwardClient = ScriptedForwardClient.of(retryable(), success());

        router(forwardClient, Option.some(OWNER)).publish(STREAM, PARTITION, "e0".getBytes(), 1L).await().onFailureRun(Assertions::fail).onSuccess(offset -> assertThat(offset).isEqualTo(FORWARDED_OFFSET));
        assertThat(forwardClient.publishCalls).isEqualTo(2);
    }

    @Test
    void publish_doesNotRetry_whenOwnerReportsPermanentFailure() {
        var forwardClient = ScriptedForwardClient.of(permanent(), success());

        router(forwardClient, Option.some(OWNER)).publish(STREAM, PARTITION, "e0".getBytes(), 1L).await().onSuccessRun(Assertions::fail).onFailure(cause -> assertThat(cause.message()).contains("Remote publish failed"));
        assertThat(forwardClient.publishCalls).isEqualTo(1);
    }

    @Test
    void publish_retryIsBounded_whenOwnerAlwaysReportsRetryable() {
        var forwardClient = ScriptedForwardClient.of(retryable(), retryable(), retryable());

        router(forwardClient, Option.some(OWNER)).publish(STREAM, PARTITION, "e0".getBytes(), 1L).await().onSuccessRun(Assertions::fail).onFailure(cause -> assertThat(cause.message()).contains("Remote publish retryable"));
        assertThat(forwardClient.publishCalls).isEqualTo(3);
    }

    private static Supplier<Promise<Long>> retryable() {
        return () -> new StreamForwardError.RemotePublishRetryable("config not yet visible").promise();
    }

    private static Supplier<Promise<Long>> permanent() {
        return () -> new StreamForwardError.RemotePublishFailed("boom").promise();
    }

    private static Supplier<Promise<Long>> success() {
        return () -> Promise.success(FORWARDED_OFFSET);
    }

    /// Serves a scripted per-call response so the bounded-retry decision can be driven: call N returns the
    /// Nth supplier's promise (the last supplier repeats past the script end). `publishCalls` counts the
    /// attempts so the retry bound is asserted.
    private static final class ScriptedForwardClient implements StreamForwardClient {
        private final List<Supplier<Promise<Long>>> script;
        private int publishCalls;

        private ScriptedForwardClient(List<Supplier<Promise<Long>>> script) {
            this.script = script;
        }

        @SafeVarargs
        static ScriptedForwardClient of(Supplier<Promise<Long>>... responses) {
            return new ScriptedForwardClient(List.of(responses));
        }

        @Override
        public Promise<Long> publishRemote(NodeId governorId,
                                           String streamName,
                                           int partition,
                                           byte[] payload,
                                           long timestamp) {
            var index = publishCalls++;

            return script.get(Math.min(index, script.size() - 1)).get();
        }

        @Override
        public Promise<ReadForwardResult> readRemote(NodeId replicaId,
                                                     String streamName,
                                                     int partition,
                                                     long fromOffset,
                                                     int maxEvents) {
            return Promise.success(ReadForwardResult.readForwardResult(List.of(), false));
        }

        @Override
        @SuppressWarnings("JBCT-RET-01")
        public void onPublishForwardResponse(PublishForwardResponse response) {}

        @Override
        @SuppressWarnings("JBCT-RET-01")
        public void onReadForwardResponse(ReadForwardResponse response) {}
    }

    /// Records the forwarded publish so the routing decision can be asserted; the read/receiver methods
    /// are unused by the write path and stay inert.
    private static final class RecordingForwardClient implements StreamForwardClient {
        private int publishCalls;
        private NodeId lastOwner;
        private String lastStream;
        private int lastPartition;
        private long lastTimestamp;

        @Override
        public Promise<Long> publishRemote(NodeId governorId,
                                           String streamName,
                                           int partition,
                                           byte[] payload,
                                           long timestamp) {
            publishCalls++;
            lastOwner = governorId;
            lastStream = streamName;
            lastPartition = partition;
            lastTimestamp = timestamp;

            return Promise.success(FORWARDED_OFFSET);
        }

        @Override
        public Promise<ReadForwardResult> readRemote(NodeId replicaId,
                                                     String streamName,
                                                     int partition,
                                                     long fromOffset,
                                                     int maxEvents) {
            return Promise.success(ReadForwardResult.readForwardResult(List.of(), false));
        }

        @Override
        @SuppressWarnings("JBCT-RET-01")
        public void onPublishForwardResponse(PublishForwardResponse response) {}

        @Override
        @SuppressWarnings("JBCT-RET-01")
        public void onReadForwardResponse(ReadForwardResponse response) {}
    }
}
