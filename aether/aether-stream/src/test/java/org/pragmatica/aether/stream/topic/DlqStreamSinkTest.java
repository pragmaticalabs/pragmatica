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

    /// #1266: an event whose topic envelope does not decode must be QUARANTINED RAW, never thrown out
    /// of `append` — a synchronous throw escapes before the runtime attaches its callbacks, leaving the
    /// dead-letter hold set forever. The entry carries the raw event bytes and a synthetic messageId.
    @Test
    void append_quarantinesAnUndecodableEnvelopeRaw_insteadOfThrowing() {
        var captured = new CopyOnWriteArrayList<DlqEnvelope>();
        var sink = new DlqStreamSink(codec, manager, _ -> capturing(captured));
        var garbage = undecodable();

        var result = org.pragmatica.lang.Result.lift(() -> sink.append(TOPIC_STREAM, 3, 11L, "group-a", garbage, "undecodable", 1))
                                              .async()
                                              .flatMap(promise -> promise)
                                              .await();

        assertThat(result.isSuccess()).describedAs("append must not throw, and the quarantine append must succeed: %s", result)
                                      .isTrue();
        assertThat(captured).hasSize(1);
        assertThat(captured.getFirst().payload()).describedAs("the raw event bytes are preserved for diagnosis")
                                                 .isEqualTo(garbage);
        assertThat(captured.getFirst().messageId()).isEqualTo("undecodable:" + TOPIC_STREAM + ":3:11");
        assertThat(captured.getFirst().sourcePartition()).isEqualTo(3);
        assertThat(captured.getFirst().sourceOffset()).isEqualTo(11L);
        assertThat(captured.getFirst().failingGroup()).isEqualTo("group-a");
    }

    /// #1266 acceptance: garbage, then a good event, on a durable-topic partition. The garbage is
    /// dead-lettered raw, the cursor moves past it, and the good event is delivered — the partition does
    /// not wedge behind a sink that throws.
    @Test
    void malformedTopicEnvelope_isQuarantinedRaw_andPartitionContinues() throws Exception {
        activateTopic();
        var captured = new CopyOnWriteArrayList<DlqEnvelope>();
        var sink = new DlqStreamSink(codec, manager, _ -> capturing(captured));
        var runtime = org.pragmatica.aether.stream.StreamConsumerRuntime.streamConsumerRuntime(manager, sink);
        var delivered = new CopyOnWriteArrayList<String>();
        var garbage = undecodable();
        var config = org.pragmatica.aether.slice.ConsumerConfig.consumerConfig("group-a",
                                                                               1,
                                                                               org.pragmatica.aether.slice.ConsumerConfig.ProcessingMode.ORDERED,
                                                                               org.pragmatica.aether.slice.ConsumerConfig.ErrorStrategy.SKIP);

        try {
            runtime.subscribe(TOPIC_STREAM, 0, config, (offset, payload, ts) -> decodeAndRecord(delivered, payload));
            manager.publishLocal(TOPIC_STREAM, 0, garbage, 1000L);
            manager.publishLocal(TOPIC_STREAM, 0, encodedEnvelope("msg-good", "good"), 2000L);

            var deadline = System.currentTimeMillis() + 3_000;

            while (delivered.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertThat(delivered).describedAs("the good event behind the garbage is delivered").containsExactly("msg-good");
            assertThat(captured).describedAs("exactly one quarantine entry, carrying the garbage bytes").hasSize(1);
            assertThat(captured.getFirst().payload()).isEqualTo(garbage);
            assertThat(runtime.cursorPosition(TOPIC_STREAM, 0, "group-a").or(-1L)).describedAs("the cursor passes the garbage")
                                                                                     .isEqualTo(2L);
        } finally {
            runtime.close();
        }
    }

    /// Positive control for every #1266 test: these bytes genuinely fail the envelope decode.
    private byte[] undecodable() {
        var garbage = "not-a-topic-envelope".getBytes(UTF_8);

        assertThat(org.pragmatica.lang.Result.lift(() -> codec.<TopicEventEnvelope>decode(garbage)).isFailure())
                  .describedAs("control: the garbage must not decode as a topic envelope")
                  .isTrue();

        return garbage;
    }

    /// Stands in for `StreamConsumerManager.deliverTopicEvent`, which lifts the same decode: garbage
    /// fails every attempt without reaching a handler.
    private Promise<org.pragmatica.lang.Unit> decodeAndRecord(List<String> delivered, byte[] payload) {
        return org.pragmatica.lang.Result.lift(() -> codec.<TopicEventEnvelope>decode(payload))
                                         .onSuccess(envelope -> delivered.add(envelope.messageId()))
                                         .async()
                                         .mapToUnit();
    }

    private void activateTopic() {
        DurableTopicSubstrate.durableTopicSubstrate(manager)
                             .activateTopic(ADDRESS,
                                            org.pragmatica.aether.resource.DurableTopicSpec.durableTopicSpec(1,
                                                                                                             2,
                                                                                                             2,
                                                                                                             org.pragmatica.lang.parse.TimeSpan.timeSpan("7d")
                                                                                                                                               .unwrap())
                                                                                           .unwrap())
                             .onFailure(cause -> fail(cause.message()));
    }

    private static StreamPublisher<DlqEnvelope> capturing(List<DlqEnvelope> sink) {
        return entry -> {
            sink.add(entry);

            return Promise.unitPromise();
        };
    }
}
