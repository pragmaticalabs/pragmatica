// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.node;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.SliceConfig;
import org.pragmatica.aether.config.TimeoutsConfig;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.dht.DHTAntiEntropy;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.net.tcp.TlsConfig;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;

/// #420/#1136 — `DHTAntiEntropy.start()` had no caller, so the periodic repair cycle never ran on a
/// booted node and `[timeouts.dht] anti_entropy_interval` was read by nothing. The node now arms the
/// cycle with its other periodic work, on the configured interval. Pinned through a REAL self-forming
/// node: with the interval set to one second, `DHTAntiEntropy`'s round-complete DEBUG line appears
/// within a few seconds of `start()` resolving, and stops appearing after `stop()`. Red with the
/// `periodicTasks.defer(...)` for the cycle removed (no round is ever logged).
class AetherNodeAntiEntropyCycleBootTest {
    /// #1276: node storage lives here, never under the machine-global `/data/aether/...` default.
    @TempDir
    Path tempDir;

    private static final String LOGGER_NAME = DHTAntiEntropy.class.getName();
    private static final String ROUND_MARKER = "DHT anti-entropy round:";

    private AetherNode node;
    private CapturingAppender appender;
    private LoggerConfig loggerConfig;
    private Level originalLevel;

    @BeforeEach
    void setUp() {
        appender = CapturingAppender.create("AntiEntropyCycleCapture");
        appender.start();
        var ctx = (LoggerContext) LogManager.getContext(false);
        loggerConfig = getOrCreateLoggerConfig(ctx.getConfiguration());
        originalLevel = loggerConfig.getLevel();
        loggerConfig.addAppender(appender, Level.DEBUG, null);
        loggerConfig.setLevel(Level.DEBUG);
        ctx.updateLoggers();
    }

    @AfterEach
    void tearDown() {
        if (node != null) {
            node.stop().await(timeSpan(10).seconds()).onFailure(cause -> {});
        }
        var ctx = (LoggerContext) LogManager.getContext(false);
        loggerConfig.removeAppender(appender.getName());
        loggerConfig.setLevel(originalLevel);
        ctx.updateLoggers();
        appender.stop();
        org.pragmatica.config.ConfigService.clear();
        org.pragmatica.aether.resource.ResourceProvider.clear();
    }

    @Test
    @Timeout(value = 90, unit = SECONDS)
    void bootedNode_runsTheAntiEntropyCycle_onTheConfiguredInterval_andStopsWithTheNode() throws InterruptedException {
        node = AetherNode.aetherNode(minimalConfig(tempDir), () -> {})
                          .onFailure(cause -> fail("construction must succeed: " + cause.message()))
                          .unwrap();

        assertThat(appender.rounds()).as("a created-but-unstarted node performs no periodic work (#644)").isZero();

        node.start().await(timeSpan(30).seconds())
            .onFailure(cause -> fail("start() must resolve on the single-node formation: " + cause.message()));

        var deadline = System.nanoTime() + timeSpan(15).seconds().nanos();

        while (appender.rounds() < 2 && System.nanoTime() < deadline) {
            Thread.sleep(200);
        }

        assertThat(appender.rounds()).as("the cycle runs on the configured 1s interval after formation").isGreaterThanOrEqualTo(2);

        node.stop().await(timeSpan(10).seconds()).onFailure(cause -> fail("stop must succeed: " + cause.message()));
        node = null;
        var afterStop = appender.rounds();

        Thread.sleep(2_500);

        assertThat(appender.rounds()).as("no round fires after stop() — the cycle is cancelled with the node").isEqualTo(afterStop);
    }

    private static AetherNodeConfig minimalConfig(Path storageRoot) {
        var self = NodeId.nodeId("anti-entropy-cycle-" + UUID.randomUUID()).unwrap();
        var selfInfo = NodeInfo.nodeInfo(self, nodeAddress("localhost", freePort()).unwrap());
        var timeouts = TimeoutsConfig.timeoutsConfig()
                                     .withDht(new TimeoutsConfig.DhtTimeouts(timeSpan(30).seconds(), timeSpan(1).seconds()));

        return AetherNodeConfig.builder()
                               .self(self).coreNodes(List.of(selfInfo)).managementPort(AetherNodeConfig.MANAGEMENT_DISABLED)
                               .sliceConfig(SliceConfig.sliceConfig()).artifactRepo(DHTConfig.DEFAULT).coreMax(1)
                               .appHttp(AppHttpConfig.appHttpConfig()).tls(Option.none()).quicTls(TlsConfig.selfSignedMutual())
                               .certificateProvider(Option.none()).configProvider(Option.some(HermeticStorage.withControlStorageIn(storageRoot,
                                    org.pragmatica.config.ConfigurationProvider.builder().build()))).environment(Option.none())
                               .managementHttpProtocol(HttpProtocol.H1)
                               .storageConfig(HermeticStorage.nodeStorageIn(storageRoot, false))
                               .build()
                               .withTimeouts(timeouts);
    }

    private static int freePort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static LoggerConfig getOrCreateLoggerConfig(Configuration configuration) {
        var existing = configuration.getLoggerConfig(LOGGER_NAME);
        if (LOGGER_NAME.equals(existing.getName())) {
            return existing;
        }
        var fresh = new LoggerConfig(LOGGER_NAME, Level.DEBUG, false);
        configuration.addLogger(LOGGER_NAME, fresh);
        return fresh;
    }

    private static final class CapturingAppender extends AbstractAppender {
        private final List<String> messages = new CopyOnWriteArrayList<>();

        private CapturingAppender(String name, Layout<?> layout) {
            super(name, (Filter) null, layout, true, Property.EMPTY_ARRAY);
        }

        static CapturingAppender create(String name) {
            return new CapturingAppender(name, PatternLayout.createDefaultLayout());
        }

        @Override
        public void append(LogEvent event) {
            messages.add(event.getMessage().getFormattedMessage());
        }

        long rounds() {
            return messages.stream().filter(m -> m.startsWith(ROUND_MARKER)).count();
        }
    }
}
