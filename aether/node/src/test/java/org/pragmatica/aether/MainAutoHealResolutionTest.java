// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether;

import org.pragmatica.aether.config.ConfigLoader;
import org.pragmatica.aether.deployment.DeploymentMap;
import org.pragmatica.aether.deployment.cluster.ActionResult;
import org.pragmatica.aether.deployment.cluster.ClusterTopologyManager;
import org.pragmatica.aether.deployment.cluster.NodeAction;
import org.pragmatica.aether.deployment.cluster.NodeLifecycleManager;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.ProvisionSpec;
import org.pragmatica.aether.slice.generation.HealthHint;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterPhase;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.consensus.topology.GenerationSnapshotSource;
import org.pragmatica.consensus.topology.MembershipView;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.consensus.topology.TopologyObserver;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.statemachine.FsmObserver;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.consensus.NodeId.nodeId;


/// #675: the three `[timeouts.scaling] auto_heal_*` timings are the operator surface of the three
/// auto-heal timings the runtime reads. `auto_heal_startup_cooldown` parsed into `TimeoutsConfig` and
/// stopped there — `Main.resolveAutoHeal` built the runtime `AutoHealConfig` from `DEFAULT` plus
/// `[cluster] max_nodes` only; `auto_heal_provisioning_timeout` and `auto_heal_swim_hints_ttl` had no
/// key at all, so `ClusterTopologyManagerRecord`'s provisioning backoff / reap backstop and
/// `MembershipFsm`'s SUSPECTED-hint decay ran on `DEFAULT` with no way to set them. The two read-site
/// tests below observe the configured value where it is CONSUMED, built from the resolved config the
/// way `AetherNode` wires it, not merely at the config record.
class MainAutoHealResolutionTest {
    private static final String MINIMAL_CLUSTER = """
        [cluster]
        environment = "docker"
        nodes = 3
        """;
    private static final NodeId SELF = nodeId("node-self").unwrap();
    private static final NodeId PEER = nodeId("node-a").unwrap();
    private static final NodeInfo INFO_SELF = NodeInfo.nodeInfo(SELF, NodeAddress.nodeAddress("localhost", 5000).unwrap());
    /// `ClusterTopologyManagerRecord.MAX_CONSECUTIVE_PROVISIONING_FAILURES` — the trip point (#148).
    private static final int FAILURES_TO_TRIP = 3;

    @Test
    void startupCooldown_reachesTheRuntimeConfig() {
        var toml = MINIMAL_CLUSTER + """

            [timeouts.scaling]
            auto_heal_startup_cooldown = "42s"
            """;

        ConfigLoader.loadFromString(toml)
                    .onFailure(cause -> fail(cause.message()))
                    .onSuccess(config -> assertThat(Main.resolveAutoHeal(Option.some(config)).startupCooldown())
                        .as("the declared cooldown must reach AutoHealConfig, not stop at the parse boundary")
                        .isEqualTo(timeSpan(42).seconds()));
    }

    /// Read site: `ClusterTopologyManagerRecord.recordProvisioningFailure` arms the provisioning backoff
    /// window from `autoHealConfig.provisioningTimeout()` once the circuit trips; `circuitBreakerState()`
    /// reports that window's end. With the 60s default the window ends ≥ 60s out, so a 42s key that
    /// stopped at the parse boundary fails the upper bound.
    @Test
    void provisioningTimeout_reachesTheProvisioningBackoff() {
        var toml = MINIMAL_CLUSTER + """

            [timeouts.scaling]
            auto_heal_provisioning_timeout = "42s"
            """;

        var ctm = topologyManager(resolve(toml));
        var before = System.currentTimeMillis();

        for (var i = 0; i < FAILURES_TO_TRIP; i++) {
            ctm.provisionReplacement(NodeId.randomNodeId(), Option.none(), Set.of(SELF), NodeRole.CORE).await();
        }
        var after = System.currentTimeMillis();
        var state = ctm.circuitBreakerState();

        assertThat(state.tripped()).as("three failed provisions trip the circuit").isTrue();
        assertThat(state.nextAllowedMs())
                .as("the backoff window is the CONFIGURED provisioning timeout (42s), not DEFAULT's 60s")
                .isBetween(before + 42_000L, after + 42_000L);
    }

    /// Read site: `MembershipFsm.healthHints()` ages a one-shot SWIM-suspect out of the quiesce hint after
    /// `suspectHintTtlMs` (#68), which `AetherNode` wires from `config.autoHeal().swimHintsTtl().millis()`.
    /// Past the 15s default but inside the configured 42s the hint must still stand.
    @Test
    void swimHintsTtl_reachesTheSuspectHintDecay() {
        var toml = MINIMAL_CLUSTER + """

            [timeouts.scaling]
            auto_heal_swim_hints_ttl = "42s"
            """;
        var clock = new long[]{10_000L};
        var fsm = MembershipFsm.membershipFsm(FsmObserver.noop(), () -> clock[0], resolve(toml).swimHintsTtl().millis());

        fsm.onSwimHealthy(PEER, 1L);
        fsm.onSwimFaulty(PEER, 2L);
        clock[0] = 10_000L + AutoHealConfig.DEFAULT_SWIM_HINTS_TTL.millis() + 1L;

        assertThat(fsm.healthHints())
                .as("past the 15s default but inside the configured 42s TTL the SUSPECTED hint must still stand")
                .containsEntry(PEER, HealthHint.SUSPECTED);

        clock[0] = 10_000L + 42_000L + 1L;

        assertThat(fsm.healthHints())
                .as("past the configured TTL the hint decays")
                .doesNotContainKey(PEER);
    }

    @Test
    void timings_defaultWhenAbsent_andMaxNodesStillCarried() {
        ConfigLoader.loadFromString(MINIMAL_CLUSTER + "max_nodes = 7\n")
                    .onFailure(cause -> fail(cause.message()))
                    .onSuccess(config -> {
                        var resolved = Main.resolveAutoHeal(Option.some(config));

                        assertThat(resolved.startupCooldown()).isEqualTo(AutoHealConfig.DEFAULT.startupCooldown());
                        assertThat(resolved.provisioningTimeout()).isEqualTo(AutoHealConfig.DEFAULT.provisioningTimeout());
                        assertThat(resolved.swimHintsTtl()).isEqualTo(AutoHealConfig.DEFAULT.swimHintsTtl());
                        assertThat(resolved.maxNodes()).isEqualTo(Option.some(7));
                    });
    }

    private static AutoHealConfig resolve(String toml) {
        return ConfigLoader.loadFromString(toml)
                           .onFailure(cause -> fail(cause.message()))
                           .map(config -> Main.resolveAutoHeal(Option.some(config)))
                           .unwrap();
    }

    /// The production-wired CTM (`ClusterTopologyManager.clusterTopologyManager`) with the resolved
    /// auto-heal config and a lifecycle manager whose every provision fails, so the circuit trips.
    private static ClusterTopologyManager topologyManager(AutoHealConfig autoHeal) {
        var config = new TopologyConfig(SELF, 5, timeSpan(60).seconds(), timeSpan(1).seconds(), List.of(INFO_SELF));
        var snapshotSource = new StubSnapshotSource();
        var observer = TopologyObserver.topologyObserver(config, quietRouter(), snapshotSource).unwrap();

        return ClusterTopologyManager.clusterTopologyManager(observer,
                                                             new FailingLifecycleManager(),
                                                             autoHeal,
                                                             DeploymentMap.deploymentMap(),
                                                             snapshotSource,
                                                             Option::none,
                                                             _ -> Promise.success(List.of()),
                                                             () -> ClusterPhase.NORMAL);
    }

    private static MessageRouter.MutableRouter quietRouter() {
        var router = MessageRouter.mutable();
        router.addRoute(NetworkServiceMessage.ListConnectedNodes.class, _ -> {});
        return router;
    }

    private static final class StubSnapshotSource implements GenerationSnapshotSource {
        @Override public Option<MembershipView> currentMembershipView() {
            return Option.none();
        }

        @Override public long observedRabiaTerm() {
            return 0L;
        }
    }

    /// Only `provisionNode` is reachable from `provisionReplacement`; the rest satisfy the interface.
    private static final class FailingLifecycleManager implements NodeLifecycleManager {
        @Override public Promise<ActionResult> executeAction(NodeAction action) {
            return Causes.cause("unreachable").promise();
        }

        @Override public Promise<InstanceInfo> provisionNode(ProvisionSpec spec) {
            return Causes.cause("stub provision failure").promise();
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
    }
}
