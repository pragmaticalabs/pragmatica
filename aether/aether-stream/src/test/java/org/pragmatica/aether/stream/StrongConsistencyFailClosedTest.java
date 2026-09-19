// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.lang.Option;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// #1262: the consensus publish path has no production caller, so a `STRONG` stream cannot be written with
/// the guarantee it declares. `DefaultStreamPublisher` already refused with `CONSENSUS_PATH_UNAVAILABLE`;
/// `PartitionedStreamAccess` and `StreamWriteRouter` never read the consistency mode and silently wrote the
/// event as EVENTUAL. Both now refuse with the same cause and leave the ring untouched. The streams here are
/// built directly on the manager, bypassing the deploy-time validator that rejects STRONG first.
class StrongConsistencyFailClosedTest {
    private static final NodeId SELF = new NodeId("self-node");
    private static final String STRONG_STREAM = "strong-stream";
    private static final String EVENTUAL_STREAM = "eventual-stream";
    private static final String UNKNOWN_STREAM = "unknown-mode-stream";
    private static final int PARTITION = 0;

    private StreamPartitionManager partitionManager;

    @BeforeEach
    void setUp() {
        // STRONG needs a non-NOOP eviction listener to be created at all (AHSE_REQUIRED_FOR_STRONG).
        partitionManager = streamPartitionManager(Long.MAX_VALUE, (_, _, _) -> {});
        partitionManager.createStream(config(STRONG_STREAM, ConsistencyMode.STRONG)).onFailureRun(Assertions::fail);
        partitionManager.createStream(config(EVENTUAL_STREAM, ConsistencyMode.EVENTUAL)).onFailureRun(Assertions::fail);
        partitionManager.createStream(config(UNKNOWN_STREAM, ConsistencyMode.UNKNOWN)).onFailureRun(Assertions::fail);
    }

    @AfterEach
    void tearDown() {
        partitionManager.close();
    }

    @Test
    void streamAccessPublish_refusesWithConsensusPathUnavailable_forStrongStream() {
        access(STRONG_STREAM).publish("e0".getBytes())
                             .await()
                             .onSuccessRun(Assertions::fail)
                             .onFailure(cause -> assertThat(cause).isEqualTo(StreamError.General.CONSENSUS_PATH_UNAVAILABLE));
        assertThat(partitionManager.nextExpectedOffset(STRONG_STREAM, PARTITION)).isZero();
    }

    @Test
    void writeRouterPublish_refusesWithConsensusPathUnavailable_forStrongStream() {
        StreamWriteRouter.localOnly(partitionManager)
                         .publish(STRONG_STREAM, PARTITION, "e0".getBytes(), 1L)
                         .await()
                         .onSuccessRun(Assertions::fail)
                         .onFailure(cause -> assertThat(cause).isEqualTo(StreamError.General.CONSENSUS_PATH_UNAVAILABLE));
        assertThat(partitionManager.nextExpectedOffset(STRONG_STREAM, PARTITION)).isZero();
    }

    /// The owner-side entry for a write-forwarded publish re-checks the mode itself: a forwarder that did not
    /// refuse (an older node, or any future path) must not get a STRONG append landed as EVENTUAL here.
    @Test
    void publishForwarded_refusesWithConsensusPathUnavailable_forStrongStream() {
        partitionManager.publishForwarded(STRONG_STREAM, PARTITION, "e0".getBytes(), 1L)
                        .onSuccessRun(Assertions::fail)
                        .onFailure(cause -> assertThat(cause).isEqualTo(StreamError.General.CONSENSUS_PATH_UNAVAILABLE));
        assertThat(partitionManager.nextExpectedOffset(STRONG_STREAM, PARTITION)).isZero();
    }

    /// #964 on every entry point: a mode that decoded to UNKNOWN was written by a node running a newer
    /// `ConsistencyMode` and may be STRONG there. Appending it would be a silent EVENTUAL write, so every
    /// path refuses it with the same cause the slice publisher already used.
    @Test
    void streamAccessPublish_refusesUnreadableConsistencyMode() {
        access(UNKNOWN_STREAM).publish("e0".getBytes())
                              .await()
                              .onSuccessRun(Assertions::fail)
                              .onFailure(StrongConsistencyFailClosedTest::assertUnreadableMode);
        assertThat(partitionManager.nextExpectedOffset(UNKNOWN_STREAM, PARTITION)).isZero();
    }

    @Test
    void writeRouterPublish_refusesUnreadableConsistencyMode() {
        StreamWriteRouter.localOnly(partitionManager)
                         .publish(UNKNOWN_STREAM, PARTITION, "e0".getBytes(), 1L)
                         .await()
                         .onSuccessRun(Assertions::fail)
                         .onFailure(StrongConsistencyFailClosedTest::assertUnreadableMode);
        assertThat(partitionManager.nextExpectedOffset(UNKNOWN_STREAM, PARTITION)).isZero();
    }

    @Test
    void publishForwarded_refusesUnreadableConsistencyMode() {
        partitionManager.publishForwarded(UNKNOWN_STREAM, PARTITION, "e0".getBytes(), 1L)
                        .onSuccessRun(Assertions::fail)
                        .onFailure(StrongConsistencyFailClosedTest::assertUnreadableMode);
        assertThat(partitionManager.nextExpectedOffset(UNKNOWN_STREAM, PARTITION)).isZero();
    }

    /// Symmetry: the slice publisher, which refused UNKNOWN first (#964), refuses with the same cause.
    @Test
    void streamPublisherPublish_refusesUnreadableConsistencyMode() {
        DefaultStreamPublisher.<byte[]> streamPublisher(partitionManager,
                                                        identitySerializer(),
                                                        UNKNOWN_STREAM,
                                                        1,
                                                        Option.<Function<byte[], Object>> none(),
                                                        ConsistencyMode.UNKNOWN,
                                                        Option.none())
                              .publish("e0".getBytes())
                              .await()
                              .onSuccessRun(Assertions::fail)
                              .onFailure(StrongConsistencyFailClosedTest::assertUnreadableMode);
        assertThat(partitionManager.nextExpectedOffset(UNKNOWN_STREAM, PARTITION)).isZero();
    }

    private static void assertUnreadableMode(Cause cause) {
        assertThat(cause.message()).contains("#964");
    }

    /// Control: the refusal is keyed on the declared mode, not on these entry points being broken.
    @Test
    void streamAccessAndWriteRouter_stillPublish_forEventualStream() {
        access(EVENTUAL_STREAM).publish("e0".getBytes()).await().onFailureRun(Assertions::fail);
        StreamWriteRouter.localOnly(partitionManager)
                         .publish(EVENTUAL_STREAM, PARTITION, "e1".getBytes(), 1L)
                         .await()
                         .onFailureRun(Assertions::fail);
        assertThat(partitionManager.nextExpectedOffset(EVENTUAL_STREAM, PARTITION)).isEqualTo(2L);
    }

    private PartitionedStreamAccess<byte[]> access(String stream) {
        return PartitionedStreamAccess.<byte[]> streamAccess(partitionManager,
                                                             identitySerializer(),
                                                             identityDeserializer(),
                                                             stream,
                                                             1,
                                                             Option.<Function<byte[], Object>> none(),
                                                             Option.none(),
                                                             SELF,
                                                             Option.<Fn0<Option<NodeId>>> none(),
                                                             Option.none(),
                                                             0);
    }

    private static StreamConfig config(String name, ConsistencyMode mode) {
        return StreamConfig.streamConfig(name,
                                         1,
                                         RetentionPolicy.retentionPolicy(1_000, 1024 * 1024, 60_000),
                                         "earliest",
                                         1_048_576L,
                                         mode);
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

    private static Deserializer identityDeserializer() {
        return new Deserializer() {
            @SuppressWarnings("unchecked")
            @Override
            public <T> T decode(byte[] bytes) {
                return (T) bytes;
            }

            @SuppressWarnings("unchecked")
            @Override
            public <T> T read(io.netty.buffer.ByteBuf byteBuf) {
                var bytes = new byte[byteBuf.readableBytes()];

                byteBuf.readBytes(bytes);

                return (T) bytes;
            }
        };
    }
}
