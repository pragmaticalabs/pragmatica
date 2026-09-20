// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.pragmatica.cluster.metrics.MetricObservation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

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
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPong;
import org.pragmatica.config.ConfigService;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.TlsConfig;
import org.pragmatica.swim.SwimProtocol;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;

/// #1061 round 3 (review S2): pins the INSTALLATION of the ClusterSync hint reporters in
/// `AetherNode.assembleNode`, through the node itself. `SwimHintWiringTest` pins the three factories;
/// the review's M7 rewired the install sites (`setUnreachableReporter` to an inline `PEER_LEFT` hint,
/// `addPongListener(pongResponsiveReporter…)` deleted) and 0 of 1397 node tests reddened, because every
/// other test rebuilds that wiring itself.
///
/// A real self-forming single-node cluster (`AetherNode.aetherNode` + `start()`, the #858 boot shape)
/// installs the reporters on the collector the node exposes (`metricsCollector()`). SWIM is running
/// after `start()`, so `CoreSwimHealthDetector.recordTransportHint` reaches `SwimProtocol`, whose own
/// log lines name what arrived: the ORIGIN of a recorded hint, and the retraction on a pong. Neither
/// the detector nor the scheduler is exposed by the node (both are `assembleNode` locals), so the
/// `SwimProtocol` logger is the observation point, read as an operator would. The line SWIM logs when
/// it starts is the control that the capture works and the subject is alive before anything is
/// asserted absent.
///
/// Reds under the review's rewirings: an inline `PEER_LEFT` reporter logs `origin LINK_LOST`; a
/// deleted pong listener logs no retraction. NOT pinned here: the `metricsScheduler::onLinkEstablished`
/// binding at the `attachQuicPeerStateListener` call — reaching it needs a second QUIC peer completing
/// a handshake, and the epoch it advances is not observable from outside the node.
class SwimHintInstallBootTest {
    /// #1276: node storage lives here, never under the machine-global `/data/aether/...` default.
    @TempDir
    Path tempDir;

    private static final String LOGGER_NAME = SwimProtocol.class.getName();
    private static final NodeId PEER = NodeId.nodeId("node-peer").unwrap();
    private static final TimeSpan START_BOUND = timeSpan(30).seconds();
    private static final Duration SWIM_START_BOUND = Duration.ofSeconds(15);
    private static final Duration HINT_BOUND = Duration.ofSeconds(5);
    private static final String SWIM_STARTED = "SWIM protocol started for node";
    private static final String HINT_RECORDED = "peer " + PEER.id() + " reported unreachable (origin ";
    private static final String PONG_RETRACTION = "pong received from " + PEER.id() + " — PEER_UNRESPONSIVE death hint retracted";

    private AetherNode node;
    private CapturingAppender appender;
    private LoggerConfig loggerConfig;
    private Level originalLevel;

    @BeforeEach
    void setUp() {
        appender = CapturingAppender.create("SwimHintInstallBootCapture");
        appender.start();
        var ctx = (LoggerContext) LogManager.getContext(false);
        loggerConfig = getOrCreateLoggerConfig(ctx.getConfiguration());
        originalLevel = loggerConfig.getLevel();
        // The hint's origin is logged at DEBUG (`SwimProtocol.applyUnreachableHint`).
        loggerConfig.addAppender(appender, Level.DEBUG, null);
        loggerConfig.setLevel(Level.DEBUG);
        ctx.updateLoggers();
    }

    @AfterEach
    void tearDown() {
        if (node != null) {
            node.stop()
                .await(timeSpan(10).seconds())
                .onFailure(cause -> {});
        }

        var ctx = (LoggerContext) LogManager.getContext(false);
        loggerConfig.removeAppender(appender.getName());
        loggerConfig.setLevel(originalLevel);
        ctx.updateLoggers();
        appender.stop();
        // Process-wide singletons AetherNode may set; cleared as the sibling boot tests do.
        ConfigService.clear();
        ResourceProvider.clear();
    }

    @Test
    @Timeout(value = 90, unit = SECONDS)
    void assembleNode_installsPingTimeoutReporterAndPongRetraction_onTheCollector() {
        node = AetherNode.aetherNode(minimalConfig(tempDir), () -> {})
                          .onFailure(cause -> fail("boot must succeed: " + cause.message()))
                          .unwrap();
        node.start()
            .await(START_BOUND)
            .onFailure(cause -> fail("start() must succeed: " + cause.message()));

        await().atMost(SWIM_START_BOUND)
               .untilAsserted(() -> assertThat(appender.messages())
                   .as("control: SWIM is running and the capture on %s sees its lines, or the "
                       + "assertions below examine nothing", LOGGER_NAME)
                   .anyMatch(line -> line.contains(SWIM_STARTED)));

        // Site 1: `setUnreachableReporter(pingTimeoutReporter(...))`. The collector's report reaches
        // SWIM as a PING_TIMEOUT hint, whose origin is PEER_UNRESPONSIVE — not the LINK_LOST an inline
        // PEER_LEFT hint would carry.
        node.membershipFsm().onMemberDescriptor(NodeInfo.nodeInfo(PEER,
            nodeAddress("localhost", freePort()).unwrap(), java.util.Map.of(NodeInfo.LABEL_ROLE, "core")));
        await().atMost(HINT_BOUND)
               .untilAsserted(() -> {
                   // Audience refresh is periodic; preserve real collector ingress for the probe.
                   node.metricsCollector().reportUnreachable(PEER);
                   assertThat(appender.messages())
                       .as("a ClusterSync missed-pong report must arrive in SWIM as a hint")
                       .anyMatch(line -> line.contains(HINT_RECORDED));
               });
        assertThat(appender.messages())
            .as("the installed reporter is pingTimeoutReporter: the hint's origin is PEER_UNRESPONSIVE")
            .anyMatch(line -> line.contains(HINT_RECORDED + "PEER_UNRESPONSIVE)"))
            .noneMatch(line -> line.contains(HINT_RECORDED + "LINK_LOST)"));

        // Site 2: `addPongListener(pongResponsiveReporter(...))`. A pong fanned out by the collector
        // retracts that hint in SWIM; with the listener not installed, nothing is retracted.
        node.metricsCollector().onClusterSyncPong(new ClusterSyncPong(PEER, new MetricObservation(0L, System.nanoTime(), System.currentTimeMillis(), Map.of()),
                                   0L, 0L, 0L, 0L, "", java.util.List.of(), java.util.List.of(), java.util.List.of(), org.pragmatica.lang.Option.none()));

        await().atMost(HINT_BOUND)
               .untilAsserted(() -> assertThat(appender.messages())
                   .as("the installed pong listener is pongResponsiveReporter: the pong retracts the hint")
                   .anyMatch(line -> line.contains(PONG_RETRACTION)));
    }

    /// The #858 single-node boot fixture: `self` in `coreNodes` (TopologyObserver requires it), mutual
    /// self-signed QUIC TLS (server and client contexts), management and app HTTP off.
    private static AetherNodeConfig minimalConfig(Path storageRoot) {
        var self = NodeId.nodeId("swim-hint-install-boot-" + UUID.randomUUID()).unwrap();
        var selfInfo = NodeInfo.nodeInfo(self, nodeAddress("localhost", freePort()).unwrap());

        return AetherNodeConfig.builder()
                                .self(self)
                                .coreNodes(List.of(selfInfo))
                                .managementPort(AetherNodeConfig.MANAGEMENT_DISABLED)
                                .sliceConfig(SliceConfig.sliceConfig())
                                .artifactRepo(DHTConfig.FULL)
                                .coreMax(1)
                                .appHttp(AppHttpConfig.appHttpConfig())
                                .tls(Option.none())
                                .quicTls(TlsConfig.selfSignedMutual())
                                .certificateProvider(Option.none())
                                .configProvider(Option.none())
                                .environment(Option.none())
                                .managementHttpProtocol(HttpProtocol.H1)
                                .storageConfig(HermeticStorage.nodeStorageIn(storageRoot, false))
                                .build();
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

    /// In-memory log4j2 appender capturing every message the bound logger emits.
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

        List<String> messages() {
            return List.copyOf(messages);
        }
    }
}
