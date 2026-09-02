// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.stream.forward.StreamForwardClient;
import org.pragmatica.aether.stream.forward.StreamForwardError;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.PublishForwardResponse;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForwardResponse;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.serialization.Serializer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// App-publish forward-retry parity for {@link DefaultStreamPublisher#forwardToOwner} (#485): a
/// metadata-only node write-forwards to the HRW owner, and a forward the owner reports as retryable
/// (`RemotePublishRetryable`, owner-config-lag) is bounded-retried exactly like the management/API write
/// path in {@link StreamWriteRouter}. The stream is deliberately NOT materialized locally so every publish
/// takes the remote-forward branch.
class DefaultStreamPublisherForwardRetryTest {
    private static final NodeId SELF = new NodeId("self-node");
    private static final NodeId OWNER = new NodeId("owner-node");
    private static final String STREAM = "forward-stream";
    private static final int PARTITION_COUNT = 1;
    private static final long FORWARDED_OFFSET = 42L;

    private StreamPartitionManager partitionManager;

    @BeforeEach
    void setUp() {
        partitionManager = streamPartitionManager(Long.MAX_VALUE);
    }

    @AfterEach
    void tearDown() {
        partitionManager.close();
    }

    private DefaultStreamPublisher<byte[]> publisher(StreamForwardClient forwardClient) {
        Function<Integer, Option<NodeId>> ownerResolver = _ -> Option.some(OWNER);

        return DefaultStreamPublisher.streamPublisher(partitionManager,
                                                      identitySerializer(),
                                                      STREAM,
                                                      PARTITION_COUNT,
                                                      Option.<Function<byte[], Object>> none(),
                                                      ConsistencyMode.EVENTUAL,
                                                      Option.none(),
                                                      0,
                                                      Option.some(forwardClient),
                                                      Option.<Fn0<Option<NodeId>>> none(),
                                                      Option.some(ownerResolver),
                                                      Option.some(SELF));
    }

    @Test
    void forwardToOwner_retriesThenSucceeds_whenOwnerReportsRetryableThenSuccess() {
        var forwardClient = ScriptedForwardClient.of(retryable(), success());

        publisher(forwardClient).publish("e0".getBytes()).await().onFailureRun(Assertions::fail);
        assertThat(forwardClient.publishCalls).isEqualTo(2);
    }

    @Test
    void forwardToOwner_doesNotRetry_whenOwnerReportsPermanentFailure() {
        var forwardClient = ScriptedForwardClient.of(permanent(), success());

        publisher(forwardClient).publish("e0".getBytes()).await().onSuccessRun(Assertions::fail).onFailure(cause -> assertThat(cause.message()).contains("Remote publish failed"));
        assertThat(forwardClient.publishCalls).isEqualTo(1);
    }

    @Test
    void forwardToOwner_retryIsBounded_whenOwnerAlwaysReportsRetryable() {
        var forwardClient = ScriptedForwardClient.of(retryable(), retryable(), retryable());

        publisher(forwardClient).publish("e0".getBytes()).await().onSuccessRun(Assertions::fail).onFailure(cause -> assertThat(cause.message()).contains("Remote publish retryable"));
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

    private static Serializer identitySerializer() {
        return new Serializer() {
            @SuppressWarnings("unchecked")
            @Override
            public <T> byte[] encode(T object) {
                return (byte[]) object;
            }

            @Override
            public <T> void write(io.netty.buffer.ByteBuf byteBuf, T object) {
                byteBuf.writeBytes((byte[]) object);
            }
        };
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
}
