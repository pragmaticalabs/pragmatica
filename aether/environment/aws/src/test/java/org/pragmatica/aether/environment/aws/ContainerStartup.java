// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.

package org.pragmatica.aether.environment.aws;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.ToStringConsumer;

/// Bounded startup retry with per-attempt diagnostics for test containers (#2927).
///
/// A CI run once failed with "Container exited with code 126" after the 60 s log wait and no
/// container output, so the cause was unknowable. Each attempt here gets a FRESH container with a
/// log consumer attached before start; when an attempt fails its container output is captured
/// before the container is stopped, and if every attempt fails the thrown exception carries every
/// attempt's failure chain and logs. Test-support code: exceptions are the JUnit failure channel.
final class ContainerStartup {
    private static final int MAX_LOG_CHARS = 8_000;

    private ContainerStartup() {}

    static <C extends GenericContainer<C>> C startWithRetry(Supplier<C> factory, int attempts) {
        var failures = new ArrayList<String>();
        for (int attempt = 1; attempt <= attempts; attempt++) {
            var container = factory.get();
            var consumer = new ToStringConsumer();
            container.withLogConsumer(consumer);
            try {
                container.start();
                return container;
            } catch (RuntimeException e) {
                failures.add(describeFailure(attempt, attempts, container, consumer, e));
                stopQuietly(container);
            }
        }
        throw new IllegalStateException("Container failed to start in " + attempts + " attempt(s):\n"
                                        + String.join("\n", failures));
    }

    private static String describeFailure(int attempt,
                                          int attempts,
                                          GenericContainer<?> container,
                                          ToStringConsumer consumer,
                                          RuntimeException e) {
        return "--- attempt " + attempt + "/" + attempts + " failed ---\n"
               + causeChain(e) + "\n--- container log ---\n" + tail(capturedLogs(container, consumer));
    }

    private static String causeChain(Throwable t) {
        var chain = new ArrayList<String>();
        for (Throwable c = t; c != null && chain.size() < 8; c = c.getCause()) {
            chain.add(c.getClass().getSimpleName() + ": " + c.getMessage());
        }
        return String.join("\n  caused by ", chain);
    }

    private static String capturedLogs(GenericContainer<?> container, ToStringConsumer consumer) {
        var streamed = consumer.toString(StandardCharsets.UTF_8);
        var fetched = fetchLogs(container);
        return fetched.length() > streamed.length() ? fetched : streamed;
    }

    private static String fetchLogs(GenericContainer<?> container) {
        try {
            return container.getLogs();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String tail(String logs) {
        if (logs.isBlank()) {
            return "<no container output captured>";
        }
        return logs.length() <= MAX_LOG_CHARS
               ? logs
               : "...(truncated)...\n" + logs.substring(logs.length() - MAX_LOG_CHARS);
    }

    private static void stopQuietly(GenericContainer<?> container) {
        try {
            container.stop();
        } catch (RuntimeException ignored) {
            // best effort cleanup of a container that already failed to start
        }
    }
}
