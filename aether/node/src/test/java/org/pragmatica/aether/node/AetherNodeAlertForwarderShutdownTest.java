// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import org.pragmatica.aether.config.AlertConfig;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.config.ConfigService;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// With webhooks enabled, the node's `AlertForwarder` builds a JDK `HttpClient` (a selector-manager thread)
/// that nothing closed on stop (#1097's class). Pinned by the thread itself, found as the selector thread that
/// did not exist before boot, then watched through `stop()`; the node is held reachable to the end so a GC cannot end the
/// thread instead of `stop()`.
///
/// The thread is identified, not counted: every `HttpClient` an earlier test in the same JVM left unreachable keeps its
/// selector thread until a GC collects the client, so a count taken before boot can fall during the test and hide the new
/// thread (the control then failed with the thread present).
class AetherNodeAlertForwarderShutdownTest {
    @TempDir
    Path tempDir;

    private AetherNode node;

    @AfterEach
    void tearDown() {
        if (node != null) {
            node.stop().await(timeSpan(10).seconds()).onFailure(cause -> fail("stop failed in teardown: " + cause.message()));
        }
        ConfigService.clear();
        ResourceProvider.clear();
    }

    @Test
    @Timeout(value = 60, unit = SECONDS)
    void stop_endsTheWebhookClientSelectorThread() throws InterruptedException {
        var existing = selectorThreads();
        var config = AetherNodeContentStorageWarnBootTest.minimalConfig(Option.none(),
                                                                       Option.none(),
                                                                       ConfigurationProvider.builder().build(),
                                                                       tempDir)
                                                         .withAlerts(Option.some(AlertConfig.alertConfig(List.of("http://127.0.0.1:1/hook"))));

        node = AetherNode.aetherNode(config, () -> {})
                         .onFailure(cause -> fail("boot must succeed: " + cause.message()))
                         .unwrap();
        var running = node;

        assertThat(eventually(() -> !startedSince(existing).isEmpty())).as("control: webhook-enabled boot owns a selector thread")
                                                                       .isTrue();
        var owned = startedSince(existing);

        node.stop().await(timeSpan(10).seconds()).onFailure(cause -> fail("stop must succeed: " + cause.message()));
        node = null;
        assertThat(eventually(() -> owned.stream().noneMatch(Thread::isAlive))).as("the boot selector threads %s after stop", owned)
                                                                              .isTrue();
        java.lang.ref.Reference.reachabilityFence(running);
    }

    private static Set<Thread> startedSince(Set<Thread> existing) {
        return selectorThreads().stream()
                                .filter(t -> !existing.contains(t))
                                .collect(Collectors.toUnmodifiableSet());
    }

    private static Set<Thread> selectorThreads() {
        return Thread.getAllStackTraces()
                     .keySet()
                     .stream()
                     .filter(t -> t.isAlive() && t.getName().startsWith("HttpClient-") && t.getName().endsWith("-SelectorManager"))
                     .collect(Collectors.toUnmodifiableSet());
    }

    private static boolean eventually(BooleanSupplier condition) throws InterruptedException {
        var deadline = System.nanoTime() + 10_000_000_000L;

        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return condition.getAsBoolean();
    }
}
