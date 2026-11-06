// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.PublishOutcomeUnknown;
import org.pragmatica.aether.stream.forward.StreamForwardClient;
import org.pragmatica.aether.stream.forward.StreamForwardError;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.PublishForwardResponse;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForwardResponse;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.WriteOutcome;
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

    /// #1236: an outcome-unknown forward means the owner may already have appended. Re-sending it would
    /// write a duplicate under the same bytes but a new offset, so the forward layer must NOT retry it —
    /// only `RemotePublishRetryable` (the owner never appended) is retried.
    @Test
    void forwardToOwner_doesNotRetry_whenOwnerReportsOutcomeUnknown() {
        var forwardClient = ScriptedForwardClient.of(outcomeUnknown(), success());

        publisher(forwardClient).publish("e0".getBytes()).await().onSuccessRun(Assertions::fail).onFailure(cause -> assertThat(cause).isInstanceOf(PublishOutcomeUnknown.class));
        assertThat(forwardClient.publishCalls).isEqualTo(1);
    }

    /// #2683: a forward whose frame the transport refused before writing it (`PublishNotSent`) never reached the owner, so
    /// re-sending it cannot duplicate: the forward layer retries it like the owner's own retryable answer. Red before #2683:
    /// the refused frame was invisible, and the publish waited out its forward wait into "outcome unknown".
    @Test
    void forwardToOwner_retriesThenSucceeds_whenTheForwardFrameWasNotSent() {
        var forwardClient = ScriptedForwardClient.of(notSent(), success());

        publisher(forwardClient).publish("e0".getBytes()).await().onFailureRun(Assertions::fail);
        assertThat(forwardClient.publishCalls).isEqualTo(2);
    }

    /// An encode failure is known-not-written too, but no retry can change it: it is not retried.
    @Test
    void forwardToOwner_doesNotRetry_whenTheForwardFrameCouldNotBeEncoded() {
        var forwardClient = ScriptedForwardClient.of(notEncoded(), success());

        publisher(forwardClient).publish("e0".getBytes()).await().onSuccessRun(Assertions::fail).onFailure(cause -> assertThat(cause).isInstanceOf(StreamForwardError.PublishNotEncoded.class));
        assertThat(forwardClient.publishCalls).isEqualTo(1);
    }

    private static Supplier<Promise<Long>> notSent() {
        return () -> StreamForwardError.PublishNotSent.FACTORY.apply(new WriteOutcome.BackpressureRefused(OWNER)).promise();
    }

    private static Supplier<Promise<Long>> notEncoded() {
        return () -> StreamForwardError.PublishNotEncoded.FACTORY.apply(new WriteOutcome.EncodeFailed(OWNER, "PublishForward")).promise();
    }

    private static Supplier<Promise<Long>> outcomeUnknown() {
        return () -> PublishOutcomeUnknown.FACTORY.apply(new StreamForwardError.RemotePublishFailed("acks timed out")).promise();
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
