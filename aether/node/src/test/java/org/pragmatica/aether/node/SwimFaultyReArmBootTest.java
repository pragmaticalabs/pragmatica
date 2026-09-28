// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
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
import org.pragmatica.aether.config.RollbackConfig;
import org.pragmatica.aether.config.SliceConfig;
import org.pragmatica.aether.config.StreamingConfig;
import org.pragmatica.aether.config.TtmConfig;
import org.pragmatica.aether.controller.ControllerConfig;
import org.pragmatica.aether.deployment.cluster.ClusterTopologyManager;
import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.node.health.CoreSwimHealthDetector;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.aether.slice.SliceActionConfig;
import org.pragmatica.config.ConfigService;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.rabia.ProtocolConfig;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.TlsConfig;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.swim.NettySwimTransport;
import org.pragmatica.swim.SwimMember.MemberState;
import org.pragmatica.swim.SwimMessage;
import org.pragmatica.swim.SwimProtocol;
import org.pragmatica.swim.SwimTransport;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;

/// #1050 (verify-1057-r3 attack 1): pins the REGISTRATION of the SWIM FAULTY edge into the CTM
/// (`swimHealthDetector.addObservationListener(obs -> routeSwimFaultyToCtm(obs, clusterTopologyManager))` in
/// `AetherNode`), through a booted node. `SwimFaultyToCtmRoutingTest` pins the router; deleting the registration
/// reddened 0 of 1393 node tests, because every other test hands the CTM its evidence directly.
///
/// The whole S1 chain runs in the real assembly, on a self-forming single-node cluster (the #858 boot shape):
/// 1. a gossiped `ALIVE` for a phantom peer — a real SWIM `Ping` over UDP into the node's SWIM port, plaintext
///    because the config carries no certificate provider — makes raw SWIM report the peer HEALTHY, which the node's
///    own `MembershipFsm` (exposed by `ManageableNode`) confirms by tracking it: the landing signal;
/// 2. a `NodeRemoved` for that peer, handed to the node's own active CTM, starts the #1062 reap: SWIM life defers it,
///    and with `provisioningTimeout` at 1200ms the twelve re-checks run out inside two seconds — ABANDONED, parked;
/// 3. a gossiped `FAULTY` at a higher incarnation makes `SwimProtocol` emit a real `FaultyObserved` (second-hand,
///    authoritative, the tombstone DEBUG line is the SWIM-side control), which reaches the CTM ONLY through the
///    registration under test and re-arms the parked reap — the CTM's own INFO line is the pin.
///
/// With the registration deleted, steps 1–2 and the SWIM-side control still pass and the re-arm line never appears.
/// Not pinned here: the terminate itself (no compute provider is configured, so it resolves as unsupported and is
/// swallowed, as in production without a provider), and the leader-change toggle that activates the CTM in
/// production — a single node never elects itself, so the CTM is activated through its public API.
class SwimFaultyReArmBootTest {
    /// #1276: node storage lives here, never under the machine-global `/data/aether/...` default.
    @TempDir
    Path tempDir;

    private static final String CTM_LOGGER = ClusterTopologyManager.class.getName();
    private static final String SWIM_LOGGER = SwimProtocol.class.getName();
    private static final NodeId PHANTOM = NodeId.nodeId("node-phantom").unwrap();
    private static final NodeId GOSSIPER = NodeId.nodeId("node-gossiper").unwrap();
    private static final TimeSpan START_BOUND = timeSpan(30).seconds();
    private static final Duration STEP_BOUND = Duration.ofSeconds(15);
    private static final String SWIM_STARTED = "SWIM protocol started for node";
    private static final String CTM_ACTIVATED = "CTM: Activated";
    // The CTM logs the NodeId record (`NodeId[id=…]`); SwimProtocol logs the bare id.
    private static final String ABANDONED = "CTM: reap of " + PHANTOM + " ABANDONED";
    private static final String TOMBSTONED = "SWIM tombstone set at FAULTY edge for id " + PHANTOM.id();
    private static final String RE_ARMED = "CTM: SWIM reported " + PHANTOM + " FAULTY — re-arming the abandoned reap";

    private AetherNode node;
    private int selfPort;
    private SwimTransport gossipTransport;
    private InetSocketAddress gossipAddress;
    private CapturingAppender appender;
    private final List<LoggerConfig> loggerConfigs = new CopyOnWriteArrayList<>();
    private final List<Level> originalLevels = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        appender = CapturingAppender.create("SwimFaultyReArmBootCapture");
        appender.start();
        var ctx = (LoggerContext) LogManager.getContext(false);

        for (var name : List.of(CTM_LOGGER, SWIM_LOGGER)) {
            var loggerConfig = getOrCreateLoggerConfig(ctx.getConfiguration(), name);

            loggerConfigs.add(loggerConfig);
            originalLevels.add(loggerConfig.getLevel());
            // The SWIM-side control (tombstone at the FAULTY edge) is logged at DEBUG.
            loggerConfig.addAppender(appender, Level.DEBUG, null);
            loggerConfig.setLevel(Level.DEBUG);
        }

        ctx.updateLoggers();
    }

    @AfterEach
    void tearDown() {
        if (gossipTransport != null) {
            gossipTransport.stop()
                           .await(timeSpan(5).seconds())
                           .onFailure(cause -> {});
        }

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
    void faultyEdge_reachesTheCtmThroughTheRegisteredListener_andReArmsTheParkedReap() {
        node = AetherNode.aetherNode(minimalConfig(), () -> {})
                          .onFailure(cause -> fail("boot must succeed: " + cause.message()))
                          .unwrap();
        node.start()
            .await(START_BOUND)
            .onFailure(cause -> fail("start() must succeed: " + cause.message()));

        await().atMost(STEP_BOUND)
               .untilAsserted(() -> assertThat(appender.messages())
                   .as("control: SWIM is running and the capture sees its lines, or the assertions below examine nothing")
                   .anyMatch(line -> line.contains(SWIM_STARTED)));

        // A single node never elects itself (`LeaderElectionState` GUARD[empty-topology]: no peers in the
        // transport view), so the leader-change toggle never activates the CTM here. The registration under
        // test is independent of leadership; the node's own CTM is activated through its public API instead.
        var ctm = node.clusterTopologyManager()
                      .or(() -> fail("the booted node exposes its CTM"));

        ctm.activate();
        await().atMost(STEP_BOUND)
               .untilAsserted(() -> assertThat(appender.messages())
                   .as("control: the CTM is active and the capture sees its lines")
                   .anyMatch(line -> line.contains(CTM_ACTIVATED)));

        startGossipTransport();
        // An unknown gossip identity is correctly excluded by hierarchical SWIM scoping. Model
        // an already admitted CORE descriptor so this test reaches the registered FAULTY listener.
        node.membershipFsm().onMemberDescriptor(NodeInfo.nodeInfo(PHANTOM,
            nodeAddress("localhost", gossipAddress.getPort()).unwrap(),
            java.util.Map.of(NodeInfo.LABEL_ROLE, "core")));
        // Scope refresh runs periodically; keep sending the real UDP edge until it is admitted.

        // Step 1: raw SWIM learns the phantom as ALIVE. The node's own FSM tracking it is the proof the edge
        // was delivered through SwimProtocol's listeners, not assumed.
        gossip(MemberState.ALIVE, 1L);
        await().atMost(STEP_BOUND)
               .untilAsserted(() -> {
                   gossip(MemberState.ALIVE, 1L);
                   assertThat(node.membershipFsm().memberStates().get(PHANTOM))
                       .as("the gossiped ALIVE promoted the descriptor through the actual SWIM listener")
                       .isEqualTo("Member");
               });

        // Step 2: the reap defers on SWIM life and runs out of re-checks — parked.
        ctm.onMembershipDecision(MembershipDecision.nodeRemoved(PHANTOM, List.of(node.self())));
        await().atMost(STEP_BOUND)
               .untilAsserted(() -> assertThat(appender.messages())
                   .as("arming: the reap was abandoned on SWIM life, so a later re-arm can only come from the FAULTY edge")
                   .anyMatch(line -> line.contains(ABANDONED)));
        assertThat(appender.messages()).as("nothing re-armed before the FAULTY edge").noneMatch(line -> line.contains(RE_ARMED));

        // Step 3: a second-hand FAULTY at a higher incarnation. SWIM's own tombstone line shows the edge fired
        // inside SwimProtocol; the CTM's line shows it arrived through the registered listener.
        gossip(MemberState.FAULTY, 2L);
        await().atMost(STEP_BOUND)
               .untilAsserted(() -> assertThat(appender.messages())
                   .as("SWIM-side control: the FAULTY edge fired inside SwimProtocol")
                   .anyMatch(line -> line.contains(TOMBSTONED)));
        await().atMost(STEP_BOUND)
               .untilAsserted(() -> assertThat(appender.messages())
                   .as("the FAULTY edge reached ClusterTopologyManager.onSwimFaulty and re-armed the parked reap")
                   .anyMatch(line -> line.contains(RE_ARMED)));
    }

    private void startGossipTransport() {
        var codec = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());
        var port = freePort();

        gossipTransport = NettySwimTransport.nettySwimTransport(codec, codec)
                                            .onFailure(cause -> fail("gossip transport: " + cause.message()))
                                            .unwrap();
        gossipTransport.start(port, (sender, message) -> {})
                       .await(timeSpan(5).seconds())
                       .onFailure(cause -> fail("gossip transport start: " + cause.message()));
        gossipAddress = new InetSocketAddress("localhost", port);
    }

    /// One real SWIM `Ping` carrying a single membership update about the phantom, whose address is the gossip
    /// transport itself so the node's own probes of it land on a socket that answers nothing.
    private void gossip(MemberState state, long incarnation) {
        var update = SwimMessage.MembershipUpdate.membershipUpdate(PHANTOM, state, incarnation, gossipAddress);
        var swimPort = new InetSocketAddress("localhost", selfPort + CoreSwimHealthDetector.SWIM_PORT_OFFSET);

        gossipTransport.send(swimPort, SwimMessage.Ping.ping(GOSSIPER, incarnation, List.of(update)))
                       .await(timeSpan(5).seconds())
                       .onFailure(cause -> fail("gossip send: " + cause.message()));
    }

    /// The #858 single-node boot fixture, plus a 1200ms `provisioningTimeout` so the twelve reap re-checks run out
    /// in seconds rather than a minute.
    private AetherNodeConfig minimalConfig() {
        var self = NodeId.nodeId("swim-faulty-rearm-boot-" + UUID.randomUUID()).unwrap();

        selfPort = freePort();
        var selfInfo = NodeInfo.nodeInfo(self, nodeAddress("localhost", selfPort).unwrap());
        var autoHeal = AutoHealConfig.autoHealConfig(timeSpan(15).seconds(),
                                                      timeSpan(1200).millis(),
                                                      AutoHealConfig.DEFAULT_SWIM_HINTS_TTL)
                                    .onFailure(cause -> fail("auto-heal config: " + cause.message()))
                                    .unwrap();

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
                                .storageConfig(HermeticStorage.nodeStorageIn(tempDir, false))
                                .backupConfig(Option.none())
                                .membership(Option.none())
                                .streaming(StreamingConfig.streamingConfig())
                                .protocol(ProtocolConfig.defaultConfig())
                                .sliceAction(SliceActionConfig.sliceActionConfig())
                                .cache(DHTConfig.CACHE_DEFAULT)
                                .ttm(TtmConfig.ttmConfig())
                                .rollback(RollbackConfig.rollbackConfig())
                                .controllerConfig(ControllerConfig.DEFAULT)
                                .autoHeal(autoHeal)
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
