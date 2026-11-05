// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.assembly;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.api.ClusterEvent;
import org.pragmatica.aether.api.ClusterEventAggregator;
import org.pragmatica.aether.node.NodeCodecs;
import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.RetentionMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.stream.FrameworkStreamConsumer;
import org.pragmatica.aether.slice.stream.FrameworkStreamPublisher;
import org.pragmatica.aether.slice.stream.SystemStreams;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.SystemStreamFactories;
import org.pragmatica.cluster.state.kvstore.DispatcherFailureSink;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.NotificationDelivery;
import org.pragmatica.cluster.state.kvstore.StructuredKey;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;
import org.pragmatica.utility.warning.OperatorWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/// D1 item 9 (KVStore dispatcher design §3.5 R4): an operator warning raised by a KV handler during a replay burst
/// crosses the operator-warning hand-off thread, where `isReplaying()` always reads false
/// (`ClusterEventAggregator` condition and recovery paths). Each arm installs a warning silently and replays it
/// through a dispatched store whose handler raises it, as a handler does, on the dispatcher thread, through the node's
/// operator-warning sink into a real aggregator whose replay check is the store's. A live sentinel raised afterwards
/// is the control: the hand-off is one FIFO thread, so once the sentinel is published, anything the replay raised was
/// handled before it.
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ReplayedOperatorWarningTest {
    private static final Logger LOG = LoggerFactory.getLogger(ReplayedOperatorWarningTest.class);
    private static final NodeId SELF = new NodeId("replay-warning-self");
    private static final SliceCodec CODEC = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());
    private static final String SUBJECT = "g:orders[0]";

    record Key(String value) implements StructuredKey {}

    private final MessageRouter.MutableRouter router = MessageRouter.mutable();
    private final InstallingDeserializer deserializer = new InstallingDeserializer();
    private final KVStore<StructuredKey, Object> store = new KVStore<>(router,
                                                                       new Serializer() {
                                                                           @Override
                                                                           public <T> void write(ByteBuf buffer, T value) {}
                                                                       },
                                                                       deserializer,
                                                                       NotificationDelivery.dispatched("replay-warning",
                                                                                                       DispatcherFailureSink.loggingOnly()));
    private final ClusterEventAggregator aggregator = aggregator(store);

    /// Installs whatever map the test sets, so a silent install needs no codec.
    static final class InstallingDeserializer implements Deserializer {
        volatile Map<StructuredKey, Object> next = Map.of();

        @Override
        @SuppressWarnings("unchecked")
        public <T> T read(ByteBuf buffer) {
            return (T) next;
        }
    }

    @AfterEach
    void closeStore() {
        store.close();
    }

    /// Condition path (`ClusterEventAggregator` `onCondition`). Mutation: the aggregator reads `replayingCheck` on the
    /// hand-off thread again: the replayed warning is published, red.
    @Test
    void replayedOperatorWarning_isSuppressedAcrossTheHandOff() {
        var sink = nodeSink();

        assertThat(replayThenSentinel(sink, warning(OperatorWarningCode.DEPLOY_WARNING, "replayed"))).containsExactly("deploy-warning:sentinel");
    }

    /// Recovery path (`ClusterEventAggregator` `onRecovery`): the repair of a shown warning, raised during replay, is
    /// held for the first tick after it. Mutation: as above: it is published at once, red.
    @Test
    void replayedRecovery_isHeldAcrossTheHandOff() {
        var sink = nodeSink();

        raiseLive(sink, warning(OperatorWarningCode.STREAM_CONSUMER_STATE_DIVERGED, SUBJECT));
        awaitPublished("stream-consumer-state-diverged:" + SUBJECT);

        assertThat(replayThenSentinel(sink, warning(OperatorWarningCode.STREAM_CONSUMER_STATE_REPAIRED, SUBJECT))).containsExactly("stream-consumer-state-diverged:" + SUBJECT,
                                                                                                                                 "deploy-warning:sentinel");
    }

    /// The node's operator-warning sink, as ClusterEventsAssembly builds it over the store's replay check.
    private OperatorWarningSink nodeSink() {
        return ClusterEventsAssembly.operatorWarningSink(aggregator, store::isReplaying);
    }

    /// Routes the store's puts to a handler that raises the stored warning, replays `replayed`, then raises a live
    /// sentinel and waits for it; returns `code:subject` of every published warning event, in order.
    private List<String> replayThenSentinel(OperatorWarningSink sink, OperatorWarning replayed) {
        var raisedWhileReplaying = new AtomicReference<Boolean>(false);

        router.addRoute(ValuePut.class,
                        (ValuePut<StructuredKey, Object> put) -> raiseFromHandler(sink, (OperatorWarning) put.cause()
                                                                                                             .value(),
                                                                                  raisedWhileReplaying));
        deserializer.next = Map.of(new Key("w"), replayed);
        assertThat(store.restoreSnapshot(new byte[0])
                        .isSuccess()).isTrue();
        store.replayNotifications();
        assertThat(raisedWhileReplaying.get()).as("the replay burst raised the warning while replaying")
                                   .isTrue();
        raiseLive(sink, warning(OperatorWarningCode.DEPLOY_WARNING, "sentinel"));

        return awaitPublished("deploy-warning:sentinel");
    }

    private void raiseFromHandler(OperatorWarningSink sink, OperatorWarning warning, AtomicReference<Boolean> raisedWhileReplaying) {
        raisedWhileReplaying.set(store.isReplaying());
        raiseLive(sink, warning);
    }

    private static void raiseLive(OperatorWarningSink sink, OperatorWarning warning) {
        OperatorWarnings.raise(LOG, sink, warning.code(), warning.subject(), "{}", warning.message());
    }

    private static OperatorWarning warning(OperatorWarningCode code, String subject) {
        return OperatorWarning.operatorWarning(code, subject, code.code() + " " + subject);
    }

    private List<String> awaitPublished(String codeAndSubject) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

        while (!published().contains(codeAndSubject) && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }

        var published = published();

        assertThat(published).as("the live control was published").contains(codeAndSubject);

        return published;
    }

    private List<String> published() {
        return aggregator.events()
                         .await()
                         .or(List.of())
                         .stream()
                         .map(ReplayedOperatorWarningTest::codeAndSubject)
                         .toList();
    }

    private static String codeAndSubject(ClusterEvent event) {
        return event.details()
                    .get("code") + ":" + event.details()
                                              .get("subject");
    }

    /// The production aggregator over a real single-partition cluster-events stream (as `ClusterEventAggregatorTest`
    /// builds it), owner and leader, with the store's replay check as its `replayingCheck`.
    private static ClusterEventAggregator aggregator(KVStore<StructuredKey, Object> store) {
        var manager = StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE);
        var retention = RetentionPolicy.retentionPolicy(10_000, 64L * 1024 * 1024, Long.MAX_VALUE, RetentionMode.ANY);
        var config = StreamConfig.streamConfig(SystemStreams.CLUSTER_EVENTS.asString(),
                                               1,
                                               retention,
                                               "earliest",
                                               64L * 1024,
                                               ConsistencyMode.EVENTUAL,
                                               1);
        var publisher = SystemStreamFactories.<ClusterEvent>systemStreamPublisher(SystemStreams.CLUSTER_EVENTS,
                                                                                  manager,
                                                                                  CODEC,
                                                                                  config)
                                             .unwrap();
        var consumer = SystemStreamFactories.<ClusterEvent>systemStreamConsumer(SystemStreams.CLUSTER_EVENTS,
                                                                                manager,
                                                                                CODEC,
                                                                                CODEC,
                                                                                config)
                                            .unwrap();
        var pubRef = new AtomicReference<FrameworkStreamPublisher<ClusterEvent>>(publisher);
        var conRef = new AtomicReference<FrameworkStreamConsumer<ClusterEvent>>(consumer);

        return ClusterEventAggregator.clusterEventAggregator(pubRef::get,
                                                             conRef::get,
                                                             () -> true,
                                                             SELF,
                                                             HlcClock.hlcClock(SELF),
                                                             () -> 1,
                                                             store::isReplaying,
                                                             () -> true);
    }
}
