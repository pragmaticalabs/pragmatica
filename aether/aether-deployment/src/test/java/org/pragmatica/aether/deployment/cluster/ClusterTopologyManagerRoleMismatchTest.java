// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

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
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.deployment.DeploymentMap;
import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.InstanceStatus;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.ProvisionSpec;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterPhase;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NetworkMessage;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.GenerationSnapshotSource;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.consensus.topology.MembershipView;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.consensus.topology.TopologyObserver;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #689 — the leader holds both halves of the role comparison: the role it PROVISIONED a node with
/// (`provisionReplacement(..., intendedRole)`) and the role that node ADVERTISES once observed
/// (`NodeInfo.LABEL_ROLE`, read through the `TopologyObserver`). A provisioned node whose label
/// never arrives is classified CORE by `MemberDescriptor.isCoreRole` — deliberately, and unchanged
/// here — so an intended worker that boots unlabelled silently joins the core set and every
/// community-tier mechanism gated on "not a core" is suppressed on it with nothing saying so.
///
/// The two tests below are a PAIR with mutually exclusive expectations over the same capture, so
/// the WARN cannot pass vacuously: one node was provisioned and must warn; the other was not and
/// must not.
class ClusterTopologyManagerRoleMismatchTest {
    private static final String LOGGER_NAME = "org.pragmatica.aether.deployment.cluster.ClusterTopologyManager";
    private static final String MISMATCH_MARKER = "advertised role";

    private static final NodeId SELF = nodeId("node-self").unwrap();
    private static final NodeId PEER_A = nodeId("node-a").unwrap();
    private static final NodeId PEER_B = nodeId("node-b").unwrap();
    private static final NodeId PROVISIONED = nodeId("node-provisioned").unwrap();
    private static final NodeId STRANGER = nodeId("node-stranger").unwrap();

    private static final NodeInfo INFO_SELF = NodeInfo.nodeInfo(SELF, NodeAddress.nodeAddress("10.0.0.1", 6000).unwrap());
    private static final NodeInfo INFO_A = NodeInfo.nodeInfo(PEER_A, NodeAddress.nodeAddress("10.0.0.2", 6000).unwrap());
    private static final NodeInfo INFO_B = NodeInfo.nodeInfo(PEER_B, NodeAddress.nodeAddress("10.0.0.3", 6000).unwrap());

    private TopologyObserver observer;
    private ClusterTopologyManager ctm;
    private CapturingAppender appender;
    private LoggerConfig loggerConfig;
    private Level originalLevel;

    @BeforeEach
    void setUp() {
        var snapshotSource = new StubSnapshotSource();
        var config = new TopologyConfig(SELF,
                                        5,
                                        timeSpan(60).seconds(),
                                        timeSpan(1).seconds(),
                                        List.of(INFO_SELF, INFO_A, INFO_B));
        observer = TopologyObserver.topologyObserver(config, quietRouter(), snapshotSource).unwrap();
        var autoHeal = AutoHealConfig.autoHealConfig(timeSpan(60).seconds(),
                                                     timeSpan(1).millis(),
                                                     AutoHealConfig.DEFAULT_STALE_OBSERVATION_TTL,
                                                     AutoHealConfig.DEFAULT_QUIC_MISS_PROMOTION_THRESHOLD,
                                                     AutoHealConfig.DEFAULT_PROVISIONING_TIMEOUT,
                                                     timeSpan(0).millis())
                                     .unwrap();
        ctm = ClusterTopologyManager.clusterTopologyManager(observer,
                                                            new StubLifecycleManager(),
                                                            autoHeal,
                                                            DeploymentMap.deploymentMap(),
                                                            snapshotSource,
                                                            Option::none,
                                                            ClusterTopologyManagerRoleMismatchTest::applyNothing,
                                                            () -> ClusterPhase.NORMAL);
        ctm.activate();

        appender = CapturingAppender.create("CtmRoleMismatchCapture");
        appender.start();
        var ctx = (LoggerContext) LogManager.getContext(false);
        var configuration = ctx.getConfiguration();
        loggerConfig = getOrCreateLoggerConfig(configuration);
        originalLevel = loggerConfig.getLevel();
        loggerConfig.addAppender(appender, Level.WARN, null);
        loggerConfig.setLevel(Level.WARN);
        ctx.updateLoggers();
    }

    @AfterEach
    void tearDown() {
        var ctx = (LoggerContext) LogManager.getContext(false);
        loggerConfig.removeAppender(appender.getName());
        loggerConfig.setLevel(originalLevel);
        ctx.updateLoggers();
        appender.stop();
    }

    /// (1): provisioned as WORKER, boots with no role label, joins on the CORE channel (blank is
    /// core to `isCoreRole`). The WARN names the node, the intended role and what was advertised.
    @Test
    void provisionedWorker_joiningWithNoRoleLabel_warnsNamingNodeIntendedAndAdvertised() {
        provision(PROVISIONED, NodeRole.WORKER);
        observe(PROVISIONED, Map.of());

        ctm.onMembershipDecision(MembershipDecision.nodeJoined(PROVISIONED, List.of(SELF, PEER_A, PEER_B)));

        var mismatchWarns = appender.capturedWarns()
                                    .stream()
                                    .filter(msg -> msg.contains(MISMATCH_MARKER))
                                    .toList();

        assertThat(mismatchWarns).as("#689: a provisioned node advertising a different (or no) role must be reported")
                                 .hasSize(1);
        assertThat(mismatchWarns.getFirst()).contains(PROVISIONED.id())
                                            .contains("intended role 'worker'")
                                            .contains("advertised role '' (absent)")
                                            .contains("classified as CORE");
    }

    /// (4): a node nothing provisioned joins unlabelled. Absence of intent is not a mismatch —
    /// the same capture that must hold one WARN above must hold none here.
    @Test
    void unprovisionedNode_joiningWithNoRoleLabel_doesNotWarn() {
        observe(STRANGER, Map.of());

        ctm.onMembershipDecision(MembershipDecision.nodeJoined(STRANGER, List.of(SELF, PEER_A, PEER_B)));

        assertThat(appender.capturedWarns()).as("#689: no intent on record, so no mismatch to report")
                                            .noneMatch(msg -> msg.contains(MISMATCH_MARKER));
    }

    /// Control for (1): provisioned as CORE and advertising `core` — the halves agree, no WARN.
    @Test
    void provisionedCore_advertisingCore_doesNotWarn() {
        provision(PROVISIONED, NodeRole.CORE);
        observe(PROVISIONED, Map.of(NodeInfo.LABEL_ROLE, "core"));

        ctm.onMembershipDecision(MembershipDecision.nodeJoined(PROVISIONED, List.of(SELF, PEER_A, PEER_B)));

        assertThat(appender.capturedWarns()).noneMatch(msg -> msg.contains(MISMATCH_MARKER));
    }

    private void provision(NodeId nodeId, NodeRole intendedRole) {
        var result = ctm.provisionReplacement(nodeId, Option.none(), Set.of(SELF, PEER_A, PEER_B), intendedRole).await();

        assertThat(result.isSuccess()).as("fixture: the provision must be DISPATCHED for the intent to be on record")
                                      .isTrue();
        assertThat(result.unwrap()).isInstanceOf(ProvisionDisposition.Dispatched.class);
    }

    /// The node becomes known to the observer with exactly these labels — what SWIM/discovery
    /// would deliver for a node that booted with (or without) `AETHER_ROLE`.
    private void observe(NodeId nodeId, Map<String, String> labels) {
        var info = NodeInfo.nodeInfo(nodeId, NodeAddress.nodeAddress("10.0.0.9", 6000).unwrap(), labels);

        observer.handleDiscoveredNodes(new NetworkMessage.DiscoveredNodes(SELF, List.of(info)));
        assertThat(observer.get(nodeId).isPresent()).as("fixture: the observer must hold the node's NodeInfo")
                                                    .isTrue();
    }

    private static Promise<List<Object>> applyNothing(List<KVCommand<AetherKey>> commands) {
        return Promise.success(List.of());
    }

    private static MessageRouter.MutableRouter quietRouter() {
        var router = MessageRouter.mutable();
        router.addRoute(NetworkServiceMessage.ListConnectedNodes.class, _ -> {});
        return router;
    }

    private static LoggerConfig getOrCreateLoggerConfig(Configuration configuration) {
        var existing = configuration.getLoggerConfig(LOGGER_NAME);
        if (LOGGER_NAME.equals(existing.getName())) {return existing;}
        var fresh = new LoggerConfig(LOGGER_NAME, Level.WARN, false);
        configuration.addLogger(LOGGER_NAME, fresh);
        return fresh;
    }

    private static final class StubSnapshotSource implements GenerationSnapshotSource {
        private final AtomicReference<Option<MembershipView>> view = new AtomicReference<>(Option.none());

        @Override public Option<MembershipView> currentMembershipView() {
            return view.get();
        }

        @Override public long observedRabiaTerm() {
            return 0L;
        }
    }

    private static final class StubLifecycleManager implements NodeLifecycleManager {
        @Override public Promise<ActionResult> executeAction(NodeAction action) {
            return Promise.success(new ActionResult.NodeStarted(stubInstance()));
        }

        @Override public Promise<InstanceInfo> provisionNode(ProvisionSpec spec) {
            return Promise.success(stubInstance());
        }

        @Override public Promise<Unit> terminateNode(NodeId nodeId) {
            return Promise.success(Unit.unit());
        }

        @Override public Promise<Unit> restartNode(NodeId nodeId) {
            return Promise.success(Unit.unit());
        }

        @Override public boolean isCloudManaged() {
            return true;
        }

        private static InstanceInfo stubInstance() {
            return InstanceInfo.instanceInfo(InstanceId.instanceId("stub").unwrap(),
                                             InstanceStatus.RUNNING,
                                             List.of("127.0.0.1"),
                                             InstanceType.ON_DEMAND).unwrap();
        }
    }

    private static final class CapturingAppender extends AbstractAppender {
        private final List<String> messages = new CopyOnWriteArrayList<>();

        private CapturingAppender(String name, Layout<?> layout) {
            super(name, (Filter) null, layout, true, Property.EMPTY_ARRAY);
        }

        static CapturingAppender create(String name) {
            return new CapturingAppender(name, PatternLayout.createDefaultLayout());
        }

        @Override public void append(LogEvent event) {
            if (event.getLevel().isMoreSpecificThan(Level.WARN)) {
                messages.add(event.getMessage().getFormattedMessage());
            }
        }

        List<String> capturedWarns() {
            return List.copyOf(messages);
        }
    }
}
