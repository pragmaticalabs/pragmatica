// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.config.ConfigService;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #1571, through the REAL boot path: a `CLUSTER_EVENTS_MAX_COUNT` above the cluster-events read window
/// (`CLUSTER_EVENTS_MAX_RETAINED`) refuses `AetherNode.aetherNode(...)`, and it does so before anything is
/// built — the node's cluster port is still free afterwards, over TCP and UDP (QUIC).
///
/// The environment arrives through the boot's environment-function seam, so the test sets the variable in a
/// map rather than in the JVM's own environment.
class AetherNodeClusterEventsWindowRefusalBootTest {
    @TempDir
    Path tempDir;

    private AetherNode node;

    @AfterEach
    void tearDown() {
        if (node != null) {
            node.stop()
                .await(timeSpan(10).seconds())
                .onFailure(cause -> {});
        }

        ConfigService.clear();
        ResourceProvider.clear();
    }

    @Test
    @Timeout(value = 60, unit = SECONDS)
    void aetherNode_refusesBoot_whenMaxCountExceedsTheReadWindow_beforeBindingAnyPort() {
        var config = AetherNodeContentStorageWarnBootTest.minimalConfig(Option.none(),
                                                                        Option.none(),
                                                                        org.pragmatica.config.ConfigurationProvider.builder()
                                                                                                                  .build(),
                                                                        tempDir);
        var port = config.topology()
                         .coreNodes()
                         .getFirst()
                         .address()
                         .port();
        var tooMany = String.valueOf(AetherNode.CLUSTER_EVENTS_MAX_RETAINED + 1);
        var environment = Map.of(ClusterEventsLimits.MAX_COUNT_VARIABLE, tooMany);

        AetherNode.aetherNode(config,
                              MessageRouter.DelegateRouter.delegate(),
                              NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs()),
                              () -> {},
                              () -> {},
                              variable -> Option.option(environment.get(variable)))
                  .onSuccess(booted -> {
                      node = booted;
                      fail("#1571: boot must REFUSE a CLUSTER_EVENTS_MAX_COUNT above the read window; it booted");
                  })
                  .onFailure(cause -> assertThat(cause.message()).as("the refusal names the variable, its value and the window")
                                                                 .contains(ClusterEventsLimits.MAX_COUNT_VARIABLE + "='" + tooMany + "'")
                                                                 .contains("CLUSTER_EVENTS_MAX_RETAINED="
                                                                           + AetherNode.CLUSTER_EVENTS_MAX_RETAINED));

        assertThat(isFree(port)).as("the refusal comes before anything binds the cluster port " + port)
                                .isTrue();
    }

    /// Positive control for the port probe: a port this test holds must read as NOT free, or a probe that
    /// always answered "free" would make the assertion above vacuous.
    @Test
    void portProbe_seesAHeldPortAsTaken() throws IOException {
        try (var held = new DatagramSocket(0)) {
            assertThat(isFree(held.getLocalPort())).isFalse();
        }
    }

    private static boolean isFree(int port) {
        try (var tcp = new ServerSocket(port); var udp = new DatagramSocket(port)) {
            return tcp.getLocalPort() == port && udp.getLocalPort() == port;
        } catch (IOException e) {
            return false;
        }
    }
}
