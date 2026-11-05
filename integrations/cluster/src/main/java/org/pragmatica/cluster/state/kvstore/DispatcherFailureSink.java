// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.state.kvstore;

import java.util.concurrent.atomic.AtomicBoolean;

import org.pragmatica.lang.Unit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Where a store's notification dispatcher sends a failure it cannot contain (design §3.2, owner ruling 1): stops the
/// node that owns the store. KVStore cannot see the node, so the host injects it: production halts the JVM after a
/// bounded log flush, an in-JVM host (Forge, Ember) stops only that node. Must be idempotent: the core fatal-escape hook
/// and a store may both call it for the same failure.
@FunctionalInterface
public interface DispatcherFailureSink {
    Unit stopNode(String reason, Throwable cause);

    /// No node to stop (an inline test store, a standalone store): the failure is logged at ERROR and nothing else happens.
    static DispatcherFailureSink loggingOnly() {
        return LoggingSink.INSTANCE;
    }

    /// `sink`, called at most once (R4.4): the core fatal-escape hook and a store may both report the same failure, and
    /// a host's stop must not run twice. A later call logs one line and returns.
    static DispatcherFailureSink once(DispatcherFailureSink sink) {
        return new Once(sink, new AtomicBoolean());
    }

    record Once(DispatcherFailureSink sink, AtomicBoolean called) implements DispatcherFailureSink {
        private static final Logger log = LoggerFactory.getLogger(DispatcherFailureSink.class);

        @Override
        public Unit stopNode(String reason, Throwable cause) {
            return called.compareAndSet(false, true)
                   ? sink.stopNode(reason, cause)
                   : ignored(reason, cause);
        }

        private static Unit ignored(String reason, Throwable cause) {
            log.error("KV dispatcher failure after the node stop was already requested ({}): {}",
                      reason,
                      cause.toString());

            return Unit.unit();
        }
    }

    enum LoggingSink implements DispatcherFailureSink {
        INSTANCE;
        private static final Logger log = LoggerFactory.getLogger(DispatcherFailureSink.class);
        @Override
        public Unit stopNode(String reason, Throwable cause) {
            log.error("KV dispatcher failure with no node to stop ({})", reason, cause);

            return Unit.unit();
        }
    }
}
