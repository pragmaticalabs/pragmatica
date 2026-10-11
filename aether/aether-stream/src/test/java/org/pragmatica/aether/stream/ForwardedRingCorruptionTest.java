// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.stream.forward.ForwardCodecsStream;
import org.pragmatica.aether.stream.forward.StreamForwardClient;
import org.pragmatica.aether.stream.forward.StreamForwardHandler;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForward;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForwardResponse;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.aether.stream.forward.StreamForwardClient.streamForwardClient;
import static org.pragmatica.aether.stream.forward.StreamForwardHandler.streamForwardHandler;


/// #1967: `RING_INDEX_CORRUPTED` over the real handler and client. It lives in this package because the only way to make a ring
/// raise it is the ring's package-private test seam (an `IndexOutOfBoundsException` inside the guarded read).
class ForwardedRingCorruptionTest {
    private static final SliceCodec CODEC = SliceCodec.sliceCodec(FrameworkCodecs.frameworkCodecs(), ForwardCodecsStream.CODECS);
    private static final NodeId OWNER_ID = new NodeId("owner-node");
    private static final NodeId CALLER_ID = new NodeId("caller-node");
    private static final String STREAM = "orders";

    private StreamForwardHandler handler;

    @Test
    void ringIndexCorrupted_underAForwardedRead_arrivesAsRingIndexCorruptedWithItsDetail() {
        var owner = streamPartitionManager(Long.MAX_VALUE);

        try {
            owner.createStream(StreamConfig.streamConfig(STREAM, 1, RetentionPolicy.retentionPolicy(100, 64 * 1024L, 3_600_000L), "earliest"))
                 .onFailure(cause -> fail(cause.message()));
            owner.publishLocal(STREAM, 0, "e".getBytes(UTF_8), 1L).onFailure(cause -> fail(cause.message()));
            owner.partitionBuffer(STREAM, 0).or(() -> fail("ring")).truncationWindowProbe(() -> {throw new IndexOutOfBoundsException("Index 9 out of bounds for length 4");});
            var local = owner.readServing(STREAM, 0, 0, 10).fold(cause -> cause, _ -> fail("expected corruption"));
            StreamForwardClient[] client = new StreamForwardClient[1];

            client[0] = streamForwardClient(CALLER_ID, (_, message) -> handler.onReadForward((ReadForward) overTheWire(message)), TimeSpan.timeSpan(5).seconds());
            handler = streamForwardHandler(OWNER_ID,
                                           owner,
                                           (_, message) -> client[0].onReadForwardResponse((ReadForwardResponse) overTheWire(message)),
                                           Long.MAX_VALUE,
                                           org.pragmatica.aether.stream.forward.StreamReadForwardMetrics.NOOP,
                                           Option.none());
            var remote = client[0].readRemote(OWNER_ID, STREAM, 0, 0, 10).await().fold(cause -> cause, _ -> fail("expected corruption"));

            assertThat(local).isInstanceOf(StreamError.RingIndexCorrupted.class);
            assertThat(remote).isEqualTo(local);
            assertThat(remote).isInstanceOfSatisfying(StreamError.RingIndexCorrupted.class,
                                                      corrupted -> assertThat(corrupted.detail()).contains("Index 9 out of bounds"));
        } finally {
            owner.close();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T overTheWire(T message) {
        var buffer = Unpooled.buffer();

        CODEC.write(buffer, message);

        return CODEC.read(buffer);
    }
}
