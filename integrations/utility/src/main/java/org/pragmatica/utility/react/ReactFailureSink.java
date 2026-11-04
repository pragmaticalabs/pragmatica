// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.utility.react;

import org.pragmatica.lang.Unit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Where a react executor sends an `Error` thrown by a reaction (KVStore dispatcher design §6.1, owner ruling 6): the
/// same failure path as the dispatcher, which stops the node. The host injects it, as it injects the dispatcher's sink.
@FunctionalInterface
public interface ReactFailureSink {
    Unit fatal(String component, String reaction, Throwable error);

    /// No node to stop: the `Error` is logged at ERROR and nothing else happens.
    static ReactFailureSink loggingOnly() {
        return LoggingSink.INSTANCE;
    }

    enum LoggingSink implements ReactFailureSink {
        INSTANCE;
        private static final Logger log = LoggerFactory.getLogger(ReactFailureSink.class);
        @Override
        public Unit fatal(String component, String reaction, Throwable error) {
            log.error("React executor {} failed in reaction {} with no node to stop", component, reaction, error);

            return Unit.unit();
        }
    }
}
