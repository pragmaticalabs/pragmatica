// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
import org.pragmatica.aether.deployment.membership.fsm.WorkerJoinDecision;
import org.pragmatica.hlc.HlcTimestamp;
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
/// (`provisionReplacement(..., intendedRole)`) and the role that node ADVERTISES as membership holds
/// it (`MembershipFsm.memberDescriptor(id).role()`, reached through `MembershipLiveness.advertisedRole`
/// — here a test-side descriptor map, exactly the projection `AetherNode.drainGraceLiveness` wires).
/// A provisioned node whose label never arrives is classified CORE by `MemberDescriptor.isCoreRole` —
/// deliberately, and unchanged here — so an intended worker that boots unlabelled silently joins the
/// core set and every community-tier mechanism gated on "not a core" is suppressed on it with nothing
/// saying so.
///
/// The first two tests are a PAIR with mutually exclusive expectations over the same capture, so the
/// WARN cannot pass vacuously: one node was provisioned and must warn; the other was not and must not.
/// The lifetime tests (verify-1120 BLOCKING-1 / SF-2) pin that the intent is RETAINED: every rejoin
/// of a provisioned id is re-compared, a relabelled rejoin clears the entry, and only decommissioning
/// forgets the id.
class ClusterTopologyManagerRoleMismatchTest {
    private static final String LOGGER_NAME = "org.pragmatica.aether.deployment.cluster.ClusterTopologyManager";
    private static final String MISMATCH_MARKER = "advertised role";
    private static final String CLEARED_MARKER = "role mismatch cleared";

    private static final NodeId SELF = nodeId("node-self").unwrap();
    private static final NodeId PEER_A = nodeId("node-a").unwrap();
    private static final NodeId PEER_B = nodeId("node-b").unwrap();
    private static final NodeId PROVISIONED = nodeId("node-provisioned").unwrap();
    private static final NodeId STRANGER = nodeId("node-stranger").unwrap();

    private static final NodeInfo INFO_SELF = NodeInfo.nodeInfo(SELF, NodeAddress.nodeAddress("10.0.0.1", 6000).unwrap());
    private static final NodeInfo INFO_A = NodeInfo.nodeInfo(PEER_A, NodeAddress.nodeAddress("10.0.0.2", 6000).unwrap());
    private static final NodeInfo INFO_B = NodeInfo.nodeInfo(PEER_B, NodeAddress.nodeAddress("10.0.0.3", 6000).unwrap());

    /// The FSM's descriptor projection: what membership holds as each node's self-asserted role.
    private final Map<NodeId, String> descriptors = new ConcurrentHashMap<>();
    /// The FSM's not-DEAD set — what an activation replay re-compares.
    private final Set<NodeId> tracked = ConcurrentHashMap.newKeySet();
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
        var autoHeal = AutoHealConfig.autoHealConfig(timeSpan(1).millis(),
                                                     AutoHealConfig.DEFAULT_PROVISIONING_TIMEOUT)
                                     .unwrap();
        var liveness = MembershipLiveness.membershipLiveness(Set::of,
                                                             () -> Set.copyOf(tracked),
                                                             _ -> false,
                                                             _ -> false,
                                                             Set::of,
                                                             () -> 3,
                                                             id -> Option.option(descriptors.get(id)));
        ctm = ClusterTopologyManager.clusterTopologyManager(observer,
                                                            new StubLifecycleManager(),
                                                            autoHeal,
                                                            DeploymentMap.deploymentMap(),
                                                            snapshotSource,
                                                            Option::none,
                                                            ClusterTopologyManagerRoleMismatchTest::applyNothing,
                                                            () -> ClusterPhase.NORMAL,
                                                            _ -> {},
                                                            _ -> {},
                                                            Option::none,
                                                            liveness);
        ctm.activate();

        appender = CapturingAppender.create("CtmRoleMismatchCapture");
        appender.start();
        var ctx = (LoggerContext) LogManager.getContext(false);
        var configuration = ctx.getConfiguration();
        loggerConfig = getOrCreateLoggerConfig(configuration);
        originalLevel = loggerConfig.getLevel();
        loggerConfig.addAppender(appender, Level.INFO, null);
        loggerConfig.setLevel(Level.INFO);
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

    /// A provisioned WORKER with no role label remains UNKNOWN. The diagnostic names the node,
    /// intended role and missing advertisement without inventing a core or worker admission.
    @Test
    void provisionedWorker_joiningWithNoRoleLabel_warnsNamingNodeIntendedAndAdvertised() {
        provision(PROVISIONED, NodeRole.WORKER);
        describe(PROVISIONED, "");

        join(PROVISIONED);

        var mismatchWarns = appender.capturedWarns()
                                    .stream()
                                    .filter(msg -> msg.contains(MISMATCH_MARKER))
                                    .toList();

        assertThat(mismatchWarns).as("#689: a provisioned node advertising a different (or no) role must be reported")
                                 .hasSize(1);
        assertThat(mismatchWarns.getFirst()).contains(PROVISIONED.id())
                                            .contains("intended role 'worker'")
                                            .contains("advertised role '' (absent)")
                                            .contains("classified as UNKNOWN");
    }

    /// (4): a node nothing provisioned joins unlabelled. Absence of intent is not a mismatch —
    /// the same capture that must hold one WARN above must hold none here.
    @Test
    void unprovisionedNode_joiningWithNoRoleLabel_doesNotWarn() {
        describe(STRANGER, "");

        join(STRANGER);

        assertThat(appender.capturedWarns()).as("#689: no intent on record, so no mismatch to report")
                                            .noneMatch(msg -> msg.contains(MISMATCH_MARKER));
    }

    /// Control for (1): provisioned as CORE and advertising `core` — the halves agree, no WARN.
    @Test
    void provisionedCore_advertisingCore_doesNotWarn() {
        provision(PROVISIONED, NodeRole.CORE);
        describe(PROVISIONED, "core");

        join(PROVISIONED);

        assertThat(appender.capturedWarns()).noneMatch(msg -> msg.contains(MISMATCH_MARKER));
    }

    /// verify-1120 SF-1 — the leader learns a node by GOSSIP before its direct ANNOUNCE: the
    /// `TopologyObserver` keeps the label-less first sighting forever (`putIfAbsent`), while the FSM
    /// merges the later labelled announce under its blank-downgrade guard and classifies `core`. The
    /// comparison must read what the FSM holds; reading the observer here is a false WARN for a
    /// correctly-labelled core, self-contradicting the classification it reports.
    @Test
    void gossipFirstSighting_ofACorrectlyLabelledCore_doesNotWarn() {
        provision(PROVISIONED, NodeRole.CORE);
        observe(PROVISIONED, Map.of());
        observe(PROVISIONED, Map.of(NodeInfo.LABEL_ROLE, "core"));
        assertThat(observer.get(PROVISIONED)
                           .flatMap(info -> Option.option(info.labels()
                                                              .get(NodeInfo.LABEL_ROLE)))
                           .isPresent()).as("premise: the observer's first (label-less) sighting is the one it keeps")
                                        .isFalse();
        describe(PROVISIONED, "core");

        join(PROVISIONED);

        assertThat(appender.capturedWarns()).as("#689 SF-1: membership classified this node core from its label; no mismatch")
                                            .noneMatch(msg -> msg.contains(MISMATCH_MARKER));
        assertThat(ctm.roleMismatches()).isEmpty();
    }

    /// A host with no membership wiring (`MembershipLiveness.UNWIRED`-shaped) has no advertised role to
    /// offer — the comparison is skipped, never run against a fabricated blank.
    @Test
    void noAdvertisedRoleKnownToMembership_isNotCompared() {
        provision(PROVISIONED, NodeRole.WORKER);

        join(PROVISIONED);

        assertThat(appender.capturedWarns()).noneMatch(msg -> msg.contains(MISMATCH_MARKER));
        assertThat(ctm.roleMismatches()).isEmpty();
    }

    /// The other direction: provisioned as CORE, booted labelled `worker`. Such a node never
    /// appears in `MembershipDecision` (#728 routes it to the worker channel), so the comparison
    /// must be reachable from `onWorkerJoin` or a core replacement that keeps missing the core set
    /// is re-provisioned forever with nothing naming why.
    @Test
    void provisionedCore_joiningOnTheWorkerChannel_warnsAndIsListed() {
        provision(PROVISIONED, NodeRole.CORE);

        ctm.onWorkerJoin(WorkerJoinDecision.workerJoinDecision(PROVISIONED, "worker", new HlcTimestamp(HlcTimestamp.pack(1L, 0), SELF)));

        assertThat(appender.capturedWarns()).filteredOn(msg -> msg.contains(MISMATCH_MARKER))
                                            .hasSize(1)
                                            .first()
                                            .asString()
                                            .contains("intended role 'core'")
                                            .contains("advertised role 'worker'")
                                            .contains("classified as WORKER");
        assertThat(ctm.roleMismatches()).as("#689: the mismatch is readable without log access")
                                        .containsExactly(new ClusterTopologyManager.RoleMismatch(PROVISIONED, "core", "worker", "WORKER"));
    }

    /// verify-1120 BLOCKING-1 — an in-place restart of a mislabelled node (crash, OOM, operator
    /// restart: same id, since a replacement boots from a rendered config carrying its node id). The
    /// entry survives `NodeRemoved`, the rejoin is compared AGAIN, and the WARN re-fires: the node's
    /// condition survived the restart, so must the operator's signals. Reviewer probe B, inverted.
    @Test
    void restartInPlace_ofAMislabelledNode_keepsTheEntry_andReWarnsOnRejoin() {
        provision(PROVISIONED, NodeRole.WORKER);
        describe(PROVISIONED, "");
        assertThat(ctm.roleMismatches()).as("control: nothing listed before the node is observed").isEmpty();

        join(PROVISIONED);
        assertThat(mismatchWarns()).hasSize(1);
        assertThat(ctm.roleMismatches()).containsExactly(new ClusterTopologyManager.RoleMismatch(PROVISIONED, "worker", "", "UNKNOWN"));

        ctm.onMembershipDecision(MembershipDecision.nodeRemoved(PROVISIONED, List.of(SELF, PEER_A, PEER_B)));
        assertThat(ctm.roleMismatches()).as("the entry survives the node's departure — a restart may follow")
                                        .containsExactly(new ClusterTopologyManager.RoleMismatch(PROVISIONED, "worker", "", "UNKNOWN"));

        join(PROVISIONED);

        assertThat(ctm.roleMismatches()).as("ledger-after-rejoin")
                                        .containsExactly(new ClusterTopologyManager.RoleMismatch(PROVISIONED, "worker", "", "UNKNOWN"));
        assertThat(mismatchWarns()).as("total-mismatch-warns: the rejoin is compared again and re-reported")
                                   .hasSize(2);
    }

    /// The other half of the retained intent: a rejoin that NOW carries the right label clears the
    /// entry (and says so at INFO). Relabelled `worker`, the node rejoins on the worker channel.
    @Test
    void rejoin_nowCorrectlyLabelled_clearsTheEntry() {
        provision(PROVISIONED, NodeRole.WORKER);
        describe(PROVISIONED, "");
        join(PROVISIONED);
        assertThat(ctm.roleMismatches()).hasSize(1);

        ctm.onMembershipDecision(MembershipDecision.nodeRemoved(PROVISIONED, List.of(SELF, PEER_A, PEER_B)));
        describe(PROVISIONED, "worker");
        ctm.onWorkerJoin(WorkerJoinDecision.workerJoinDecision(PROVISIONED, "worker", new HlcTimestamp(HlcTimestamp.pack(2L, 0), SELF)));

        assertThat(ctm.roleMismatches()).as("a correctly relabelled rejoin clears the entry").isEmpty();
        assertThat(mismatchWarns()).as("no second WARN — the halves now agree").hasSize(1);
        assertThat(appender.capturedInfos()).filteredOn(msg -> msg.contains(CLEARED_MARKER))
                                            .hasSize(1)
                                            .first()
                                            .asString()
                                            .contains(PROVISIONED.id())
                                            .contains("was advertised ''");
    }

    /// The CTM's own forget point: `NodeDecommissioned` retires the id for good, so the intent and the
    /// entry go with it and a later join under that id is a stranger — not compared, not listed.
    @Test
    void decommission_forgetsTheIntent_soALaterJoinUnderThatIdIsNotCompared() {
        provision(PROVISIONED, NodeRole.WORKER);
        describe(PROVISIONED, "");
        join(PROVISIONED);
        assertThat(ctm.roleMismatches()).hasSize(1);

        ctm.onMembershipDecision(MembershipDecision.nodeDecommissioned(PROVISIONED, List.of(SELF, PEER_A, PEER_B)));
        assertThat(ctm.roleMismatches()).as("decommissioning drops the entry").isEmpty();

        join(PROVISIONED);

        assertThat(ctm.roleMismatches()).as("the intent was forgotten with the id").isEmpty();
        assertThat(mismatchWarns()).hasSize(1);
    }

    /// verify-1120 SF-2 — a deposed CTM drops decisions at its `active` gate, so a node that restarted
    /// while this node was not leading was never re-compared. Re-activation re-derives every retained
    /// intent against what membership holds NOW: the still-mislabelled node is re-reported, the one
    /// relabelled in the meantime is cleared.
    @Test
    void reactivation_reDerivesTheLedger_fromWhatMembershipHoldsNow() {
        provision(PROVISIONED, NodeRole.WORKER);
        provision(STRANGER, NodeRole.WORKER);
        describe(PROVISIONED, "");
        describe(STRANGER, "");
        join(PROVISIONED);
        join(STRANGER);
        assertThat(ctm.roleMismatches()).hasSize(2);
        assertThat(mismatchWarns()).hasSize(2);

        ctm.deactivate();
        describe(STRANGER, "worker");
        ctm.onWorkerJoin(WorkerJoinDecision.workerJoinDecision(STRANGER, "worker", new HlcTimestamp(HlcTimestamp.pack(3L, 0), SELF)));
        assertThat(ctm.roleMismatches()).as("control: the deposed CTM dropped the rejoin at its gate")
                                        .hasSize(2);

        ctm.activate();

        assertThat(ctm.roleMismatches()).as("re-derived on activation: the relabelled node is cleared, the other stays")
                                        .containsExactly(new ClusterTopologyManager.RoleMismatch(PROVISIONED, "worker", "", "UNKNOWN"));
        assertThat(mismatchWarns()).filteredOn(msg -> msg.contains(PROVISIONED.id()))
                                   .as("the still-mismatched node is re-reported on this activation")
                                   .hasSize(2);
        assertThat(mismatchWarns()).filteredOn(msg -> msg.contains(STRANGER.id()))
                                   .hasSize(1);
    }

    private void provision(NodeId nodeId, NodeRole intendedRole) {
        var result = ctm.provisionReplacement(nodeId, Option.none(), Set.of(SELF, PEER_A, PEER_B), intendedRole).await();

        assertThat(result.isSuccess()).as("fixture: the provision must be DISPATCHED for the intent to be on record")
                                      .isTrue();
        assertThat(result.unwrap()).isInstanceOf(ProvisionDisposition.Dispatched.class);
    }

    /// Membership's view of the node: the FSM tracks it with exactly this self-asserted role — what a
    /// node that booted with (or without) `AETHER_ROLE` advertises after the descriptor merge.
    private void describe(NodeId nodeId, String role) {
        descriptors.put(nodeId, role);
        tracked.add(nodeId);
    }

    /// A SWIM/discovery sighting as the `TopologyObserver` records it — first sighting wins.
    private void observe(NodeId nodeId, Map<String, String> labels) {
        var info = NodeInfo.nodeInfo(nodeId, NodeAddress.nodeAddress("10.0.0.9", 6000).unwrap(), labels);

        observer.handleDiscoveredNodes(new NetworkMessage.DiscoveredNodes(SELF, List.of(info)));
        assertThat(observer.get(nodeId).isPresent()).as("fixture: the observer must hold the node's NodeInfo")
                                                    .isTrue();
    }

    private void join(NodeId nodeId) {
        ctm.onMembershipDecision(MembershipDecision.nodeJoined(nodeId, List.of(SELF, PEER_A, PEER_B)));
    }

    private List<String> mismatchWarns() {
        return appender.capturedWarns()
                       .stream()
                       .filter(msg -> msg.contains(MISMATCH_MARKER))
                       .toList();
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
        var fresh = new LoggerConfig(LOGGER_NAME, Level.INFO, false);
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
        private record Captured(Level level, String message) {}

        private final List<Captured> messages = new CopyOnWriteArrayList<>();

        private CapturingAppender(String name, Layout<?> layout) {
            super(name, (Filter) null, layout, true, Property.EMPTY_ARRAY);
        }

        static CapturingAppender create(String name) {
            return new CapturingAppender(name, PatternLayout.createDefaultLayout());
        }

        @Override public void append(LogEvent event) {
            messages.add(new Captured(event.getLevel(), event.getMessage().getFormattedMessage()));
        }

        List<String> capturedWarns() {
            return messages.stream()
                           .filter(captured -> captured.level().isMoreSpecificThan(Level.WARN))
                           .map(Captured::message)
                           .toList();
        }

        List<String> capturedInfos() {
            return messages.stream()
                           .filter(captured -> captured.level().equals(Level.INFO))
                           .map(Captured::message)
                           .toList();
        }
    }
}
