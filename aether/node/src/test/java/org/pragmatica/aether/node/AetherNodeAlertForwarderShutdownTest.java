// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

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
/// that nothing closed on stop (#1097's class). Pinned by the thread, counted before boot, while up and after
/// stop; the node is held reachable to the end so a GC cannot end the thread instead of `stop()`.
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
        var baseline = selectorThreads();
        var config = AetherNodeContentStorageWarnBootTest.minimalConfig(Option.none(),
                                                                       Option.none(),
                                                                       ConfigurationProvider.builder().build(),
                                                                       tempDir)
                                                         .withAlerts(Option.some(AlertConfig.alertConfig(List.of("http://127.0.0.1:1/hook"))));

        node = AetherNode.aetherNode(config, () -> {})
                         .onFailure(cause -> fail("boot must succeed: " + cause.message()))
                         .unwrap();
        var running = node;

        assertThat(eventually(() -> selectorThreads() > baseline)).as("control: webhook-enabled boot owns a selector thread")
                                                                  .isTrue();
        node.stop().await(timeSpan(10).seconds()).onFailure(cause -> fail("stop must succeed: " + cause.message()));
        node = null;
        assertThat(eventually(() -> selectorThreads() <= baseline)).as("selector threads after stop; baseline %d, now %d",
                                                                      baseline,
                                                                      selectorThreads()).isTrue();
        java.lang.ref.Reference.reachabilityFence(running);
    }

    private static long selectorThreads() {
        return Thread.getAllStackTraces()
                     .keySet()
                     .stream()
                     .filter(t -> t.isAlive() && t.getName().startsWith("HttpClient-") && t.getName().endsWith("-SelectorManager"))
                     .count();
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
