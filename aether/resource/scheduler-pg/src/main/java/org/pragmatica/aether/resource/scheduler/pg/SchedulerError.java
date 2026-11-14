// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.scheduler.pg;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.utils.Causes;


/// Failures of the scheduler itself (database failures pass through unchanged).
public sealed interface SchedulerError extends Cause {
    /// The delay cannot be expressed in milliseconds (`Duration.toMillis` overflowed); nothing was written.
    record DelayOutOfRange(Cause origin, String message) implements SchedulerError, Cause.Wrapped {
        static final Fn1<DelayOutOfRange, Cause> FACTORY = Causes.forOneValue("Delay is out of range",
                                                                              DelayOutOfRange::new);

        static DelayOutOfRange delayOutOfRange(Cause origin) {
            return FACTORY.apply(origin);
        }
    }

    enum General implements SchedulerError {
        CLOSED("Scheduler is closed");
        private final String message;
        General(String message) {
            this.message = message;
        }
        @Override
        public String message() {
            return message;
        }
    }
}
