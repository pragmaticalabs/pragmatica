// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.invoke;

import java.util.List;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;

/// Test-only: capture the formatted WARN messages one class's logger emits into a caller-owned list, as they
/// arrive. The returned runnable detaches the capture; call it in a `finally`. The capture itself is
/// [org.pragmatica.testlogging.LogCapture], which restores the log4j2 configuration it displaced.
final class LogCapture {
    private LogCapture() {}

    static Runnable warningsOf(Class<?> loggerOwner, List<String> sink) {
        var capture = org.pragmatica.testlogging.LogCapture.capture(loggerOwner.getName(),
                                                                    Level.WARN,
                                                                    true,
                                                                    event -> deliver(event, sink));

        return capture::close;
    }

    private static void deliver(LogEvent event, List<String> sink) {
        if (event.getLevel() == Level.WARN) {
            sink.add(event.getMessage().getFormattedMessage());
        }
    }
}
