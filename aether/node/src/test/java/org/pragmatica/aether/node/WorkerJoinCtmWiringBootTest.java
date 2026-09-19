// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

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
import org.pragmatica.aether.deployment.cluster.ClusterTopologyManager;
import org.pragmatica.aether.deployment.membership.fsm.MembershipDeltaProjector;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.config.ConfigService;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.TlsConfig;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;

/// #689 (verify-1120 SF-3): pins the `AetherNode` WIRING of the worker join channel into the CTM —
/// `WorkerJoinDecision → clusterTopologyManager::onWorkerJoin` in `collectRouteEntries`. Every unit pin
/// hand-calls `onWorkerJoin`; deleting the route entry left 0 node tests red, so the worker half of the
/// role comparison reached the CTM only by reading.
///
/// A real self-forming single-node cluster (`AetherNode.aetherNode` + `start()`, the #858 boot shape). The
/// decision is produced the way production produces it: the node's own `MembershipFsm` is fed a `worker`
/// descriptor and one ALIVE sample (`UP_HYSTERESIS`), enters MEMBER, emits the JOINED edge, and the
/// `MembershipDeltaProjector` classifies it non-core and routes a `WorkerJoinDecision` on the node's bus.
/// Observation points are log lines: the projector's own `WorkerJoined` DEBUG is the control that the
/// decision was emitted onto the router at all; the CTM's "observed on WorkerJoinDecision" DEBUG for the
/// same id is emitted ONLY inside `ClusterTopologyManagerRecord.onWorkerJoin`, so with the route entry
/// deleted the control still fires and the CTM line never does.
class WorkerJoinCtmWiringBootTest {
    /// #1276: node storage lives here, never under the machine-global `/data/aether/...` default.
    @TempDir
    Path tempDir;

    private static final String CTM_LOGGER = ClusterTopologyManager.class.getName();
    private static final String PROJECTOR_LOGGER = MembershipDeltaProjector.class.getName();
    private static final NodeId WORKER = NodeId.nodeId("worker-wiring-" + UUID.randomUUID()).unwrap();
    private static final TimeSpan START_BOUND = timeSpan(30).seconds();
    private static final Duration STEP_BOUND = Duration.ofSeconds(20);
    private static final String CTM_ACTIVATED = "CTM: Activated";
    private static final String PROJECTOR_WORKER_JOINED = "WorkerJoined ";
    private static final String CTM_OBSERVED_ON_WORKER_CHANNEL = "node " + WORKER.id() + " observed on WorkerJoinDecision";

    private AetherNode node;
    private CapturingAppender appender;
    private List<LoggerConfig> loggerConfigs;
    private List<Level> originalLevels;

    @BeforeEach
    void setUp() {
        appender = CapturingAppender.create("WorkerJoinCtmWiringCapture");
        appender.start();
        var ctx = (LoggerContext) LogManager.getContext(false);
        loggerConfigs = List.of(getOrCreateLoggerConfig(ctx.getConfiguration(), CTM_LOGGER),
                                getOrCreateLoggerConfig(ctx.getConfiguration(), PROJECTOR_LOGGER));
        originalLevels = loggerConfigs.stream()
                                      .map(LoggerConfig::getLevel)
                                      .toList();
        loggerConfigs.forEach(config -> {
            config.addAppender(appender, Level.DEBUG, null);
            config.setLevel(Level.DEBUG);
        });
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

        for (var i = 0; i < loggerConfigs.size(); i++) {
            loggerConfigs.get(i)
                         .removeAppender(appender.getName());
            loggerConfigs.get(i)
                         .setLevel(originalLevels.get(i));
        }

        ctx.updateLoggers();
        appender.stop();
        ConfigService.clear();
        ResourceProvider.clear();
    }

    @Test
    @Timeout(value = 120, unit = SECONDS)
    void workerJoinDecision_reachesTheCtm_throughTheNodesRouter() {
        node = AetherNode.aetherNode(minimalConfig(tempDir), () -> {})
                          .onFailure(cause -> fail("boot must succeed: " + cause.message()))
                          .unwrap();
        node.start()
            .await(START_BOUND)
            .onFailure(cause -> fail("start() must succeed: " + cause.message()));

        // A single node never elects itself, so the leader-change toggle never activates the CTM here; the
        // route under test is independent of leadership, and the CTM's handler is `active`-gated.
        var ctm = node.clusterTopologyManager()
                      .or(() -> fail("the booted node exposes its CTM"));

        ctm.activate();
        await().atMost(STEP_BOUND)
               .untilAsserted(() -> assertThat(appender.messages())
                   .as("control: the CTM is active and the capture on %s sees its lines", CTM_LOGGER)
                   .anyMatch(line -> line.contains(CTM_ACTIVATED)));

        // Produce the decision the way production does: the FSM classifies a `worker` descriptor's join.
        var fsm = node.membershipFsm();

        fsm.onMemberDescriptor(NodeInfo.nodeInfo(WORKER, nodeAddress("127.0.0.1", freePort()).unwrap(), Map.of(NodeInfo.LABEL_ROLE, "worker")));
        fsm.onSwimHealthy(WORKER, 1L);

        await().atMost(STEP_BOUND)
               .untilAsserted(() -> assertThat(appender.messages())
                   .as("control: the projector emitted a WorkerJoinDecision for the worker onto the router")
                   .anyMatch(line -> line.contains(PROJECTOR_WORKER_JOINED) && line.contains(WORKER.id())));
        await().atMost(STEP_BOUND)
               .untilAsserted(() -> assertThat(appender.messages())
                   .as("the WorkerJoinDecision must reach ClusterTopologyManager.onWorkerJoin through the node's router")
                   .anyMatch(line -> line.contains(CTM_OBSERVED_ON_WORKER_CHANNEL)));
    }

    /// The #858 single-node boot fixture: `self` in `coreNodes`, mutual self-signed QUIC TLS, management
    /// and app HTTP off.
    private static AetherNodeConfig minimalConfig(Path storageRoot) {
        var self = NodeId.nodeId("worker-join-wiring-boot-" + UUID.randomUUID()).unwrap();
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

    private static LoggerConfig getOrCreateLoggerConfig(Configuration configuration, String loggerName) {
        var existing = configuration.getLoggerConfig(loggerName);

        if (loggerName.equals(existing.getName())) {
            return existing;
        }

        var fresh = new LoggerConfig(loggerName, Level.DEBUG, false);

        configuration.addLogger(loggerName, fresh);

        return fresh;
    }

    /// In-memory log4j2 appender capturing every message the bound loggers emit.
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
