// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.cluster.state.kvstore.DispatcherFailureSink;
import org.pragmatica.cluster.state.kvstore.HeldDelivery;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.NotificationDelivery;
import org.pragmatica.cluster.state.kvstore.StructuredKey;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;

import static org.assertj.core.api.Assertions.assertThat;

/// D1 item 10 (KVStore dispatcher design §3.6): `kv-dispatch-lagging` fires on a sustained positive slope of the dispatch lag,
/// never on a threshold alone, and `kv-dispatch-caught-up` closes it once the queue drains. Each arm drives a real dispatched store
/// whose dispatcher is held, so the lag is what the writes make it, and ticks the watch once per sample. Absence is proven against a
/// sentinel raised through the same FIFO hand-off after the ticks.
@Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class KvDispatchLagWatchTest {
    private static final String BATCHES = "kv.dispatch.lag.batches";

    record Key(String value) implements StructuredKey {}

    private final HeldDelivery hold = HeldDelivery.heldDelivery();
    private final KVStore<StructuredKey, Object> store = new KVStore<>(MessageRouter.mutable(),
                                                                       new Serializer() {
                                                                           @Override
                                                                           public <T> void write(ByteBuf buffer, T value) {}
                                                                       },
                                                                       new Deserializer() {
                                                                           @Override
                                                                           public <T> T read(ByteBuf buffer) {
                                                                               return null;
                                                                           }
                                                                       },
                                                                       NotificationDelivery.dispatched("lag-watch",
                                                                                                       DispatcherFailureSink.loggingOnly(),
                                                                                                       hold));
    private final List<OperatorWarning> raised = new CopyOnWriteArrayList<>();
    private final OperatorWarningSink sink = OperatorWarningSink.handingOffTo(raised::add);
    private final KvDispatchLagWatch watch = KvDispatchLagWatch.kvDispatchLagWatch(store::deliveryMetrics, sink);

    @AfterEach
    void closeStore() {
        hold.release();
        store.close();
    }

    /// One write per sample while the dispatcher is held: the lag climbs for a whole window. Then the hold is released and the queue
    /// drains. Mutation: the slope test never holds (`sustainedRise` false): no warning, red; drop the caught-up recovery: red.
    @Test
    void laggingDispatcher_raisesTheSlopeWarning() {
        hold.holdFrom(store.enqueuedSeq() + 1);
        for (int i = 0; i < KvDispatchLagWatch.WINDOW_SAMPLES; i++) {
            put("k" + i);
            watch.tick();
        }

        assertThat(hold.awaitHeld(10)).as("the dispatcher is held, so the lag is the writes'").isTrue();
        assertThat(store.deliveryMetrics()
                        .lagBatches()).as("control: the lag climbed by one per write")
                                      .isEqualTo(KvDispatchLagWatch.WINDOW_SAMPLES);
        awaitRaised(OperatorWarningCode.KV_DISPATCH_LAGGING, BATCHES);
        hold.release();
        awaitDrained();
        watch.tick();
        awaitRaised(OperatorWarningCode.KV_DISPATCH_CAUGHT_UP, BATCHES);
    }

    /// A high lag that stays where it is: no slope, so nothing. Mutation: fire on the level (`last > 0` instead of `last > first`):
    /// the steady lag raises the warning, red.
    @Test
    void steadyHighLag_raisesNothing() {
        hold.holdFrom(store.enqueuedSeq() + 1);
        for (int i = 0; i < 10; i++) {
            put("k" + i);
        }

        assertThat(hold.awaitHeld(10)).isTrue();
        for (int i = 0; i < 2 * KvDispatchLagWatch.WINDOW_SAMPLES; i++) {
            watch.tick();
        }

        assertThat(store.deliveryMetrics()
                        .lagBatches()).as("control: the lag is high").isEqualTo(10);
        sink.accept(OperatorWarning.operatorWarning(OperatorWarningCode.DEPLOY_WARNING, "sentinel", "sentinel"));
        awaitRaised(OperatorWarningCode.DEPLOY_WARNING, "sentinel");
        assertThat(raised).extracting(OperatorWarning::code)
                          .doesNotContain(OperatorWarningCode.KV_DISPATCH_LAGGING);
    }

    private void put(String key) {
        store.process(store.createBatch(List.of(new KVCommand.Put<StructuredKey, Object>(new Key(key), key))));
    }

    private void awaitDrained() {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

        while (store.deliveryMetrics()
                    .lagBatches() > 0 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }

        assertThat(store.deliveryMetrics()
                        .lagBatches()).as("the queue drained after the release").isZero();
    }

    private void awaitRaised(OperatorWarningCode code, String subject) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

        while (!raisedOne(code, subject) && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }

        assertThat(raisedOne(code, subject)).as("%s raised for %s; raised: %s", code, subject, raised).isTrue();
    }

    private boolean raisedOne(OperatorWarningCode code, String subject) {
        return raised.stream()
                     .anyMatch(warning -> warning.code() == code && warning.subject()
                                                                           .equals(subject));
    }
}
