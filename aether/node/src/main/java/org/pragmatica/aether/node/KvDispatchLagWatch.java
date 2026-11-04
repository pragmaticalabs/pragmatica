// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.ArrayDeque;
import java.util.List;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;
import java.util.stream.IntStream;

import org.pragmatica.cluster.state.kvstore.KVStore.DeliveryMetrics;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.Unit;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;
import org.pragmatica.utility.warning.OperatorWarnings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Unit.unit;


/// The `kv-dispatch-lagging` operator warning (KVStore dispatcher design §3.6): raised on a SUSTAINED POSITIVE SLOPE of
/// `kv.dispatch.lag.batches` or `kv.dispatch.queue.bytes`, never on a threshold alone. Each [#tick] samples the store's
/// delivery metrics. A series whose last [#WINDOW_SAMPLES] samples never fell and ended above where they began raises the
/// warning (the subject is the series) and starts a new window; a series that raised it and is back to zero raises
/// `kv-dispatch-caught-up`. Driven by one scheduler tick, so its state is confined to that thread.
public final class KvDispatchLagWatch {
    /// One sample per [#TICK]: a 30 s window [guess: window and tick].
    public static final int WINDOW_SAMPLES = 30;
    public static final TimeSpan TICK = TimeSpan.timeSpan(1).seconds();
    private static final Logger LOG = LoggerFactory.getLogger(KvDispatchLagWatch.class);

    private final Supplier<DeliveryMetrics> metrics;
    private final OperatorWarningSink warnings;

    private final List<Series> series = List.of(new Series("kv.dispatch.lag.batches", DeliveryMetrics::lagBatches),
                                                new Series("kv.dispatch.queue.bytes",
                                                           DeliveryMetrics::queueBytesEstimate));

    private KvDispatchLagWatch(Supplier<DeliveryMetrics> metrics, OperatorWarningSink warnings) {
        this.metrics = metrics;
        this.warnings = warnings;
    }

    public static KvDispatchLagWatch kvDispatchLagWatch(Supplier<DeliveryMetrics> metrics,
                                                        OperatorWarningSink warnings) {
        return new KvDispatchLagWatch(metrics, warnings);
    }

    /// Takes one sample of every series and raises what it calls for.
    public Unit tick() {
        var sample = metrics.get();

        series.forEach(one -> one.sample(sample, warnings));

        return unit();
    }

    /// One lag series and its window. Mutable, confined to the tick thread.
    private static final class Series {
        private final String name;
        private final ToLongFunction<DeliveryMetrics> read;
        private final ArrayDeque<Long> window = new ArrayDeque<>();
        private boolean reported;

        private Series(String name, ToLongFunction<DeliveryMetrics> read) {
            this.name = name;
            this.read = read;
        }

        private Unit sample(DeliveryMetrics sample, OperatorWarningSink warnings) {
            var value = read.applyAsLong(sample);

            window.addLast(value);
            if (window.size() > WINDOW_SAMPLES) {
                window.removeFirst();
            }

            return sustainedRise()
                   ? lagging(sample, warnings)
                   : caughtUpIfDrained(value, warnings);
        }

        /// A full window that never fell and ended higher than it began.
        private boolean sustainedRise() {
            var samples = List.copyOf(window);

            return samples.size() == WINDOW_SAMPLES
                   && IntStream.range(1,
                                      samples.size())
                               .allMatch(i -> samples.get(i) >= samples.get(i - 1))
                   && samples.getLast() > samples.getFirst();
        }

        private Unit lagging(DeliveryMetrics sample, OperatorWarningSink warnings) {
            OperatorWarnings.raise(LOG,
                                   warnings,
                                   OperatorWarningCode.KV_DISPATCH_LAGGING,
                                   name,
                                   "KV dispatch is falling behind: {} rose from {} to {} over {} samples without falling; the oldest undelivered batch is {} ms old",
                                   name,
                                   window.getFirst(),
                                   window.getLast(),
                                   WINDOW_SAMPLES,
                                   sample.lagOldestAgeMillis());
            reported = true;
            window.clear();

            return unit();
        }

        private Unit caughtUpIfDrained(long value, OperatorWarningSink warnings) {
            return reported && value == 0L
                   ? caughtUp(warnings)
                   : unit();
        }

        private Unit caughtUp(OperatorWarningSink warnings) {
            OperatorWarnings.raise(LOG,
                                   warnings,
                                   OperatorWarningCode.KV_DISPATCH_CAUGHT_UP,
                                   name,
                                   "KV dispatch caught up: {} is back to zero",
                                   name);
            reported = false;

            return unit();
        }
    }
}
