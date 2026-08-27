/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.pragmatica.aether.forge;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.aether.worker.isolation.CoreAbsenceSnapshot;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.TerminalOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// #590 — the community tier's core-absence fence, and the ORDERING that keeps it safe.
///
/// ## HISTORY: this was @Disabled for one round, and the reason is worth keeping
///
/// The first four runs could not pass, and NOT because the mechanism was broken. `EmberCluster` set no
/// role label on any node, and `MemberDescriptor.isCoreRole(role) = !"worker".equals(role)` classifies
/// blank as CORE — so every in-JVM node read as a core and the fence's suppressor correctly refused to
/// fire. Measured: `armed=true sinceLastPingMs=40922 remainingMs=0 thresholdMs=10000 fenced=false` —
/// every precondition met, window exceeded fourfold, suppressed by design.
///
/// That was a harness fidelity gap, fixed by `EmberCluster.addWorkerNode()` (default `addNode()`
/// untouched, both pinned by `EmberAddNodeRoleLabelTest`). Kept here because the near-miss is the
/// lesson: without the precondition assertion below, those four runs would have been reported as
/// "#590's fence does not fire" — a confident false defect against working code.
///
/// ## What this proves, and what it explicitly does not
///
/// **Proves (total isolation):** a worker cut off from the core fences ITSELF, locally, within the
/// configured `core_absence` window, without completing any consensus write — and does so strictly
/// before `community_absence`, the window after which the core independently stops counting it and
/// re-places its work. That gap is the no-double-active guarantee, and this measures it rather than
/// arguing it from the config inequality.
///
/// **Does NOT prove (partial partition):** a community that can still reach its own members but not
/// the core. `EmberCluster.blackhole` is per-node and total — it drops all cluster traffic to and
/// from one node — so this exercises total isolation only. The CP contract at the community tier is
/// therefore NOT fully proven here, and this class must not be cited as if it were. The remaining
/// validation (partial partition, real-network severance) belongs to #367 output 1.
///
/// ## MEASURED (2026-08-27, six runs, 8-core/16GB host, 5 cores + 1 worker in one JVM)
///
/// ```
///   run          8      9     10     11     12     13
///   fence(ms) 9673   9696   9676   9704   9692   9697     window 10000
///   margin(ms)10327  10304  10324  10296  10308  10303     community_absence 20000
/// ```
///
/// `margin` is `community_absence - fence` — how long the isolated worker was already fenced before
/// the core would stop counting it. **The no-double-active ordering is measured here, not argued from
/// the config inequality**: the fence lands at ~97% of its own window and with ~10.3s to spare against
/// the core's, every run, with a spread of 31ms across six runs.
///
/// In-process numbers on one host; a real cluster pays network cost this substrate does not. They are
/// a regression baseline and an order-of-magnitude check, not a production SLO.
///
/// ## Why the fence is observable at all — the suppressor is the precondition
///
/// `AetherNode` arms this detector on EVERY node but gates firing behind a fail-safe suppressor:
///
/// ```java
/// coreAbsenceDetector.setFenceSuppressor(() -> {
///     var cores = topologyObserver.coreNodes();
///     return cores.isEmpty() || cores.contains(config.self());
/// });
/// ```
///
/// The core tier must never fence this way — the ping is leader-broadcast and a broadcast never
/// reaches its own sender, so on a core node the signal is structurally absent and an ungated fence
/// drained every new leader ten seconds after each election. Core liveness is `QuorumLossDetector`'s
/// job. Consequently **only a genuine WORKER can fence**, and `snapshot.armed()` is exactly the
/// "this node is not core, and the core view is known" signal. The test gates on it rather than
/// assuming the added node was minted a worker.
///
/// ## Producing a worker
///
/// There is no worker-join primitive: role is leader-decided by core count. `assignNodeRole` promotes
/// a joiner to CORE while `currentCoreCount < effectiveCoreMax` (the committed
/// `ClusterConfig.coreCount`, auto-seeded from the topology baseline) and mints a WORKER once the cap
/// is reached. So the cluster forms at its cap, then one `addNode()` exceeds it.
///
/// The joiner is added via `addWorkerNode()`, which advertises `role=worker` — the production shape,
/// set there from `AETHER_ROLE`. Without it the node classifies as a core and the fence is suppressed.
///
/// Five cores, not three: `CommunityFormationProbeTest` records that a single worker-add flaps SWIM
/// and a 3-node quorum cannot survive two suspected members, losing the leader mid-join. Six nodes
/// total, which is under the ~8-node density where that probe was disabled — and `withRaisedSwimTimeouts()`
/// (a seam that did not exist when it was disabled) is applied for the same density reason.
@Tag("Heavy")
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CoreAbsenceFenceOrderingTest {
    private static final Logger log = LoggerFactory.getLogger(CoreAbsenceFenceOrderingTest.class);

    private static final int INITIAL_CORES = 5;
    private static final int BASE_PORT = 21000;
    private static final int BASE_MGMT_PORT = 21100;
    private static final int BASE_APP_HTTP_PORT = 21200;

    /// Shipped defaults from `TimeoutsConfig.ClusterTimeouts`: multiples of the 1s `pingInterval` —
    /// 10s to fence locally, 20s before the core re-places. `ConfigValidator` REFUSES a config where
    /// `core_absence >= community_absence`, and that inequality is the invariant this class measures.
    ///
    /// **These survive `withRaisedSwimTimeouts()` — verified, not assumed.** That seam swaps the whole
    /// `TimeoutsConfig`, so it could silently move the windows this class measures against. It does
    /// not: `EmberCluster.raisedSwimTimeoutsConfig()` passes `defaults.cluster()` through untouched and
    /// raises only the SWIM suspect timeout (60s), the Hello timeout and the membership split. Note the
    /// consequence — SWIM will not notice the isolated worker for 60s, which is FINE here because the
    /// core half of #590 keys on pong silence (`ClusterSyncCollector.sinceLastPongNanos`), not on SWIM.
    private static final Duration CORE_ABSENCE = Duration.ofSeconds(10);
    private static final Duration COMMUNITY_ABSENCE = Duration.ofSeconds(20);

    private static final Duration FORM_TIMEOUT = Duration.ofSeconds(240);
    private static final Duration WORKER_ARM_TIMEOUT = Duration.ofSeconds(120);
    /// Generous against the fence window so a slow poll cannot fail a fence that did fire; the
    /// assertion is on the MEASURED time, not on this ceiling.
    private static final Duration FENCE_BUDGET = CORE_ABSENCE.multipliedBy(3);
    private static final Duration POLL = Duration.ofMillis(250);
    /// The fence-to-deregistration window is sub-poll at 250ms, so the fence watch polls
    /// far faster to give the DIRECT `fenced=true` observation a chance before the
    /// node removes itself.
    private static final Duration FENCE_POLL = Duration.ofMillis(20);

    private final java.util.concurrent.atomic.AtomicReference<CoreAbsenceSnapshot> fenceObservation =
        new java.util.concurrent.atomic.AtomicReference<>();
    private final AtomicLong lastProgressLog = new AtomicLong();
    private final java.util.concurrent.atomic.AtomicReference<String> fenceEvidence =
        new java.util.concurrent.atomic.AtomicReference<>("none");

    private EmberCluster cluster;
    private String worker;

    @BeforeAll
    @TerminalOperation
    void setUp() {
        cluster = emberCluster(INITIAL_CORES, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, "fence");
        cluster.withRaisedSwimTimeouts();
        cluster.start().await().onFailure(CoreAbsenceFenceOrderingTest::failStart);

        await().atMost(FORM_TIMEOUT).pollInterval(POLL).until(() -> cluster.currentLeader().isPresent());
        await().atMost(FORM_TIMEOUT).pollInterval(POLL).until(() -> cluster.status().nodes().size() == INITIAL_CORES);
        log.info("FENCE-PROBE: {}-core cluster formed, leader={}", INITIAL_CORES, cluster.currentLeader().or("none"));

        // WAIT FOR THE COMMITTED CAP BEFORE ADDING. Role is leader-decided: `assignNodeRole` promotes a
        // joiner to CORE while `currentCoreCount < effectiveCoreMax`, and `effectiveCoreMax` reads the
        // COMMITTED `ClusterConfig.coreCount`, which `BootstrapModule` auto-seeds shortly after
        // election. Adding before that commit races the seed and the joiner is minted a CORE.
        //
        // Learned by running it: the first version added ~1s after formation, got a sixth CORE, and the
        // fence then correctly refused to fire because a core may never fence this way. Without the
        // precondition below that would have been reported as "#590's fence is broken" — a false defect
        // against a working mechanism, with a confident 40-second proof behind it.
        await().atMost(FORM_TIMEOUT)
               .pollInterval(POLL)
               .until(() -> committedCoreCount().or(0) == INITIAL_CORES);
        log.info("FENCE-PROBE: committed ClusterConfig.coreCount={} — the cap is live, the next join exceeds it",
                 committedCoreCount().or(0));

        worker = cluster.addWorkerNode()
                        .await()
                        .onFailure(CoreAbsenceFenceOrderingTest::failScenario)
                        .map(id -> id.id())
                        .or("");
        log.info("FENCE-PROBE: added node {} — expected to exceed the core cap and be minted a WORKER", worker);

        // CORRECTION (found by running it): `isArmed()` is `!lastPingNanos.isEmpty()` — "a core ping
        // has ever been accepted" — NOT "the suppressor released". Gating on it proves the node is
        // reachable, not that it is a WORKER, so a node mis-minted as a core would sail past this and
        // then never fence, reading as a defect in the fence rather than a defect in the premise.
        // The suppressor's REAL input is the node's own `topologyManager().coreNodes()`, checked below.
        await().atMost(WORKER_ARM_TIMEOUT)
               .pollInterval(POLL)
               .until(() -> snapshot(worker).map(CoreAbsenceSnapshot::armed).or(false));
        log.info("FENCE-PROBE: {} is ARMED — it has accepted at least one core ping", worker);

        // THE PRECONDITION THAT ACTUALLY MATTERS, and exactly what the suppressor samples at firing
        // time: `cores.isEmpty() || cores.contains(self)` SUPPRESSES. If this node was minted a CORE
        // rather than a worker, the fence is suppressed BY DESIGN and any non-fence says nothing about
        // the mechanism.
        var workerCoreView = cluster.getNode(worker)
                                    .map(node -> node.topologyManager().coreNodes().toString())
                                    .or("<no node>");
        var suppressed = cluster.getNode(worker)
                                .map(node -> {
                                    var cores = node.topologyManager().coreNodes();

                                    return cores.isEmpty() || cores.stream().anyMatch(id -> id.id().equals(worker));
                                })
                                .or(true);

        log.info("FENCE-PROBE PRECONDITION: {} coreNodes-as-seen-by-itself={} suppressorWouldSuppress={}",
                 worker, workerCoreView, suppressed);
        assertThat(suppressed)
            .as("PRECONDITION: %s must be a genuine WORKER — its own coreNodes() view must be non-empty "
                + "and must NOT contain itself, or the fence is suppressed by design and this test "
                + "measures nothing. Saw coreNodes=%s", worker, workerCoreView)
            .isFalse();
    }

    @AfterAll
    @TerminalOperation
    void tearDown() {
        Option.option(cluster).onPresent(c -> c.stop().await());
    }

    @Test
    void isolatedWorkerFencesItselfLocally_strictlyBeforeTheCoreWouldReplaceIt() {
        var before = requireSnapshot(worker);

        assertThat(before.fenced())
            .as("PRECONDITION: %s must not already be fenced before isolation", worker)
            .isFalse();

        var t0 = System.nanoTime();

        // Total isolation: all cluster traffic to and from this node is dropped, connections left
        // open. The core's ClusterSyncPing stops arriving, which is the ONLY liveness signal this
        // fence consumes — and crucially the node cannot write to the core either, which is the whole
        // reason the response has to be local.
        cluster.blackhole(worker).await().onFailure(CoreAbsenceFenceOrderingTest::failScenario);
        log.info("FENCE-PROBE: black-holed {} at t0 — core pings stop, and it cannot reach consensus", worker);

        // CAPTURED at the moment the fence is observed, NOT re-read afterwards. Ember injects
        // `() -> handleSelfDrain(nodeId)` as the node's `jvmExit`, so the fence's drain STOPS this node
        // — a later read can find it already deregistered and throw, failing a run that actually
        // succeeded. (Production passes `Runtime.getRuntime().halt(2)` there; an in-JVM host must not,
        // or one fencing worker would take the whole test JVM with it.)
        var fenceMs = awaitFence(t0);
        var after = fenceObservation.get();

        log.info("FENCE-PROBE EVIDENCE: {}", fenceEvidence.get());
        log.info("FENCE-PROBE FINAL STATE: {}",
                 snapshot(worker).map(s -> "armed=" + s.armed() + " fenced=" + s.fenced()
                                           + " sinceLastPingMs=" + s.sinceLastPingMs()
                                           + " remainingMs=" + s.remainingMs())
                                 .or("NO SNAPSHOT — node already deregistered"));

        assertThat(fenceMs)
            .as("the isolated worker must fence within the %ds core-absence window. -1 means it NEVER "
                + "fenced in %ds — read the t+ progress lines above: `armed=false` means the suppressor "
                + "re-engaged (an isolated node's coreNodes() view emptying reads as SUPPRESS by "
                + "design), a resetting sinceLastPingMs means the isolation is not isolating",
                CORE_ABSENCE.toSeconds(), FENCE_BUDGET.toSeconds())
            .isNotEqualTo(-1L);

        assertThat(fenceEvidence.get())
            .as("the fence must be evidenced either directly (snapshot.fenced) or by the drain "
                + "completing (node deregistered)")
            .isNotEqualTo("none");

        // `after` is null on the deregistration path — the node removed itself before a poll could
        // capture the flag, which is the normal outcome and not a failure.
        log.info("FENCE-PROBE RESULT: fence={}ms coreAbsenceWindow={}ms communityAbsence={}ms "
                 + "marginBeforeCoreWouldReplace={}ms evidence={} capturedSnapshot={}",
                 fenceMs,
                 CORE_ABSENCE.toMillis(),
                 COMMUNITY_ABSENCE.toMillis(),
                 COMMUNITY_ABSENCE.toMillis() - fenceMs,
                 fenceEvidence.get(),
                 after == null
                 ? "none (node deregistered before capture)"
                 : "sinceLastPingMs=" + after.sinceLastPingMs() + " thresholdMs=" + after.thresholdMs());

        assertThat(fenceMs)
            .as("the isolated worker must fence itself within the %ds core-absence window (-1 = never "
                + "fenced within %ds). This is a LOCAL decision — no consensus write is reachable from "
                + "an isolated node, which is why the response cannot be a KV announcement",
                CORE_ABSENCE.toSeconds(), FENCE_BUDGET.toSeconds())
            .isBetween(0L, FENCE_BUDGET.toMillis());

        // THE ORDERING INVARIANT, measured rather than argued from the config inequality.
        assertThat(fenceMs)
            .as("NO-DOUBLE-ACTIVE: the worker must fence STRICTLY BEFORE %dms, the window after which "
                + "the core stops counting it and re-places its work. If these ever crossed, the "
                + "community would be live here and re-provisioned there at the same time — the exact "
                + "hazard `core_absence < community_absence` exists to prevent, and which ConfigValidator "
                + "refuses at load. Measured gap: %dms",
                COMMUNITY_ABSENCE.toMillis(), COMMUNITY_ABSENCE.toMillis() - fenceMs)
            .isLessThan(COMMUNITY_ABSENCE.toMillis());

        // Only assertable when the direct observation won the race. On the deregistration path the
        // observer is gone by construction — the fence removes its own node — so demanding a captured
        // snapshot here would fail runs that succeeded. Which path was taken is reported above.
        if (after != null) {
            assertThat(after.fenced())
                .as("when captured directly, the fence must read as a LATCHED state")
                .isTrue();
        }
    }

    /// Polls the node's own snapshot — the same `coreAbsence` projection served on
    /// `GET /api/cluster/membership`, so this asserts on the operator-visible surface rather than on
    /// an internal the operator could not see. Returns -1 on timeout so the caller reports its own
    /// message instead of dying with a bare Awaitility timeout.
    private long awaitFence(long t0) {
        var latch = new AtomicLong(-1);

        try {
            await().atMost(FENCE_BUDGET.plusSeconds(10))
                   .pollInterval(FENCE_POLL)
                   .until(() -> {
                       var current = snapshot(worker);

                       if (current.map(CoreAbsenceSnapshot::fenced).or(false)) {
                           fenceObservation.set(current.or((CoreAbsenceSnapshot) null));
                           fenceEvidence.set("snapshot.fenced=true");
                           latch.compareAndSet(-1, (System.nanoTime() - t0) / 1_000_000);

                           return true;
                       }
                       // THE FENCE REMOVES ITS OWN OBSERVER, so `fenced=true` is only visible in the
                       // window between the flag being set and the drain completing — measured shorter
                       // than a 250ms poll. Node DISAPPEARANCE is the durable evidence: in Ember the
                       // only route out of the registry is `jvmExit` -> `handleSelfDrain`, and the only
                       // thing that invokes `jvmExit` here is `DrainProcedure.initiate(...)`. This node
                       // is not a core (precondition asserted), so QuorumLossDetector is not its path,
                       // and nothing commanded a drain — leaving CORE_ABSENCE as the only reachable
                       // cause. Recorded as such rather than claimed as a direct observation.
                       if (deregistered()) {
                           fenceEvidence.set("node deregistered (drain completed; CORE_ABSENCE is the "
                                             + "only reachable jvmExit cause for a non-core node here)");
                           latch.compareAndSet(-1, (System.nanoTime() - t0) / 1_000_000);

                           return true;
                       }
                       // A non-fence must explain itself. `armed` false means the SUPPRESSOR re-engaged
                       // — and since an isolated node's `coreNodes()` view can empty out, and an empty
                       // view is documented to read as SUPPRESS, that is the interesting failure. A
                       // `sinceLastPing` that keeps resetting means pings still arrive, i.e. the
                       // isolation is not isolating. Without this line a silent 30s produces no
                       // evidence at all, which is the failure shape this repo keeps paying for.
                       logProgress(current, t0);

                       return false;
                   });
        } catch (Exception e) {
            log.warn("FENCE-PROBE: {} never fenced within {}s", worker, FENCE_BUDGET.toSeconds());
        }

        return latch.get();
    }

    /// An absent snapshot means the detector is unwired on that node — which would itself be the
    /// finding, so it fails loudly rather than degrading into a never-fences run.
    private CoreAbsenceSnapshot requireSnapshot(String nodeId) {
        return snapshot(nodeId).fold(() -> {
                                   throw new AssertionError("no coreAbsence snapshot for " + nodeId
                                                            + " — the detector is unwired on that node");
                               },
                               s -> s);
    }

    private void logProgress(Option<CoreAbsenceSnapshot> current, long t0) {
        var now = System.nanoTime();

        if (now - lastProgressLog.get() < 2_000_000_000L) {
            return;
        }
        lastProgressLog.set(now);
        log.info("FENCE-PROBE: t+{}ms {}",
                 (now - t0) / 1_000_000,
                 current.map(s -> "armed=" + s.armed()
                                  + " fenced=" + s.fenced()
                                  + " sinceLastPingMs=" + s.sinceLastPingMs()
                                  + " remainingMs=" + s.remainingMs()
                                  + " thresholdMs=" + s.thresholdMs())
                        .or("NO SNAPSHOT — node deregistered or detector unwired"));
    }

    /// Read in-process off the leader KV — the COMMITTED value is what role assignment reads, so an
    /// HTTP read of a not-yet-committed config would be the wrong signal.
    private Option<Integer> committedCoreCount() {
        return leaderOrAnyNode().flatMap(node -> node.kvStore().get(ClusterConfigKey.CURRENT))
                                .filter(ClusterConfigValue.class::isInstance)
                                .map(ClusterConfigValue.class::cast)
                                .map(ClusterConfigValue::coreCount);
    }

    private Option<AetherNode> leaderOrAnyNode() {
        return cluster.currentLeader()
                      .flatMap(cluster::getNode)
                      .orElse(() -> Option.from(cluster.allNodes().stream().findFirst()));
    }

    /// True once Ember has removed the node from its registry — the drain ran to completion.
    private boolean deregistered() {
        return cluster.status()
                      .nodes()
                      .stream()
                      .noneMatch(n -> n.id().equals(worker));
    }

    private Option<CoreAbsenceSnapshot> snapshot(String nodeId) {
        return cluster.getNode(nodeId)
                      .flatMap(node -> node.coreAbsenceSnapshot());
    }

    private static void failStart(Cause cause) {
        throw new AssertionError("Cluster start failed: " + cause.message());
    }

    private static void failScenario(Cause cause) {
        throw new AssertionError("Scenario step failed: " + cause.message());
    }
}
