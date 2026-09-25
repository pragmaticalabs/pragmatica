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
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.aether.worker.isolation.CoreAbsenceSnapshot;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.TerminalOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// Total-isolation regression for a worker's local core-absence fence.
///
/// A worker cut off from every core stops serving after its configured absence window without
/// requiring a successful consensus write. This test observes the local fence and its timing
/// relative to the longer community-observation window. The timing margin is operational evidence,
/// not an exclusive-ownership or no-double-active proof: asymmetric delivery, pauses and delayed
/// effects require committed governor terms and effect fencing.
///
/// This test uses a total per-node blackhole. It does not exercise a governor that loses its report
/// uplink while retaining community connectivity. Report expiry makes that community unreachable;
/// it does not establish individual worker death or authorize termination.
///
/// Historical measurements (2026-08-27, six runs, 8-core/16GB host, five cores and one worker):
/// local fence elapsed times were 9673, 9696, 9676, 9704, 9692 and 9697 milliseconds against a
/// 10000 millisecond configured window. The corresponding margins to the historical 20000
/// millisecond community window were 10327, 10304, 10324, 10296, 10308 and 10303 milliseconds.
/// These measurements describe that implementation and environment, not the current hierarchy's
/// authority guarantee or scalability limit.
///
/// Earlier harness runs omitted role labels and treated blank roles as core. That historical
/// setup suppressed the worker fence and motivated explicit worker admission. Roles are now
/// immutable: addWorkerNode supplies worker intent, and neither a core deficit nor a community
/// assignment can promote a worker into the core electorate. Unknown roles are not core authority.
///
/// Core nodes use quorum-loss detection. Worker core-absence detection consumes observations from
/// the bounded core uplink policy; ping/pong is request-response and is not restricted to a leader
/// broadcast. Community liveness on core nodes comes from fresh term-fenced governor reports.
/// Five cores and raised SWIM timeouts retain this test's historical load conditions; they are not
/// a claim that three-core worker admission is unsupported.
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
    /// 10s to fence locally, 20s community-observation timeout. The configured inequality provides
    /// an operational margin; committed authority and effect fencing provide ownership safety.
    ///
    /// **These survive `withRaisedSwimTimeouts()` — verified, not assumed.** That seam swaps the whole
    /// `TimeoutsConfig`, so it could silently move the windows this class measures against. It does
    /// not: `EmberCluster.raisedSwimTimeoutsConfig()` passes `defaults.cluster()` through untouched and
    /// raises only the SWIM suspect timeout (60s), the Hello timeout and the membership split. Note the
    /// consequence — SWIM will not notice the isolated worker for 60s, which is FINE here because the
    /// current core-side community health expires independently of SWIM peer suspicion.
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
        LifecycleAwait.settled("cluster start in setUp()", cluster, cluster.start());

        await().atMost(FORM_TIMEOUT).pollInterval(POLL).until(() -> cluster.currentLeader().isPresent());
        await().atMost(FORM_TIMEOUT).pollInterval(POLL).until(() -> cluster.status().nodes().size() == INITIAL_CORES);
        log.info("FENCE-PROBE: {}-core cluster formed, leader={}", INITIAL_CORES, cluster.currentLeader().or("none"));

        // Wait for bootstrap configuration materialization before testing the worker path.
        // This is a fixture readiness condition; configured worker identity cannot become core.
        await().atMost(FORM_TIMEOUT)
               .pollInterval(POLL)
               .until(() -> committedCoreCount().or(0) == INITIAL_CORES);
        log.info("FENCE-PROBE: committed ClusterConfig.coreCount={} — bootstrap configuration is committed",
                 committedCoreCount().or(0));

        worker = LifecycleAwait.nodeSettled("worker node join in addWorkerBeyondTheCoreCap()",
                                            cluster,
                                            cluster.addWorkerNode())
                               .id();
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
        Option.option(cluster).onPresent(c -> LifecycleAwait.bestEffort("cluster stop in tearDown()", c, c.stop()));
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
        LifecycleAwait.nodeSettled("blackhole node " + worker
                                  + " in isolatedWorkerFencesItselfLocally_strictlyBeforeTheCoreWouldReplaceIt()",
                                   cluster,
                                   cluster.blackhole(worker));
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
    /// `GET /api/v1/cluster/membership`, so this asserts on the operator-visible surface rather than on
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
}
