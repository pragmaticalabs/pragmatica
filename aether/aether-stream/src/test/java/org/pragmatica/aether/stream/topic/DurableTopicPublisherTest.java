// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.topic;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.pragmatica.aether.resource.DurableTopicSpec;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.aether.slice.StreamPublisher;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.parse.TimeSpan;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.utility.KSUID;

import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// Pins durable-pubsub-spec §5/§8 publisher properties at the wrapper seam: every publish wraps
/// the serialized payload in an envelope carrying a FRESH time-sortable messageId (the §8
/// idempotency key) and the publish timestamp. The replication barrier below the wrapper is the
/// #410 machinery, exercised by its own suite — this test proves what the wrapper adds, against a
/// capturing fake, because the barrier's outcome is orthogonal to envelope correctness.
class DurableTopicPublisherTest {
    private static final Serializer STRING_BYTES = new Serializer() {
        @Override
        public <T> void write(io.netty.buffer.ByteBuf byteBuf, T object) {
            byteBuf.writeBytes(String.valueOf(object).getBytes(UTF_8));
        }
    };

    @Test
    void publish_wrapsPayload_withFreshSortableMessageIdAndTimestamp() {
        var captured = new CopyOnWriteArrayList<TopicEventEnvelope>();
        var publisher = new DurableTopicPublisher<String>(STRING_BYTES, capturing(captured));
        var before = System.currentTimeMillis();

        publisher.publish("first").await().onFailure(cause -> fail(cause.message()));
        publisher.publish("second").await().onFailure(cause -> fail(cause.message()));
        var after = System.currentTimeMillis();

        assertThat(captured).hasSize(2);
        assertThat(captured.get(0).messageId()).hasSize(KSUID.STRING_LENGTH);
        assertThat(captured.get(1).messageId()).hasSize(KSUID.STRING_LENGTH);
        assertThat(captured.get(0).messageId()).isNotEqualTo(captured.get(1).messageId());
        assertThat(captured.get(0).payload()).isEqualTo("first".getBytes(UTF_8));
        assertThat(captured.get(1).payload()).isEqualTo("second".getBytes(UTF_8));
        assertThat(captured.get(0).publishedAtMs()).isBetween(before, after);
    }

    @Test
    void durablePublisher_activatesTopicAndDlqStreams_atProvisioning() throws Exception {
        var manager = StreamPartitionManager.streamPartitionManager();

        try {
            var context = ProvisioningContext.provisioningContext()
                                             .withExtension(StreamPartitionManager.class, manager)
                                             .withExtension(Serializer.class, STRING_BYTES);
            var spec = DurableTopicSpec.durableTopicSpec(2,
                                                         2,
                                                         2,
                                                         TimeSpan.timeSpan("7d").unwrap()).unwrap();

            DurableTopicSubstrate.durablePublisher("org.example:orders:1.0.0", spec, context)
                                 .onFailure(cause -> fail(cause.message()))
                                 .onSuccess(publisher -> assertThat(publisher).isInstanceOf(DurableTopicPublisher.class));
            assertThat(manager.partitionBuffer("topic:org.example:orders:1.0.0", 0).isPresent()).isTrue();
            assertThat(manager.partitionBuffer("topic:org.example:orders:1.0.0.dlq", 0).isPresent()).isTrue();
        } finally {
            manager.close();
        }
    }

    private static StreamPublisher<TopicEventEnvelope> capturing(List<TopicEventEnvelope> sink) {
        return new StreamPublisher<>() {
            @Override
            public Promise<Unit> publish(TopicEventEnvelope event) {
                sink.add(event);

                return Promise.unitPromise();
            }

            @Override
            public Promise<Unit> publishBatch(List<TopicEventEnvelope> events) {
                sink.addAll(events);

                return Promise.unitPromise();
            }
        };
    }
}
