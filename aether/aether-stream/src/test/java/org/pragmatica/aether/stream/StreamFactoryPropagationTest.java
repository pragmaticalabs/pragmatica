// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// stream-offheap-budget-spec §11.3 / reconciliation #10: the app stream provisioning factories must
/// PROPAGATE a `STREAM_MEMORY_EXCEEDED` create failure (they previously swallowed every create
/// outcome). A floor-exhausted budget fails the provisioning `Promise`, which fails the slice
/// resource provisioning — surfacing the deployment as FAILED rather than "deployed but dead".
class StreamFactoryPropagationTest {
    private static final RetentionPolicy RETENTION = RetentionPolicy.retentionPolicy(10_000, 4 * 1024 * 1024L, 60_000L);

    /// EVENTUAL (DROP_OLDEST) so the manager does not require AHSE; 4 partitions; a 1-byte budget so the
    /// per-partition floor cannot be admitted → `STREAM_MEMORY_EXCEEDED`.
    private static StreamConfig exhaustingConfig() {
        return StreamConfig.streamConfig("propagation-stream", 4, RETENTION, "latest", 1024 * 1024L, ConsistencyMode.EVENTUAL, 1);
    }

    private static StreamPartitionManager tinyBudgetManager() {
        return StreamPartitionManager.streamPartitionManager(1L);
    }

    private static ProvisioningContext publisherContext(StreamPartitionManager manager) {
        return ProvisioningContext.provisioningContext()
                                  .withExtension(StreamPartitionManager.class, manager)
                                  .withExtension(Serializer.class, identitySerializer());
    }

    private static ProvisioningContext accessContext(StreamPartitionManager manager) {
        return publisherContext(manager).withExtension(Deserializer.class, identityDeserializer());
    }

    @Test
    void streamPublisherFactory_provision_propagatesMemoryExceeded() {
        var manager = tinyBudgetManager();

        try {
            new StreamPublisherFactory().provision(exhaustingConfig(), publisherContext(manager))
                                        .await()
                                        .onSuccess(_ -> fail("Expected provisioning to fail with STREAM_MEMORY_EXCEEDED"))
                                        .onFailure(cause -> assertThat(cause).isEqualTo(StreamError.General.STREAM_MEMORY_EXCEEDED));
        } finally {
            manager.close();
        }
    }

    @Test
    void streamAccessFactory_provision_propagatesMemoryExceeded() {
        var manager = tinyBudgetManager();

        try {
            new StreamAccessFactory().provision(exhaustingConfig(), accessContext(manager))
                                     .await()
                                     .onSuccess(_ -> fail("Expected provisioning to fail with STREAM_MEMORY_EXCEEDED"))
                                     .onFailure(cause -> assertThat(cause).isEqualTo(StreamError.General.STREAM_MEMORY_EXCEEDED));
        } finally {
            manager.close();
        }
    }

    private static Serializer identitySerializer() {
        return new Serializer() {
            @SuppressWarnings("unchecked") @Override public <T> byte[] encode(T object) {return (byte[]) object;}

            @Override public <T> void write(ByteBuf byteBuf, T object) {byteBuf.writeBytes((byte[]) object);}
        };
    }

    private static Deserializer identityDeserializer() {
        return new Deserializer() {
            @SuppressWarnings("unchecked") @Override public <T> T decode(byte[] bytes) {return (T) bytes;}

            @SuppressWarnings("unchecked") @Override public <T> T read(ByteBuf byteBuf) {
                var bytes = new byte[byteBuf.readableBytes()];
                byteBuf.readBytes(bytes);
                return (T) bytes;
            }
        };
    }
}
