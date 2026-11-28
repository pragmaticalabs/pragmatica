// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.assembly;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import org.pragmatica.aether.deployment.DeploymentMap;
import org.pragmatica.aether.deployment.cluster.ActionResult;
import org.pragmatica.aether.deployment.cluster.ClusterTopologyManager;
import org.pragmatica.aether.deployment.cluster.DrainGate;
import org.pragmatica.aether.deployment.cluster.DrainReason;
import org.pragmatica.aether.deployment.cluster.DrainSink;
import org.pragmatica.aether.deployment.cluster.MembershipLiveness;
import org.pragmatica.aether.deployment.cluster.NodeAction;
import org.pragmatica.aether.deployment.cluster.NodeLifecycleManager;
import org.pragmatica.aether.deployment.membership.fsm.WorkerJoinDecision;
import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.ProvisionSpec;
import org.pragmatica.aether.metrics.DrainCommandRegistry;
import org.pragmatica.aether.metrics.DrainPin;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterPhase;
import org.pragmatica.aether.slice.kvstore.AetherValue.TopologyEntry;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.GenerationSnapshotSource;
import org.pragmatica.consensus.topology.MembershipView;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.consensus.topology.TopologyObserver;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// #3858 round 2, invariant 2: a drain entry is cleared only by the end of the drain that set it
/// (`DrainCommandRegistry:32-33`); an attempt that ends by `release` leaves its command to the DEAD edge, so no later attempt may
/// own it (`TopologyDrains:28-29`, `:61-62`).
///
/// The harness drives the events in PRODUCTION ORDER, through the real `ClusterTopologyManager` over the real `TopologyDrains` and
/// `DrainCommandRegistry`, wired as `TopologyAssembly` wires them (`drains.sink`, `drains::clear`): a rejoin of a retired identity
/// is the EVICTION (`evict`, the gate, `endAttempt` by release); `drainNode` of the same, still-live process is the later DRAIN
/// attempt (`pinDrain` hands it the eviction's id); then each end that clears by id (grace expiry whose reap does not run, cancel on
/// rejoin, deactivation).
///
/// Each failing input is two tests: an ENABLED tripwire asserting today's behaviour (the eviction's command is gone while the
/// process lives; it goes red when that changes and its message says what to enable), and the real assertion, `@Disabled` for lane B.
/// A test without a `@Disabled` twin is a control.
///
/// Sites of the #3858 round 2 enumeration that no test in this class names, and why none can break the invariant [reading]:
/// - G2, O1, O2 (the OPERATOR slot's set): the slot has no attempt end; only the DEAD or rejoin edge clears it (O3 -> G4). The shared
///   id between the two requesters is F3, closed and pinned by `TopologyDrainsRequesterTest`.
/// - G3, G4, O3 (clear by id, clear by incarnation): a clear by id of a pin another attempt re-took is the failing input above; a clear
///   by incarnation at the DEAD or rejoin edge ends the life every entry of the node was set for, which is the end the invariant names.
/// - G5, O5 (reads), O4, O6, T4 (wiring): no mutation.
/// - T2 (`TopologyDrains.clear`): a no-op when the holder holds nothing (the F2 fix); it only clears when T1 re-took the pin, which is
///   the E1 input.
/// - M1's `raiseToDrain` (a pending EVICTION entry becomes a DRAIN attempt): the entry keeps its `seq`, so the same opener's gate and
///   grace timer own it: one attempt sets the command once and one end clears it.
/// - M2 joining an existing entry, M3, M4's DRAIN arm: the join sets nothing; the other two are reached by every test above.
/// - The gate against a concurrent cancel (rule M): pinned by `ClusterTopologyManagerDrainFenceTest`. [unverified: not read in this lane]
class DrainEntryOwnershipTest {
    private static final NodeId SELF = nodeId("node-self").unwrap();
    private static final NodeId TARGET = nodeId("node-d").unwrap();
    private static final HlcTimestamp HLC = new HlcTimestamp(HlcTimestamp.pack(1L, 0), SELF);
    private static final String EVICTED = OperatorWarningCode.NODE_RETIRED_IDENTITY_REJOINED.code();
    private static final String CANCELLED = OperatorWarningCode.NODE_DRAIN_CANCELLED_REJOINED.code();
    private static final String LANE_B = "#3858 round 2 lane B";

    private final DrainCommandRegistry registry = DrainCommandRegistry.drainCommandRegistry();
    private final TopologyDrains drains = TopologyDrains.topologyDrains(registry);
    private final CopyOnWriteArrayList<String> raised = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<NodeId> refusalsAsked = new CopyOnWriteArrayList<>();
    private final AtomicBoolean retiring = new AtomicBoolean(true);
    private final AtomicBoolean failNextRequest = new AtomicBoolean(false);
    private ClusterTopologyManager ctm;

    @AfterEach
    void tearDown() {
        ctm.deactivate();
    }

    private void start(AutoHealConfig config) {
        ctm = manager(config);
        ctm.setRetirementRefusal(_ -> Option.none());
        ctm.setOperatorWarningSink(OperatorWarningSink.handingOffTo(this::record));
        ctm.activate();
    }

    private void record(OperatorWarning warning) {
        raised.add(warning.code().code());
    }

    private List<DrainPin> pins() {
        return registry.pinOf(TARGET).stream().toList();
    }

    private void join() {
        ctm.onWorkerJoin(WorkerJoinDecision.workerJoinDecision(TARGET, "worker", HLC));
    }

    /// The eviction, then the later manager DRAIN attempt of the same live process, which shares the eviction's pin.
    private void evictThenDrain() {
        join();
        await().atMost(Duration.ofSeconds(5)).until(() -> raised.contains(EVICTED));
        var evictionsPin = pins();

        assertThat(evictionsPin).as("instrument: the eviction's command is set").hasSize(1);
        assertThat(drains.held()).as("instrument: the eviction's attempt ended by release").isZero();
        assertThat(ctm.drainNode(TARGET, DrainReason.OPERATOR_COMMAND).await().isSuccess()).as("instrument: the DRAIN attempt was accepted").isTrue();
        assertThat(pins()).as("instrument: the DRAIN attempt owns the eviction's pin (same id)").isEqualTo(evictionsPin);
        assertThat(drains.held()).as("instrument: the DRAIN attempt holds the id").isEqualTo(1);
    }

    private static String fixed(String path, String realTest) {
        return "#3858 round 2 E1 (" + path + ") is fixed: delete this tripwire and enable " + realTest;
    }

    // ---- E1 through grace expiry whose reap does not run (M6 `reapOwned` clears at its end, :2980) ----

    private void refuseTheReapThenExpireTheGrace() {
        ctm.setRetirementRefusal(id -> {
            refusalsAsked.add(id);
            return Option.some("#3858 test: reap refused");
        });
        await().atMost(Duration.ofSeconds(10)).until(() -> !refusalsAsked.isEmpty());
        // `graceTerminate` holds the manager's monitor from the refusal check to the clear; taking it here waits for the clear.
        synchronized (ctm) {
            assertThat(refusalsAsked).as("instrument: the grace expired and the reap was refused").isNotEmpty();
        }
    }

    /// Control: the same expiry with no eviction before it. The DRAIN attempt's own end clears its own command, so the
    /// monitor synchronisation above reaches the clear. Green before and after the fix.
    @Test
    void e1Control_graceExpiryOfAnAttemptWithNoEviction_clearsItsOwnCommand() {
        start(AutoHealConfig.DEFAULT.withProvisioningTimeout(timeSpan(1).seconds()));
        assertThat(ctm.drainNode(TARGET, DrainReason.OPERATOR_COMMAND).await().isSuccess()).as("instrument: the attempt was accepted").isTrue();
        refuseTheReapThenExpireTheGrace();

        assertThat(registry.isDrainRequested(TARGET)).as("control: the attempt's own end clears its own command").isFalse();
    }

    /// Tripwire for E1 by a refused reap: today the DRAIN attempt's grace expiry clears the eviction's command.
    @Test
    void e1Tripwire_evictionThenDrain_refusedReapAtGraceExpiry_todayClearsTheEvictionsCommand() {
        start(AutoHealConfig.DEFAULT.withProvisioningTimeout(timeSpan(1).seconds()));
        evictThenDrain();
        refuseTheReapThenExpireTheGrace();

        assertThat(registry.isDrainRequested(TARGET)).as(fixed("refused reap", "e1_evictionThenDrain_refusedReapAtGraceExpiry_keepsTheEvictionsCommand")).isFalse();
    }

    /// Red when: the later DRAIN attempt's grace expiry, whose reap is refused, ends the eviction's command while the node lives.
    @Disabled(LANE_B)
    @Test
    void e1_evictionThenDrain_refusedReapAtGraceExpiry_keepsTheEvictionsCommand() {
        start(AutoHealConfig.DEFAULT.withProvisioningTimeout(timeSpan(1).seconds()));
        evictThenDrain();
        refuseTheReapThenExpireTheGrace();

        assertThat(registry.isDrainRequested(TARGET)).as("E1: a later attempt's refused-reap expiry ended the eviction's command").isTrue();
    }

    // ---- E1 through the cancel (M7 `cancelAttempt`, :3956) ----

    private void rejoinAsANewIncarnation() {
        retiring.set(false);
        join();
        await().atMost(Duration.ofSeconds(5)).until(() -> raised.contains(CANCELLED));
    }

    /// Tripwire for E1 by a cancel: today the cancel of the DRAIN attempt clears the eviction's command.
    @Test
    void e1Tripwire_evictionThenDrain_cancelOnRejoin_todayClearsTheEvictionsCommand() {
        start(AutoHealConfig.DEFAULT);
        evictThenDrain();
        rejoinAsANewIncarnation();

        assertThat(registry.isDrainRequested(TARGET)).as(fixed("cancel", "e1_evictionThenDrain_cancelOnRejoin_keepsTheEvictionsCommand")).isFalse();
    }

    /// Red when: the cancel of the later DRAIN attempt ends the eviction's command while the node lives.
    @Disabled(LANE_B)
    @Test
    void e1_evictionThenDrain_cancelOnRejoin_keepsTheEvictionsCommand() {
        start(AutoHealConfig.DEFAULT);
        evictThenDrain();
        rejoinAsANewIncarnation();

        assertThat(registry.isDrainRequested(TARGET)).as("E1: a later attempt's cancel ended the eviction's command").isTrue();
    }

    // ---- E1 through the deactivation (M8 `deactivate`, :4444) ----

    /// Tripwire for E1 by a deactivation: today the deactivation clears the eviction's command with the DRAIN attempt's.
    @Test
    void e1Tripwire_evictionThenDrain_deactivation_todayClearsTheEvictionsCommand() {
        start(AutoHealConfig.DEFAULT);
        evictThenDrain();
        ctm.deactivate();

        assertThat(registry.isDrainRequested(TARGET)).as(fixed("deactivate", "e1_evictionThenDrain_deactivation_keepsTheEvictionsCommand")).isFalse();
    }

    /// Red when: the deactivation clears the later DRAIN attempt's id, which is the eviction's, while the node lives.
    @Disabled(LANE_B)
    @Test
    void e1_evictionThenDrain_deactivation_keepsTheEvictionsCommand() {
        start(AutoHealConfig.DEFAULT);
        evictThenDrain();
        ctm.deactivate();

        assertThat(registry.isDrainRequested(TARGET)).as("E1: a deactivation ended the eviction's command").isTrue();
    }

    // ---- M5 `untrack` (a failed request ends its attempt by release too): the same shape through the other release ----

    /// A DRAIN attempt whose request failed after the sink set the command ends by release (`untrack`, :2543) and leaves the command;
    /// a later DRAIN attempt of the same process takes the same pin, and its cancel clears it.
    private void failedAttemptThenAnotherThenCancel() {
        retiring.set(false);
        start(AutoHealConfig.DEFAULT);
        failNextRequest.set(true);
        assertThat(ctm.drainNode(TARGET, DrainReason.OPERATOR_COMMAND).await().isFailure()).as("instrument: the request failed").isTrue();
        var failedAttemptsPin = pins();

        assertThat(failedAttemptsPin).as("instrument: the failed attempt left its command").hasSize(1);
        assertThat(drains.held()).as("instrument: the failed attempt released its id").isZero();
        assertThat(ctm.drainNode(TARGET, DrainReason.OPERATOR_COMMAND).await().isSuccess()).as("instrument: the second attempt was accepted").isTrue();
        assertThat(pins()).as("instrument: the second attempt owns the failed attempt's pin (same id)").isEqualTo(failedAttemptsPin);
        join();
        await().atMost(Duration.ofSeconds(5)).until(() -> raised.contains(CANCELLED));
    }

    /// Tripwire for E1 by a failed request: today the later attempt's cancel clears the command the failed attempt left.
    @Test
    void e1FailedRequestTripwire_failedAttemptThenAnotherThenCancel_todayClearsTheLeftCommand() {
        failedAttemptThenAnotherThenCancel();

        assertThat(registry.isDrainRequested(TARGET)).as(fixed("failed request", "e1FailedRequest_failedAttemptThenAnotherThenCancel_keepsTheLeftCommand")).isFalse();
    }

    /// Red when: a later attempt's cancel ends the command a failed (released) attempt left to the DEAD edge.
    @Disabled(LANE_B)
    @Test
    void e1FailedRequest_failedAttemptThenAnotherThenCancel_keepsTheLeftCommand() {
        failedAttemptThenAnotherThenCancel();

        assertThat(registry.isDrainRequested(TARGET)).as("E1: a later attempt's cancel ended the command a released attempt left").isTrue();
    }

    private DrainSink sink() {
        var production = drains.sink(_ -> {});

        return new DrainSink() {
            @Override
            public Promise<Unit> request(NodeId target, DrainGate gate) {
                if (failNextRequest.compareAndSet(true, false)) {
                    drains.request(target);

                    return Causes.cause("apply failed").promise();
                }

                return production.request(target, gate);
            }

            @Override
            public Option<Promise<Unit>> inFlight(NodeId target) {
                return production.inFlight(target);
            }

            @Override
            public void release(NodeId target) {
                production.release(target);
            }
        };
    }

    private ClusterTopologyManager manager(AutoHealConfig autoHeal) {
        var self = NodeInfo.nodeInfo(SELF, NodeAddress.nodeAddress("localhost", 5000).unwrap());
        var peer = NodeInfo.nodeInfo(nodeId("node-a").unwrap(), NodeAddress.nodeAddress("localhost", 5001).unwrap());
        var config = new TopologyConfig(SELF, 5, timeSpan(60).seconds(), timeSpan(1).seconds(), List.of(self, peer));
        var snapshots = new EmptySnapshotSource();
        var observer = TopologyObserver.topologyObserver(config, quietRouter(), snapshots).unwrap();
        var liveness = MembershipLiveness.membershipLiveness(Set::of,
                                                             Set::of,
                                                             _ -> false,
                                                             _ -> false,
                                                             Set::of,
                                                             () -> 5,
                                                             _ -> Option.none());

        return ClusterTopologyManager.clusterTopologyManager(observer,
                                                             new SwitchableLifecycle(retiring),
                                                             autoHeal,
                                                             DeploymentMap.deploymentMap(),
                                                             snapshots,
                                                             DrainEntryOwnershipTest::clusterConfig,
                                                             _ -> Promise.success(List.of()),
                                                             () -> ClusterPhase.NORMAL,
                                                             sink(),
                                                             drains::clear,
                                                             Option::none,
                                                             liveness);
    }

    private static Option<ClusterConfigValue> clusterConfig() {
        return Option.some(new ClusterConfigValue(Option.none(),
                                                  "",
                                                  "1.0.0",
                                                  List.of(new TopologyEntry("primary", "core", 5)),
                                                  3,
                                                  9,
                                                  "test",
                                                  1L,
                                                  System.currentTimeMillis()));
    }

    private static MessageRouter.MutableRouter quietRouter() {
        var router = MessageRouter.mutable();

        router.addRoute(NetworkServiceMessage.ListConnectedNodes.class, _ -> {});

        return router;
    }

    private static final class EmptySnapshotSource implements GenerationSnapshotSource {
        @Override
        public Option<MembershipView> currentMembershipView() {
            return Option.none();
        }

        @Override
        public long observedRabiaTerm() {
            return 0L;
        }
    }

    /// Answers a re-admission as a retired identity (the join is an EVICTION) while `retiring` is set, and as done (the join is a new
    /// incarnation, which cancels a pending DRAIN) after it is cleared.
    private record SwitchableLifecycle(AtomicBoolean retiring) implements NodeLifecycleManager {
        @Override
        public Promise<Unit> readmitExternal(NodeId nodeId) {
            return retiring.get()
                   ? new RetiredIdentityRejoined("rejoined", "its-replacement").promise()
                   : Promise.unitPromise();
        }

        @Override
        public Promise<ActionResult> executeAction(NodeAction action) {
            return Causes.cause("not used").promise();
        }

        @Override
        public Promise<InstanceInfo> provisionNode(ProvisionSpec spec) {
            return Causes.cause("not used").promise();
        }

        @Override
        public Promise<Unit> terminateNode(NodeId nodeId) {
            return Promise.unitPromise();
        }

        @Override
        public Promise<List<InstanceInfo>> listInstances(Map<String, String> tagFilter) {
            return Promise.success(List.of());
        }

        @Override
        public Promise<List<InstanceInfo>> instancesForNode(NodeId nodeId) {
            return Promise.success(List.of());
        }

        @Override
        public boolean isCloudManaged() {
            return true;
        }
    }
}
