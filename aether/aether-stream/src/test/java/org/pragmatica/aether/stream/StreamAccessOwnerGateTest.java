// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.stream.replication.ReplicaSetController;
import org.pragmatica.lang.Option;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.slice.StreamConfig.streamConfig;

/// #1555 (v1555 nit): the slice-facing stream access reads this node's copy through the owner promotion gate — a
/// placement owner that has not been activated refuses the read instead of serving a ring it has not caught up.
class StreamAccessOwnerGateTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;

    private StreamPartitionManager manager;
    private PartitionedStreamAccess<byte[]> access;

    @BeforeEach
    void setUp() {
        manager = StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE);
        assertThat(manager.createStream(streamConfig(STREAM)).isSuccess()).isTrue();
        assertThat(manager.publishLocal(STREAM, PARTITION, "e0".getBytes(), 1L).isSuccess()).isTrue();
        access = PartitionedStreamAccess.streamAccess(manager,
                                                      identitySerializer(),
                                                      identityDeserializer(),
                                                      STREAM,
                                                      1,
                                                      Option.<Function<byte[], Object>> none());
        manager.placementRoleSupplier((_, _) -> ReplicaSetController.Role.OWNER);
    }

    @Test
    void fetch_ownerNotActivated_isRefused() {
        manager.ownerServeGate((stream, partition) -> new StreamError.OwnerNotActivated(stream, partition).result());

        var read = access.fetch(PARTITION, 0, 10).await();

        assertThat(read.isFailure()).as("an un-activated owner serves nothing: %s", read).isTrue();
    }

    /// Control: the same read on an activated owner serves the ring.
    @Test
    void fetch_ownerActivated_servesTheRing() {
        var read = access.fetch(PARTITION, 0, 10).await();

        assertThat(read.isSuccess()).isTrue();
        assertThat(read.unwrap()).hasSize(1);
    }

    private static Serializer identitySerializer() {
        return new Serializer() {
            @SuppressWarnings("unchecked")
            @Override
            public <T> byte[] encode(T object) {
                return (byte[]) object;
            }

            @Override
            public <T> void write(ByteBuf byteBuf, T object) {
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
            public <T> T read(ByteBuf byteBuf) {
                var bytes = new byte[byteBuf.readableBytes()];

                byteBuf.readBytes(bytes);

                return (T) bytes;
            }
        };
    }
}
