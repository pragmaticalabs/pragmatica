// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.topic;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.pragmatica.aether.slice.StreamPublisher;
import org.pragmatica.aether.stream.DefaultStreamPublisher;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// Pins durable-pubsub-spec §9's sink mapping: a retry-exhausted event is re-enveloped as a
/// group-attributed [DlqEnvelope] carrying the ORIGINAL messageId (the §8 idempotency key), and
/// `read` maps DLQ-stream entries back to the source-stream view. Routing by stream family
/// ([RoutingDeadLetterSink]) is pinned alongside: only `topic:*` appends reach the durable sink.
class DlqStreamSinkTest {
    private static final String ADDRESS = "org.example:orders:1.0.0";
    private static final String TOPIC_STREAM = "topic:" + ADDRESS;

    private SliceCodec codec;
    private StreamPartitionManager manager;

    @BeforeEach
    void setUp() {
        codec = SliceCodec.sliceCodec(FrameworkCodecs.frameworkCodecs(), TopicCodecsStream.CODECS);
        manager = streamPartitionManager();
    }

    @AfterEach
    void tearDown() throws Exception {
        manager.close();
    }

    private byte[] encodedEnvelope(String messageId, String payload) {
        return codec.encode(new TopicEventEnvelope(messageId, 1234L, payload.getBytes(UTF_8)));
    }

    @Test
    void append_reEnvelopesWithGroupAttribution_preservingMessageId() {
        var captured = new CopyOnWriteArrayList<DlqEnvelope>();
        var sink = new DlqStreamSink(codec, manager, _ -> capturing(captured));

        sink.append(TOPIC_STREAM,
                    2,
                    42L,
                    "group-a",
                    encodedEnvelope("msg-1", "poison"),
                    "boom",
                    5)
            .await()
            .onFailure(cause -> fail(cause.message()));
        assertThat(captured).hasSize(1);
        assertThat(captured.getFirst().messageId()).isEqualTo("msg-1");
        assertThat(captured.getFirst().sourceTopic()).isEqualTo(ADDRESS);
        assertThat(captured.getFirst().sourcePartition()).isEqualTo(2);
        assertThat(captured.getFirst().sourceOffset()).isEqualTo(42L);
        assertThat(captured.getFirst().failingGroup()).isEqualTo("group-a");
        assertThat(captured.getFirst().attemptCount()).isEqualTo(5);
        assertThat(captured.getFirst().lastFailureCause()).isEqualTo("boom");
        assertThat(captured.getFirst().publishedAtMs()).isEqualTo(1234L);
        assertThat(captured.getFirst().payload()).isEqualTo("poison".getBytes(UTF_8));
    }

    @Test
    void read_mapsDlqStreamEntries_toSourceStreamView() {
        DurableTopicSubstrate.durableTopicSubstrate(manager)
                             .activateTopic(ADDRESS,
                                            org.pragmatica.aether.resource.DurableTopicSpec.durableTopicSpec(1,
                                                                                                             2,
                                                                                                             2,
                                                                                                             org.pragmatica.lang.parse.TimeSpan.timeSpan("7d")
                                                                                                                                               .unwrap())
                                                                                           .unwrap())
                             .onFailure(cause -> fail(cause.message()));
        var sink = new DlqStreamSink(codec,
                                     manager,
                                     dlqStream -> DefaultStreamPublisher.streamPublisher(manager,
                                                                                         codec,
                                                                                         dlqStream,
                                                                                         1,
                                                                                         Option.none()));

        sink.append(TOPIC_STREAM,
                    0,
                    7L,
                    "group-a",
                    encodedEnvelope("msg-7", "bad"),
                    "cause-7",
                    5)
            .await()
            .onFailure(cause -> fail(cause.message()));
        var entries = sink.read(TOPIC_STREAM, 10);

        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst().streamName()).isEqualTo(TOPIC_STREAM);
        assertThat(entries.getFirst().offset()).isEqualTo(7L);
        assertThat(entries.getFirst().failingGroup()).isEqualTo("group-a");
        assertThat(entries.getFirst().payload()).isEqualTo("bad".getBytes(UTF_8));
    }

    @Test
    void read_returnsEmpty_whenNothingDeadLettered() {
        var sink = new DlqStreamSink(codec, manager, _ -> capturing(new CopyOnWriteArrayList<>()));

        assertThat(sink.read(TOPIC_STREAM, 10)).isEmpty();
    }

    @Test
    void routingSink_divertsOnlyTopicStreams_toTheDurableSink() {
        var topicAppends = new CopyOnWriteArrayList<DlqEnvelope>();
        var fallback = org.pragmatica.aether.stream.DeadLetterHandler.deadLetterHandler();
        var routing = new RoutingDeadLetterSink(new DlqStreamSink(codec, manager, _ -> capturing(topicAppends)),
                                                fallback);

        routing.append(TOPIC_STREAM,
                       0,
                       1L,
                       "group-a",
                       encodedEnvelope("m", "x"),
                       "err",
                       5)
               .await()
               .onFailure(cause -> fail(cause.message()));
        routing.append("orders",
                       0,
                       1L,
                       "group-b",
                       "raw".getBytes(UTF_8),
                       "err",
                       1)
               .await()
               .onFailure(cause -> fail(cause.message()));
        assertThat(topicAppends).hasSize(1);
        assertThat(fallback.read("orders", 10)).hasSize(1);
        assertThat(routing.read("orders", 10)).hasSize(1);
    }

    private static StreamPublisher<DlqEnvelope> capturing(List<DlqEnvelope> sink) {
        return entry -> {
            sink.add(entry);

            return Promise.unitPromise();
        };
    }
}
