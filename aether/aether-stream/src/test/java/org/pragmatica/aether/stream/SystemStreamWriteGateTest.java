// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.aether.slice.StreamAccess;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.StreamPublisher;
import org.pragmatica.aether.slice.stream.SystemStreams;
import org.pragmatica.lang.Result;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #1338: system streams are READ-ONLY to blueprints, slices and the Management API. The gate sits on the one write
/// operation ([StreamWriteRouter]) and on [DefaultStreamPublisher], keyed on the FULL `system:` address, and the only
/// writer it admits is the node's own system publisher ([SystemStreamFactories], holding [SystemWriteGrant]).
///
/// Every refusal test is paired with the control that its instrument can see a write at all: the same call against a
/// non-system stream lands, and the real system publisher lands on the same ring.
class SystemStreamWriteGateTest {
    private static final String SYSTEM = SystemStreams.CLUSTER_EVENTS.asString();
    private static final String SLICE = "org.acme:consumer:1.0.0";

    private static StreamPartitionManager manager() {
        var manager = StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE);

        manager.createStream(StreamConfig.streamConfig(SYSTEM)).onFailure(cause -> fail(cause.message()));
        manager.createStream(StreamConfig.streamConfig("com.acme:orders:1.0.0")).onFailure(cause -> fail(cause.message()));
        return manager;
    }

    private static long events(StreamPartitionManager manager, String stream) {
        return manager.partitionInfo(stream, 0).unwrap().eventCount();
    }

    /// A deployed slice whose `[streams.X]` alias resolves to `target` — what a blueprint `External` source does.
    private static ProvisioningContext slice(StreamPartitionManager manager, String target) {
        return ProvisioningContext.provisioningContext()
                                  .withExtension(StreamPartitionManager.class, manager)
                                  .withExtension(Serializer.class, serializer())
                                  .withExtension(Deserializer.class, deserializer())
                                  .withExtension(StreamAddressResolver.class, (_, _) -> Result.success(target))
                                  .withExtension(String.class, SLICE);
    }

    private static StreamConfig declared(String alias) {
        return StreamConfig.streamConfig(alias);
    }

    @SuppressWarnings("unchecked")
    private static StreamAccess<byte[]> access(StreamPartitionManager manager, String target) {
        return (StreamAccess<byte[]>) new StreamAccessFactory().provision(declared("alias"), slice(manager, target))
                                                               .await()
                                                               .onFailure(cause -> fail("provision: " + cause.message()))
                                                               .unwrap();
    }

    @SuppressWarnings("unchecked")
    private static StreamPublisher<byte[]> publisher(StreamPartitionManager manager, String target) {
        return (StreamPublisher<byte[]>) new StreamPublisherFactory().provision(declared("alias"), slice(manager, target))
                                                                     .await()
                                                                     .onFailure(cause -> fail("provision: " + cause.message()))
                                                                     .unwrap();
    }

    @Test
    void sliceStreamAccess_boundToASystemStream_publishIsRefused_andTheRingIsUntouched() {
        var manager = manager();

        try {
            var access = access(manager, SYSTEM);
            var before = events(manager, SYSTEM);
            var outcome = access.publish(new byte[]{1}).await();

            assertThat(outcome.isFailure()).as("outcome=%s", outcome).isTrue();
            outcome.onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.SystemStreamWriteRefused.class));
            assertThat(events(manager, SYSTEM)).isEqualTo(before);
        } finally {
            manager.close();
        }
    }

    @Test
    void sliceStreamPublisher_boundToASystemStream_publishAndBatchAreRefused_andTheRingIsUntouched() {
        var manager = manager();

        try {
            var publisher = publisher(manager, SYSTEM);
            var before = events(manager, SYSTEM);
            var single = publisher.publish(new byte[]{1}).await();
            var batch = publisher.publishBatch(java.util.List.of(new byte[]{1}, new byte[]{2})).await();

            assertThat(single.isFailure()).as("single=%s", single).isTrue();
            batch.onFailure(cause -> fail("batch must report per-event outcomes: " + cause.message()))
                 .onSuccess(outcomes -> assertThat(outcomes).hasSize(2)
                                                            .allSatisfy(outcome -> assertThat(outcome).isInstanceOf(org.pragmatica.aether.slice.stream.PublishOutcome.NotAttempted.class)));
            assertThat(events(manager, SYSTEM)).isEqualTo(before);
        } finally {
            manager.close();
        }
    }

    /// The Management API publishes through the node's shared [StreamWriteRouter]; built without the grant it refuses too.
    @Test
    void managementWriteRouter_refusesASystemStream_singleAndBatch() {
        var manager = manager();

        try {
            var router = StreamWriteRouter.localOnly(manager);
            var single = router.publish(SYSTEM, 0, new byte[]{1}, 1L).await();
            var batch = router.publishBatch(SYSTEM, 0, java.util.List.of(new byte[]{1}), 1L).await();

            assertThat(single.isFailure()).as("single=%s", single).isTrue();
            single.onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.SystemStreamWriteRefused.class));
            batch.onSuccess(outcomes -> assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome).isNotInstanceOf(org.pragmatica.aether.slice.stream.PublishOutcome.Published.class)));
            assertThat(events(manager, SYSTEM)).isZero();
        } finally {
            manager.close();
        }
    }

    /// Controls: the instrument can see a write. The same slice path lands on a non-system stream, and the node's own
    /// system publisher still writes the system ring.
    @Test
    void controls_nonSystemSliceWriteLands_andTheNodesOwnSystemPublisherStillWrites() {
        var manager = manager();

        try {
            access(manager, "com.acme:orders:1.0.0").publish(new byte[]{1}).await()
                                                    .onFailure(cause -> fail("control: a non-system slice write must land: " + cause.message()));
            assertThat(events(manager, "com.acme:orders:1.0.0")).isEqualTo(1L);

            var system = SystemStreamFactories.<byte[]> systemStreamPublisher(SystemStreams.CLUSTER_EVENTS,
                                                                               manager,
                                                                               serializer(),
                                                                               StreamConfig.streamConfig(SYSTEM))
                                              .unwrap();

            system.publish(new byte[]{9}).await().onFailure(cause -> fail("the system publisher must keep writing: " + cause.message()));
            assertThat(events(manager, SYSTEM)).isEqualTo(1L);
        } finally {
            manager.close();
        }
    }

    /// A slice never creates a system ring: if the runtime has not provisioned it, binding fails and nothing is minted.
    @Test
    void sliceBindingAnAbsentSystemStream_failsAndMintsNothing() {
        var manager = StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE);

        try {
            var result = new StreamAccessFactory().provision(declared("alias"), slice(manager, SYSTEM)).await();

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.StreamNotFound.class));
            assertThat(manager.streamInfo(SYSTEM).isPresent()).isFalse();
        } finally {
            manager.close();
        }
    }

    private static Serializer serializer() {
        return new Serializer() {
            @SuppressWarnings("unchecked") @Override public <T> byte[] encode(T o) {return (byte[]) o;}

            @Override public <T> void write(ByteBuf b, T o) {b.writeBytes((byte[]) o);}
        };
    }

    private static Deserializer deserializer() {
        return new Deserializer() {
            @SuppressWarnings("unchecked") @Override public <T> T decode(byte[] b) {return (T) b;}

            @SuppressWarnings("unchecked") @Override public <T> T read(ByteBuf b) {
                var x = new byte[b.readableBytes()];
                b.readBytes(x);
                return (T) x;
            }
        };
    }
}
