// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.forge;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.ProviderDefaults;
import org.pragmatica.aether.environment.ProvisionRequest;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.TerminalOperation;
import org.pragmatica.lang.Unit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// #232 — the in-process membership chaos cycle: kill → detect → decommission → heal.
///
/// ## This is a BUILD, not a revive
///
/// The Spike-2 substrate this ticket asks to revive (`MembershipChaosSpikeTest`, added `b793bf342`)
/// was deleted in `c0c4e6444` — "delete divergence-logger + flag + φ-accrual machinery, E2 phase 2a
/// of v2 migration" — **deliberately, as part of the membership v2 migration, not for flakiness**.
/// Its imports still name `PhiAccrualDetector`, a class that no longer exists, so restoring the file
/// would not compile. Its published timeline (transport ~5s / decommission ~15s / heal ~20s) was
/// measured against a detector that has since been replaced, so those numbers are historical context
/// and are deliberately NOT asserted here. Carrying them over would bake a stale premise into a new
/// gate — the #591 failure mode, where a ticket's stated mechanism no longer matched what ships.
///
/// ## What this covers that the existing chaos test does not
///
/// `MembershipBlackHoleSpikeTest` black-holes a victim (silent but still connected) and asserts it is
/// still detected dead and terminally removed. That is kill → detect → decommission for the HARDEST
/// detection case, and it stops there. This class takes the ordinary case — a hard kill, connections
/// closed — and carries it one leg further, through **heal**: auto-heal must provision a replacement
/// and the cluster must return to full counted membership. The two are complements, not duplicates:
/// that one asks "is a silent node noticed?", this one asks "does the cluster put itself back
/// together?".
///
/// ## Budgets are DERIVED, and the timeline is MEASURED
///
/// Every budget below is computed from a real constant in the shipping configuration, named at its
/// definition. They are ceilings for the assertions; the numbers this ticket actually wants are the
/// MEASURED milestones, logged as `CHAOS-CYCLE RESULT` and reported on the ticket. A budget that
/// happens to pass is not a measurement.
///
/// ## MEASURED TIMELINE (2026-08-27, this machine, 5-node in-JVM Ember, three runs)
///
/// ```
///                    run 1     run 2     run 3
///   decommission     6652ms    8147ms    5648ms
///   heal            21768ms   23754ms   21267ms
///   leader recovery      --   24765ms   22279ms
///   provisions            1         1         1
/// ```
///
/// Conditions: single 8-core/16GB host, all five nodes in one JVM, no competing load. These are
/// in-process numbers and are NOT a production SLO — a real cluster pays network and boot costs this
/// substrate does not. What they are good for is a regression baseline and an order-of-magnitude
/// check, which is what the ticket's "fast dev loop" asks for.
///
/// Against the phi-accrual-era numbers this ticket carries (decommission ~15s, heal ~20s):
/// decommission is now roughly HALF, heal is comparable. Recorded as an observation, not a claim
/// about the cause — the detector was replaced wholesale, so the two are not a controlled comparison.
///
/// ## Node count
///
/// Five: one kill leaves four, still a quorum of five, so the cluster is expected to survive and heal
/// rather than fence. Comfortably under the ~8-node in-JVM ceiling where SWIM probe-acks starve —
/// `CommunityFormationProbeTest` is @Disabled above it, and this class must not rediscover that.
///
/// ## Why the recording provider is load-bearing
///
/// Counted membership returning to five could in principle happen without provisioning (a node
/// rejoining). The recorder proves the heal leg went through the real `ComputeProvider.provision`
/// path — the same reason #509's probe needed one, in the opposite direction: there to prove NOTHING
/// was provisioned, here to prove something was.
@Tag("Heavy")
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MembershipChaosCycleTest {
    private static final Logger log = LoggerFactory.getLogger(MembershipChaosCycleTest.class);

    private static final int SIZE = 5;
    private static final int BASE_PORT = 20500;
    private static final int BASE_MGMT_PORT = 20600;
    private static final int BASE_APP_HTTP_PORT = 20700;

    /// `AutoHealConfig.DEFAULT` startupCooldown is 15s: the reconciler will not fill a deficit until a
    /// node has been up that long. Killing inside that window would measure the cooldown, not
    /// detection, so the run settles past it before the kill.
    private static final Duration AUTO_HEAL_STARTUP_COOLDOWN = Duration.ofSeconds(15);
    private static final Duration SETTLE = AUTO_HEAL_STARTUP_COOLDOWN.plusSeconds(5);

    /// SWIM suspicion (`SwimConfig.suspectTimeout`, 10s) plus NTT departure
    /// (`MembershipConfig.nttDepartureTimeout`, 15s) — the detection window `AutoHealSpec` names when
    /// it sizes its own hint TTL. 25s is the mechanism; the budget triples it so a loaded CI box does
    /// not turn a slow detection into a red build.
    private static final Duration DETECTION_WINDOW = Duration.ofSeconds(25);
    private static final Duration DECOMMISSION_BUDGET = DETECTION_WINDOW.multipliedBy(3);

    /// After the deficit is visible: `autoHealRetry` (10s) to re-arm, `provisioningTimeout` (60s) as
    /// the provider ceiling, then the replacement has to boot, join via SWIM and be counted. Doubled
    /// for the same CI-load reason.
    private static final Duration AUTO_HEAL_RETRY = Duration.ofSeconds(10);
    private static final Duration PROVISIONING_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration HEAL_BUDGET = AUTO_HEAL_RETRY.plus(PROVISIONING_TIMEOUT).multipliedBy(2);

    private static final Duration FORM_TIMEOUT = Duration.ofSeconds(240);
    private static final Duration POLL = Duration.ofMillis(500);

    private final ProvisionRecorder recorder = new ProvisionRecorder();
    private EmberCluster cluster;

    @BeforeAll
    @TerminalOperation
    void setUp() {
        cluster = emberCluster(SIZE, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, "chaos");
        cluster.withComputeProviderDecorator(recorder::wrap);
        cluster.start().await().onFailure(MembershipChaosCycleTest::failStart);

        await().atMost(FORM_TIMEOUT).pollInterval(POLL).until(() -> cluster.currentLeader().isPresent());
        await().atMost(FORM_TIMEOUT).pollInterval(POLL).until(() -> countedCores() == SIZE);
        log.info("CHAOS-CYCLE: {}-node cluster formed, leader={} countedCores={}",
                 SIZE, cluster.currentLeader().or("none"), countedCores());
    }

    @AfterAll
    @TerminalOperation
    void tearDown() {
        Option.option(cluster).onPresent(c -> c.stop().await());
    }

    @Test
    void killedCoreNode_isDecommissioned_andTheClusterHealsItselfBackToFullMembership() {
        settlePastAutoHealCooldown();

        var victim = nonLeaderNode();
        var provisionsBeforeKill = recorder.provisionCalls();

        log.info("CHAOS-CYCLE: leader={} victim={} (hard kill, connections close) provisionsBefore={}",
                 cluster.currentLeader().or("none"), victim, provisionsBeforeKill);

        var t0 = System.nanoTime();

        cluster.killNode(victim, false).await().onFailure(MembershipChaosCycleTest::failScenario);

        var decommissionMs = awaitMillis("DECOMMISSION",
                                         t0,
                                         DECOMMISSION_BUDGET,
                                         () -> !countedCoreIds().contains(victim));
        var healMs = awaitMillis("HEAL",
                                 t0,
                                 DECOMMISSION_BUDGET.plus(HEAL_BUDGET),
                                 () -> countedCores() >= SIZE);
        // Leadership is a CONVERGENCE property, not an instant one. The first run of this class read
        // `currentLeader()` at the moment heal completed and saw `none` — a re-election was in flight
        // as the replacement joined and changed the topology. Asserting on that instant would have
        // reported a healthy self-healing cluster as broken. What the cycle actually promises is that
        // the cluster settles back to having a leader, so that is what is awaited, and how long it
        // took is itself a number worth reporting.
        var leaderMs = awaitMillis("LEADER-RECOVERY",
                                   t0,
                                   DECOMMISSION_BUDGET.plus(HEAL_BUDGET),
                                   () -> cluster.currentLeader().isPresent());
        var provisionsAfter = recorder.provisionCalls();

        // Logged BEFORE the assertions: a failing assertion is exactly when this timeline is the
        // evidence the ticket asked for, and an AssertionError would skip anything logged after it.
        log.info("CHAOS-CYCLE RESULT (measured, not carried over from the phi-accrual era): "
                 + "decommission={}ms heal={}ms leaderRecovery={}ms provisions={} countedCores={} leader={} ids={}",
                 decommissionMs, healMs, leaderMs, provisionsAfter - provisionsBeforeKill,
                 countedCores(), cluster.currentLeader().or("none"), countedCoreIds());

        assertThat(decommissionMs)
            .as("a hard-killed core must leave counted membership within %ds "
                + "(SWIM suspicion 10s + NTT departure 15s, tripled for CI load); -1 means it never did",
                DECOMMISSION_BUDGET.toSeconds())
            .isBetween(0L, DECOMMISSION_BUDGET.toMillis());

        assertThat(provisionsAfter)
            .as("auto-heal must reach the real ComputeProvider.provision path — counted membership "
                + "returning to %d without a provision would mean something rejoined rather than the "
                + "cluster healing itself, and the heal leg would be unproven", SIZE)
            .isGreaterThan(provisionsBeforeKill);

        assertThat(healMs)
            .as("the replacement must boot, join and be COUNTED within %ds; a provision that never "
                + "joins leaves the cycle half-proven", DECOMMISSION_BUDGET.plus(HEAL_BUDGET).toSeconds())
            .isBetween(0L, DECOMMISSION_BUDGET.plus(HEAL_BUDGET).toMillis());

        assertThat(leaderMs)
            .as("the cluster must settle back to having a leader within %ds — healing to %d nodes is "
                + "not a success if leadership never recovers. Awaited, not sampled: a re-election is "
                + "legitimately in flight while the replacement joins",
                DECOMMISSION_BUDGET.plus(HEAL_BUDGET).toSeconds(), SIZE)
            .isBetween(0L, DECOMMISSION_BUDGET.plus(HEAL_BUDGET).toMillis());

        assertThat(countedCoreIds())
            .as("the dead node must NOT reappear in counted membership — the cluster heals by "
                + "replacing it, not by resurrecting the id that was decommissioned")
            .doesNotContain(victim);
    }

    /// Polls until `condition`, returning elapsed millis from `t0`, or -1 on timeout. Returning a
    /// sentinel rather than throwing lets the caller dump the timeline and assert with its own
    /// message instead of dying with a bare Awaitility timeout.
    private long awaitMillis(String label, long t0, Duration budget, java.util.function.BooleanSupplier condition) {
        var latch = new AtomicLong(-1);

        try {
            await().atMost(budget.plusSeconds(10))
                   .pollInterval(POLL)
                   .until(() -> {
                       if (condition.getAsBoolean()) {
                           latch.compareAndSet(-1, (System.nanoTime() - t0) / 1_000_000);

                           return true;
                       }
                       log.info("CHAOS-CYCLE: {} pending at t+{}ms countedCores={} ids={}",
                                label, (System.nanoTime() - t0) / 1_000_000, countedCores(), countedCoreIds());

                       return false;
                   });
        } catch (Exception e) {
            log.warn("CHAOS-CYCLE: {} NOT reached within {}s", label, budget.toSeconds());
        }

        return latch.get();
    }

    private void settlePastAutoHealCooldown() {
        log.info("CHAOS-CYCLE: settling {}s past the {}s auto-heal startup cooldown so the kill "
                 + "measures detection rather than the cooldown",
                 SETTLE.toSeconds(), AUTO_HEAL_STARTUP_COOLDOWN.toSeconds());
        await().pollDelay(SETTLE).timeout(SETTLE.plusSeconds(10)).until(() -> true);
    }

    private String nonLeaderNode() {
        var leader = cluster.currentLeader().or("");

        return cluster.status()
                      .nodes()
                      .stream()
                      .map(EmberCluster.NodeStatus::id)
                      .filter(id -> !id.equals(leader))
                      .findFirst()
                      .orElseThrow(() -> new AssertionError("no non-leader node to kill"));
    }

    /// Counted membership comes from the membership FSM, NOT `EmberCluster.status()`. Ember's status
    /// is its own registry of nodes it manages; a killed node can still appear there, so asserting
    /// decommission against it would pass or fail for reasons unrelated to membership. The FSM's
    /// `coreCountedMembers()` is the authority the reconciler itself reads.
    private int countedCores() {
        return leaderOrAnyNode().map(node -> node.membershipFsm()
                                                 .coreCountedMembers()
                                                 .size())
                                .or(0);
    }

    private String countedCoreIds() {
        return leaderOrAnyNode().map(node -> idStrings(node.membershipFsm().coreCountedMembers()))
                                .or("");
    }

    private Option<AetherNode> leaderOrAnyNode() {
        return cluster.currentLeader()
                      .flatMap(cluster::getNode)
                      .orElse(() -> Option.from(cluster.allNodes().stream().findFirst()));
    }

    private static String idStrings(Set<NodeId> ids) {
        return ids.stream()
                  .map(NodeId::id)
                  .sorted()
                  .toList()
                  .toString();
    }

    private static void failStart(Cause cause) {
        throw new AssertionError("Cluster start failed: " + cause.message());
    }

    private static void failScenario(Cause cause) {
        throw new AssertionError("Scenario step failed: " + cause.message());
    }

    /// Counts every provision and ALWAYS delegates — this class must not inject provider faults, it
    /// is measuring the healthy heal path.
    private static final class ProvisionRecorder {
        private final List<String> calls = new CopyOnWriteArrayList<>();

        private ComputeProvider wrap(ComputeProvider delegate) {
            return new RecordingProvider(delegate);
        }

        private int provisionCalls() {
            return calls.size();
        }

        private final class RecordingProvider implements ComputeProvider {
            private final ComputeProvider delegate;

            private RecordingProvider(ComputeProvider delegate) {
                this.delegate = delegate;
            }

            @Override
            public ProviderDefaults providerDefaults() {
                return delegate.providerDefaults();
            }

            @Override
            public Promise<InstanceInfo> createFrom(ProvisionRequest request) {
                var requested = request.context().nodeId().or("<unnamed>");

                calls.add(requested);
                log.info("CHAOS-CYCLE: PROVISION #{} requestedNodeId={}", calls.size(), requested);

                return delegate.createFrom(request);
            }

            @Override
            public Promise<Unit> terminate(InstanceId instanceId) {
                return delegate.terminate(instanceId);
            }

            @Override
            public Promise<List<InstanceInfo>> listInstances() {
                return delegate.listInstances();
            }

            @Override
            public Promise<InstanceInfo> instanceStatus(InstanceId instanceId) {
                return delegate.instanceStatus(instanceId);
            }
        }
    }
}
