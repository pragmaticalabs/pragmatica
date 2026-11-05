// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.state.kvstore;

import org.pragmatica.lang.Unit;
import org.pragmatica.utility.warning.OperatorWarningCode;


/// How a store delivers its notifications (design §3.1-§3.2).
///
/// - [Inline]: the writer that applied a batch delivers it on its own thread after releasing the store monitor, while
///   still holding the mutation guard: the pre-D1 behaviour, kept for standalone stores and for the tests that assert
///   right after a synchronous `process()` (the design's "inline test dispatcher"). It proves nothing about
///   concurrency and contains nothing: a handler's `Error` reaches the writer, as before.
/// - [Dispatched]: one daemon delivery thread per store; a write only enqueues. Production stores use this.
public sealed interface NotificationDelivery {
    /// The order a replay burst emits keys in, ascending, rank-major (design §3.7, exit (z)). Every key 0 reproduces
    /// the pre-D1 order: all removals, then all puts.
    @FunctionalInterface
    interface ReplayRank {
        int rank(StructuredKey key);

        static ReplayRank flat() {
            return _ -> 0;
        }
    }

    ReplayRank replayRank();

    record Inline(ReplayRank replayRank) implements NotificationDelivery {}

    /// `name` names the store (thread name, log lines); `sink` stops the node on a failure; `hook` runs before each
    /// batch (the held-dispatcher test hook); `events` receives operator events; `slowHandlerNanos` is the watchdog
    /// threshold for one handler invocation.
    record Dispatched(String name,
                      ReplayRank replayRank,
                      DispatcherFailureSink sink,
                      DeliveryHook hook,
                      OperatorEvents events,
                      long slowHandlerNanos) implements NotificationDelivery {}

    /// Operator events the dispatcher raises (operator-attention rule): [OperatorWarningCode#KV_DISPATCHER_FAILED],
    /// [OperatorWarningCode#KV_DISPATCH_READ_AHEAD], [OperatorWarningCode#KV_DISPATCH_SLOW_HANDLER] and its recovery
    /// [OperatorWarningCode#KV_DISPATCH_SLOW_HANDLER_RETURNED]. The dispatcher has already logged each one. Called on the
    /// delivery thread and on the watchdog thread, so it must not block and must not route through KV.
    @FunctionalInterface
    interface OperatorEvents {
        Unit emit(OperatorWarningCode code, String subject, String detail);

        static OperatorEvents none() {
            return (_, _, _) -> Unit.unit();
        }
    }

    /// The #1117 SLOW-APPLY threshold (`RabiaEngine.SLOW_APPLY_THRESHOLD_NANOS`, 5 ms), reused for one handler
    /// invocation (design §3.6). Crossings are counted every time; the stack dump is rate-limited per notification type.
    long DEFAULT_SLOW_HANDLER_NANOS = 5_000_000L;

    static NotificationDelivery inline() {
        return new Inline(ReplayRank.flat());
    }

    static NotificationDelivery inline(ReplayRank replayRank) {
        return new Inline(replayRank);
    }

    static NotificationDelivery dispatched(String name, DispatcherFailureSink sink) {
        return dispatched(name, sink, DeliveryHook.none());
    }

    static NotificationDelivery dispatched(String name, DispatcherFailureSink sink, DeliveryHook hook) {
        return dispatched(name, ReplayRank.flat(), sink, hook);
    }

    /// A host that orders replay bursts (design §3.7, exit (z)) passes its rank; KVStore stays agnostic of the keys.
    static NotificationDelivery dispatched(String name, ReplayRank replayRank, DispatcherFailureSink sink) {
        return dispatched(name, replayRank, sink, DeliveryHook.none());
    }

    static NotificationDelivery dispatched(String name,
                                           ReplayRank replayRank,
                                           DispatcherFailureSink sink,
                                           DeliveryHook hook) {
        return new Dispatched(name, replayRank, sink, hook, OperatorEvents.none(), DEFAULT_SLOW_HANDLER_NANOS);
    }

    /// A production store: no test hook, operator events to `events`, the default slow-handler threshold.
    static NotificationDelivery dispatched(String name,
                                           ReplayRank replayRank,
                                           DispatcherFailureSink sink,
                                           OperatorEvents events) {
        return new Dispatched(name, replayRank, sink, DeliveryHook.none(), events, DEFAULT_SLOW_HANDLER_NANOS);
    }
}
