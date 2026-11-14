// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.scheduler.pg;

import org.pragmatica.lang.Cause;

/// Failures of the scheduler itself (database failures pass through unchanged).
public sealed interface SchedulerError extends Cause {
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
