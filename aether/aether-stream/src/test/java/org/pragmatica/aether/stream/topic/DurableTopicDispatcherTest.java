// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.topic;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.resource.DurableTopicSpec;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.stream.DefaultStreamPublisher;
import org.pragmatica.aether.stream.StreamConsumerRuntime;
import org.pragmatica.aether.stream.StreamError;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.parse.TimeSpan;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.pragmatica.aether.stream.StreamConsumerRuntime.streamConsumerRuntime;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// Pins durable-pubsub-spec §6 dispatch and the §6→§9 exhaust path end to end on one node, over
/// the REAL wire format (the generated `TopicCodecsStream` aggregate): envelopes decode, payload
/// bytes reach the invoker in per-partition order, the handler promise is the ack, and a
/// poison event lands in the topic's DLQ stream as a GROUP-ATTRIBUTED [DlqEnvelope] while the
/// partition unblocks past it. The min-sync replication barrier is deliberately absent (single
/// node, `publishLocal` feed) — it is #410's proven machinery, orthogonal to dispatch mechanics.
class DurableTopicDispatcherTest {
    private static final String ADDRESS = "org.example:orders:1.0.0";
    private static final String TOPIC_STREAM = "topic:" + ADDRESS;
    private static final Artifact SUBSCRIBER = Artifact.artifact("org.example:orders-slice:1.0.0").unwrap();
    private static final MethodName METHOD = MethodName.methodName("onOrderCompleted").unwrap();

    private SliceCodec codec;
    private StreamPartitionManager manager;
    private StreamConsumerRuntime runtime;
    private DlqStreamSink dlqSink;

    @BeforeEach
    void setUp() {
        codec = SliceCodec.sliceCodec(FrameworkCodecs.frameworkCodecs(), TopicCodecsStream.CODECS);
        manager = streamPartitionManager();
        dlqSink = new DlqStreamSink(codec,
                                    manager,
                                    dlqStream -> DefaultStreamPublisher.streamPublisher(manager,
                                                                                        codec,
                                                                                        dlqStream,
                                                                                        1,
                                                                                        Option.none()));
        runtime = streamConsumerRuntime(manager, dlqSink);
    }

    @AfterEach
    void tearDown() throws Exception {
        runtime.close();
        manager.close();
    }

    private DurableTopicSpec spec(int partitions) {
        return DurableTopicSpec.durableTopicSpec(partitions,
                                                 2,
                                                 2,
                                                 TimeSpan.timeSpan("7d").unwrap())
                               .unwrap();
    }

    private void activate(DurableTopicSpec spec) {
        DurableTopicSubstrate.durableTopicSubstrate(manager)
                             .activateTopic(ADDRESS, spec)
                             .onFailure(cause -> fail(cause.message()));
    }

    private void publishEnvelope(int partition, String messageId, String payload) {
        var envelope = new TopicEventEnvelope(messageId, System.currentTimeMillis(), payload.getBytes(UTF_8));

        manager.publishLocal(TOPIC_STREAM,
                             partition,
                             codec.encode(envelope),
                             System.currentTimeMillis())
               .onFailure(cause -> fail(cause.message()));
    }

    @Test
    void attachGroup_deliversEnvelopePayloads_inOrder_andAckAdvancesCursor() throws Exception {
        var spec = spec(1);

        activate(spec);
        var delivered = new CopyOnWriteArrayList<String>();
        var latch = new CountDownLatch(2);
        var dispatcher = DurableTopicDispatcher.durableTopicDispatcher(runtime,
                                                                       codec,
                                                                       (artifact, method, payload) -> {
                                                                           assertThat(artifact).isEqualTo(SUBSCRIBER);
                                                                           assertThat(method).isEqualTo(METHOD);
                                                                           delivered.add(new String(payload, UTF_8));
                                                                           latch.countDown();

                                                                           return Promise.unitPromise();
                                                                       });

        dispatcher.attachGroup(ADDRESS, spec, SUBSCRIBER, METHOD).onFailure(cause -> fail(cause.message()));
        publishEnvelope(0, "msg-1", "first");
        publishEnvelope(0, "msg-2", "second");
        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(delivered).containsExactly("first", "second");
        var groupId = DurableGroupIdentity.groupId(SUBSCRIBER, METHOD);

        assertThat(runtime.cursorPosition(TOPIC_STREAM, 0, groupId).or(-1L)).isEqualTo(2L);
    }

    @Test
    void attachGroup_isIdempotent_repeatAttachSucceeds() {
        var spec = spec(2);

        activate(spec);
        var dispatcher = DurableTopicDispatcher.durableTopicDispatcher(runtime,
                                                                       codec,
                                                                       (a, m, p) -> Promise.unitPromise());

        dispatcher.attachGroup(ADDRESS, spec, SUBSCRIBER, METHOD).onFailure(cause -> fail(cause.message()));
        dispatcher.attachGroup(ADDRESS, spec, SUBSCRIBER, METHOD)
                  .onFailure(cause -> fail("repeat attach must succeed: " + cause.message()));
    }

    @Test
    void detachGroup_isIdempotent_andRepeatDetachSucceeds() {
        var spec = spec(1);

        activate(spec);
        var dispatcher = DurableTopicDispatcher.durableTopicDispatcher(runtime,
                                                                       codec,
                                                                       (a, m, p) -> Promise.unitPromise());

        dispatcher.attachGroup(ADDRESS, spec, SUBSCRIBER, METHOD).onFailure(cause -> fail(cause.message()));
        dispatcher.detachGroup(ADDRESS, spec, SUBSCRIBER, METHOD).onFailure(cause -> fail(cause.message()));
        dispatcher.detachGroup(ADDRESS, spec, SUBSCRIBER, METHOD)
                  .onFailure(cause -> fail("repeat detach must succeed: " + cause.message()));
    }

    /// The §6→§9 money path: a poison event exhausts its 5 attempts, lands in `topic:<addr>.dlq`
    /// as a group-attributed [DlqEnvelope] with the ORIGINAL messageId (the idempotency key
    /// surviving where offsets cannot), the source cursor advances past it, and the next event is
    /// dispatched — the partition stalls only while dead-lettering, never permanently.
    @Test
    void retryExhaustion_landsGroupAttributedEnvelopeInDlqStream_andUnblocksPartition() throws Exception {
        var spec = spec(1);

        activate(spec);
        var delivered = new CopyOnWriteArrayList<String>();
        var tailLatch = new CountDownLatch(1);
        var dispatcher = DurableTopicDispatcher.durableTopicDispatcher(runtime,
                                                                       codec,
                                                                       (artifact, method, payload) -> {
                                                                           var text = new String(payload, UTF_8);

                                                                           delivered.add(text);
                                                                           if ("poison".equals(text)) {
                                                                           return StreamError.General.BUFFER_EMPTY.promise();
                                                                       }

                                                                           tailLatch.countDown();

                                                                           return Promise.unitPromise();
                                                                       });

        dispatcher.attachGroup(ADDRESS, spec, SUBSCRIBER, METHOD).onFailure(cause -> fail(cause.message()));
        publishEnvelope(0, "poison-id", "poison");
        publishEnvelope(0, "ok-id", "ok");
        assertThat(tailLatch.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(delivered).endsWith("ok");
        // 5 attempts on the poison event, then exactly one delivery of the next.
        assertThat(delivered.stream().filter("poison"::equals).count()).isEqualTo(5);
        var entries = dlqSink.read(TOPIC_STREAM, 10);
        var groupId = DurableGroupIdentity.groupId(SUBSCRIBER, METHOD);

        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst().failingGroup()).isEqualTo(groupId);
        assertThat(entries.getFirst().offset()).isEqualTo(0L);
        assertThat(entries.getFirst().payload()).isEqualTo("poison".getBytes(UTF_8));
        assertThat(entries.getFirst().attemptCount()).isEqualTo(5);
        var rawDlq = manager.readLocal(TOPIC_STREAM + ".dlq", 0, 0, 10).unwrap();

        assertThat(rawDlq).hasSize(1);
        DlqEnvelope dlqEnvelope = codec.decode(rawDlq.getFirst().data());

        assertThat(dlqEnvelope.messageId()).isEqualTo("poison-id");
        assertThat(dlqEnvelope.sourceTopic()).isEqualTo(ADDRESS);
        assertThat(dlqEnvelope.lastFailureCause()).isNotBlank();
    }

    @Test
    void dlqSinkRead_returnsEmpty_whenNothingDeadLettered() {
        activate(spec(1));
        assertThat(dlqSink.read(TOPIC_STREAM, 10)).isEmpty();
    }
}
