// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.pragmatica.aether.deployment.membership.MembershipConfig;
import org.pragmatica.aether.deployment.membership.ntt.LeaderReconciler.ProvisioningDecisionSnapshot;
import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.ProviderDefaults;
import org.pragmatica.aether.environment.ProvisionRequest;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.node.ProvisioningDiagnostics;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.TerminalOperation;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.parse.Number;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.pragmatica.http.JdkHttpOperations.jdkHttpOperations;
import static org.pragmatica.lang.Option.option;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;


/// Assembly-level probe (GH #509) — after a full-cluster restart in which some CONFIGURED
/// stable-id core members are merely SLOW to rejoin, does the `LeaderReconciler` deficit-fill and
/// provision EMPTY replacement nodes for them (`provisionReplacement failedPeer=None()`), or does it
/// correctly wait?
///
/// ## Why Ember / Forge answers this question
/// #509's mechanism analysis (2026-08-03) says the fill cannot happen on current HEAD because
/// `MembershipFsm.seed` promotes the whole CONFIGURED core set to MEMBER at node-wiring time, so the
/// leader's `coreCountedMembers()` already shows the full count while the laggards are still absent
/// and the reconciler sees NO deficit. Every piece of that claim is assembly-level and lives inside a
/// single JVM — the config-topology seed at `AetherNode` wiring, the leader's `MembershipFsm`, the
/// reconciler's deficit math, and the CTM → `ComputeProvider` provisioning edge. Ember exercises all
/// four faithfully with no DNS, no advertise-host and no container boot, and — unlike a real restart
/// — lets the "slow to rejoin" condition be created DETERMINISTICALLY rather than raced. The unit
/// probes in `LeaderReconcilerTest.PostRestartSlowRejoin` pin the same invariant against a stubbed
/// CTM; this probe is the owed evidence that the assembled system behaves the same way.
///
/// ## Producing the condition deterministically (authorized test seam)
/// `EmberCluster.start(Set<String> heldBackNodeIds)` (new seam) CREATES every configured node with
/// the complete `initialNodes` topology list — so all 5 ids are in every started node's configured
/// core set and therefore in its `MembershipFsm` config seed — but DEFERS `start()` for the held
/// subset. `startHeldBackNodes()` later brings them up on their original identities, ports and slots.
/// That is precisely "configured stable-id members that have not come back yet", with no timing race.
///
/// ## The recorder is a pure observer
/// A [ComputeProvider] WRAPPER installed via `EmberCluster.withComputeProviderDecorator` BEFORE
/// `start()` counts and timestamps every `createFrom` — the one call the CTM `provisionReplacement`
/// path reaches the provider through — and ALWAYS delegates. NO fault injection: the question is
/// whether a provision happens at all, so the recorder must never alter the outcome. An injected
/// failure would suppress the resulting join and could disguise a fired deficit-fill as "no effect".
///
/// ## The hold window is derived, not guessed
/// See [#HOLD_WINDOW]: it is computed from the reconciler's OWN timing constants so the observed zero
/// cannot be explained by "the reconciler had not gotten around to it yet".
///
/// ## What the assertions ride on (no log-scraping)
/// Provision calls come from the recorder. Membership comes from
/// `AetherNode.membershipFsm().coreCountedMembers()` — the exact set the reconciler does its deficit
/// math on. The reconciler's own end-of-pass decision, INCLUDING its precise suppression `reason`
/// token, is read from the first-class `AetherNode.provisioningDiagnostics()` management surface.
/// The reason token is recorded and reported as DIAGNOSTICS, not asserted: two independent gates can
/// each suppress the fill here — `NO_DEFICIT` (the #509 mechanism: the config seed keeps
/// `coreCountedMembers()` at full count) and `COLD_START_NOT_FULL` (the `reachedFullMembership`
/// latch, un-latched because a restarted Ember cluster elects on term 1 and the sampler peak cannot
/// reach 5 while 2 nodes are held). `suppressionReason` evaluates `NO_DEFICIT` FIRST, so the tokens
/// in the timeline tell the ticket WHICH gate was load-bearing, while the assertion rides only on the
/// invariant #509 actually claims.
///
/// PASS  = zero provisions across the hold and the rejoin, the same 5 stable ids come back, AND the
///         positive control fills a genuine deficit (so the zero is not vacuous).
/// FAIL  = any provision during the hold — #509 reproducing at assembly level (the failure names the
///         requested node ids and when each was requested).
@Tag("Heavy")
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PostRestartSlowRejoinDeficitFillProbeTest {
    private static final Logger log = LoggerFactory.getLogger(PostRestartSlowRejoinDeficitFillProbeTest.class);
    private static final int INITIAL_CORES = 5;
    /// The positive control raises the configured core count by exactly one, so a single genuine
    /// deficit-fill is the only way to converge — the smallest change that proves the path is live.
    private static final int RAISED_CORES = INITIAL_CORES + 1;
    private static final String NODE_PREFIX = "slowjoin";
    /// The two configured members held back across the restart. Ember names initial nodes
    /// `<prefix>-1..<prefix>-N` deterministically and regenerates the SAME ids after `stop()`, so
    /// these are stable across the restart. Holding 2 of 5 leaves 3 — still a Rabia quorum, so the
    /// survivors can elect and the reconciler can actually run (a sub-quorum hold would suppress
    /// every pass as `NOT_QUORUM_SAFE` and make the observed zero vacuous).
    private static final Set<String> HELD_BACK = Set.of(NODE_PREFIX + "-4", NODE_PREFIX + "-5");
    private static final int BASE_PORT = 19500;
    private static final int BASE_MGMT_PORT = 19600;
    private static final int BASE_APP_HTTP_PORT = 19700;

    // ----- reconciler restraints the hold window must provably outlast -----
    // Ember nodes are created with an empty membership config, so every node falls back to
    // MembershipConfig.membershipConfig() (AetherNode: `config.membership().or(MembershipConfig::membershipConfig)`).
    // T is that single membership timing constant; every reconciler window below derives from it.
    private static final Duration SPLIT_TIMEOUT = Duration.ofMillis(MembershipConfig.DEFAULT_SPLIT_TIMEOUT.millis());

    /// `LeaderReconciler.leaderActivationDelay` = `computeQuiesceDelay(splitTimeout)` = `T × 3/2`.
    /// Nothing reconciles at all until this one-shot delay after the leader edge fires; the same value
    /// is reused as `provisioningGraceWindow`.
    private static final Duration LEADER_ACTIVATION_DELAY = SPLIT_TIMEOUT.multipliedBy(3).dividedBy(2);
    /// `LeaderReconciler.deficitDebounceWindow` = `splitTimeout` (`T × 1`). A deficit run must age
    /// past this before a fill is permitted.
    private static final Duration DEFICIT_DEBOUNCE_WINDOW = SPLIT_TIMEOUT;
    /// Mirrors the private `LeaderReconciler.DEBOUNCE_DELAY` floor (100ms) that every follow-up delay
    /// adds on top of the debounce window. Not referenceable by name — it is private to the
    /// reconciler — so it is named here and kept as a margin, never as a threshold.
    private static final Duration RECONCILER_DEBOUNCE_FLOOR = Duration.ofMillis(100);

    /// `LeaderReconciler.deficitFollowUpDelay` = `deficitDebounceWindow + DEBOUNCE_DELAY` once the
    /// window has elapsed — the spacing of the self-rearming `DEFICIT_FOLLOW_UP` convergence loop that
    /// re-fires off the RAW confirmed-member deficit.
    private static final Duration DEFICIT_FOLLOW_UP_SPACING = DEFICIT_DEBOUNCE_WINDOW.plus(RECONCILER_DEBOUNCE_FLOOR);

    /// Three `DEFICIT_FOLLOW_UP` passes past the debounce. One would be enough to permit a fill; three
    /// makes "the loop simply had not re-fired yet" untenable as an explanation for the zero.
    private static final int FOLLOW_UP_PASSES_HELD_THROUGH = 3;

    /// Hold window = leader-activation quiesce + deficit debounce + 3 × `DEFICIT_FOLLOW_UP` spacing
    /// = `T×1.5 + T×1 + 3×(T + 100ms)` = 82.8s at the 15s default `T`. Every reconciler restraint that
    /// could independently explain a zero has elapsed by the end of it, so the zero means the
    /// reconciler DECIDED not to fill, not that it had not yet looked.
    private static final Duration HOLD_WINDOW = LEADER_ACTIVATION_DELAY.plus(DEFICIT_DEBOUNCE_WINDOW).plus(DEFICIT_FOLLOW_UP_SPACING.multipliedBy(FOLLOW_UP_PASSES_HELD_THROUGH));

    private static final Duration FORM_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration REJOIN_TIMEOUT = Duration.ofSeconds(90);

    /// Positive-control budget: the debounce window must elapse before the first fill is permitted,
    /// then the provisioned node must boot and be counted. Four `DEFICIT_FOLLOW_UP` passes plus a 60s
    /// in-JVM boot/join margin on top of the debounce.
    private static final Duration FILL_BUDGET = DEFICIT_DEBOUNCE_WINDOW.plus(DEFICIT_FOLLOW_UP_SPACING.multipliedBy(4)).plusSeconds(60);

    private static final Duration POLL = Duration.ofMillis(500);
    private static final Duration LOG_EVERY = Duration.ofSeconds(10);
    private static final Pattern CONFIG_VERSION = Pattern.compile("\"configVersion\"\\s*:\\s*(\\d+)");

    private EmberCluster cluster;
    private final ProvisionRecorder recorder = new ProvisionRecorder();
    private final HttpOperations http = jdkHttpOperations();
    private final List<String> milestones = new ArrayList<>();
    /// Ids this probe has deliberately STARTED and therefore expects to stay alive. Held-back ids are
    /// absent until [#releaseHeldBackMembers] widens the set.
    ///
    /// `volatile` because of a genuine cross-thread handoff: these three are WRITTEN on the test thread
    /// at phase transitions and READ on Awaitility's poller thread on every tick. Without safe
    /// publication the guard could read a stale set and either miss a death or fail against ids from
    /// the previous phase. The set itself is immutable (`Set.copyOf`), so publishing the reference
    /// safely is sufficient — no lock is needed.
    private volatile Set<String> startedNodeIds = Set.of();
    /// Label + origin of the current phase, so a liveness failure can say WHEN the death was first seen
    /// rather than only that it happened. Same publication constraint as [#startedNodeIds].
    private volatile String startedPhase = "PRE-START";
    private volatile long startedPhaseNanos = System.nanoTime();

    @BeforeAll
    @TerminalOperation
    void setUp() {
        cluster = emberCluster(INITIAL_CORES, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, NODE_PREFIX);
        cluster.withComputeProviderDecorator(recorder::wrap);
        cluster.start().await().onFailure(PostRestartSlowRejoinDeficitFillProbeTest::failStart);
        expectStarted(allConfiguredIds(), "FORMATION-1 start");
        await().atMost(FORM_TIMEOUT).pollInterval(POLL).failFast(this::failIfClusterUnhealthy).until(() -> cluster.currentLeader()
                                                                           .isPresent());
        await().atMost(FORM_TIMEOUT).pollInterval(POLL).failFast(this::failIfClusterUnhealthy).until(() -> countedCores() == INITIAL_CORES);
        // Formation 1 must reach FULL observed membership before the restart: the sampler PEAK is what
        // the reconciler's `reachedFullMembership` cold-start latch reads, and gating here means the
        // pre-restart cluster is genuinely formed rather than merely quorate.
        await().atMost(FORM_TIMEOUT).pollInterval(POLL).failFast(this::failIfClusterUnhealthy).until(() -> observedPeak() >= INITIAL_CORES);
        recordMilestone("FORMATION-1 complete: leader=" + cluster.currentLeader().or("none")
                       + " countedCores=" + countedCores()
                       + " observedPeak=" + observedPeak());
        log.info("SLOWJOIN-PROBE: {}-core cluster formed, leader={}, countedCores={} observedPeak={} "
                + "(full membership observed)",
                 INITIAL_CORES,
                 cluster.currentLeader().or("none"),
                 countedCores(),
                 observedPeak());
    }

    @AfterAll
    @TerminalOperation
    void tearDown() {
        option(cluster).onPresent(c -> c.stop()
                                        .await());
    }

    /// THE #509 INVARIANT. Restart the cluster with 2 configured members held back, hold past every
    /// reconciler restraint, and require that NOTHING was provisioned — then release the held members
    /// and require that the SAME 5 stable ids came back with still nothing provisioned.
    @Test
    @Order(0)
    @TerminalOperation
    void reconcile_configuredMembersSlowToRejoin_provisionsNoReplacement() {
        restartWithHeldBackMembers();
        var t0 = System.nanoTime();
        var hold = new HoldObservation();

        observeThroughHoldWindow(t0, hold);
        recordMilestone("HOLD complete after " + HOLD_WINDOW.toSeconds()
                       + "s: countedCores=" + countedCores()
                       + " aliveNodes=" + cluster.nodeCount()
                       + " reasons=" + hold.reasons
                       + " provisionCalls=" + recorder.provisionCalls());
        // Dumped BEFORE the assertions: a failing assertion is exactly when this timeline is the
        // evidence the ticket needs, and an assertion error would skip anything logged after it.
        dumpTimeline("SLOW-REJOIN HOLD", hold.timeline);
        dumpProvisionLedger();
        assertThat(hold.leaderTicks).as("PRECONDITION-1: a leader must be present among the %d started members for the whole "
                                       + "hold, otherwise no reconcile pass ever runs and a zero-provision observation says "
                                       + "nothing. Ticks with a leader=%d of %d.",
                                        INITIAL_CORES - HELD_BACK.size(),
                                        hold.leaderTicks,
                                        hold.totalTicks)
                  .isGreaterThan(0);
        assertThat(hold.diagnosticTicks).as("PRECONDITION-2: the leader's provisioningDiagnostics() must be readable during the "
                                           + "hold — that surface is populated by the reconciler's end-of-pass capture, so a "
                                           + "reading proves passes actually ran on THIS cluster. Ticks with diagnostics=%d of "
                                           + "%d. Suppression reasons observed: %s.",
                                            hold.diagnosticTicks,
                                            hold.totalTicks,
                                            hold.reasons)
                  .isGreaterThan(0);
        assertThat(recorder.provisionCalls()).as("#509 INVARIANT: configured stable-id members that are merely SLOW to rejoin must "
                                                + "NEVER be replaced by provisioned empty nodes. Held back %s across a full-cluster "
                                                + "restart and waited %ds — past the leader-activation quiesce (%ds), the deficit "
                                                + "debounce (%ds) and %d DEFICIT_FOLLOW_UP passes (%dms apart) — so every restraint "
                                                + "that could independently explain a zero has elapsed. Provisions recorded: %s. "
                                                + "Reconciler suppression reasons seen: %s. NON-ZERO = #509 reproducing at assembly "
                                                + "level.",
                                                 HELD_BACK,
                                                 HOLD_WINDOW.toSeconds(),
                                                 LEADER_ACTIVATION_DELAY.toSeconds(),
                                                 DEFICIT_DEBOUNCE_WINDOW.toSeconds(),
                                                 FOLLOW_UP_PASSES_HELD_THROUGH,
                                                 DEFICIT_FOLLOW_UP_SPACING.toMillis(),
                                                 recorder.render(),
                                                 hold.reasons)
                  .isZero();
        releaseHeldBackMembers();
        recordMilestone("REJOIN complete: countedCores=" + countedCores()
                       + " ids=" + countedCoreIds()
                       + " leader=" + cluster.currentLeader().or("none")
                       + " observedPeak=" + observedPeak());
        dumpProvisionLedger();
        assertThat(countedCoreIds()).as("REJOIN: the released members must come back under their ORIGINAL stable ids, so the "
                                       + "counted core set is exactly the %d configured ids. A provisioned replacement would "
                                       + "appear here as `%s-6`, which is the shape #509 reports.",
                                        INITIAL_CORES,
                                        NODE_PREFIX)
                  .isEqualTo(configuredCoreIds());
        assertThat(cluster.currentLeader().isPresent()).as("REJOIN: a leader must still be present once all %d configured members are back.",
                                                           INITIAL_CORES)
                  .isTrue();
        assertThat(recorder.provisionCalls()).as("#509 INVARIANT (post-rejoin): the rejoin itself must not have triggered a fill "
                                                + "either. Provisions recorded: %s.",
                                                 recorder.render())
                  .isZero();
        recordMilestone("SLOW-REJOIN probe complete with zero provisions across hold and rejoin");
    }

    /// POSITIVE CONTROL — guards the zero above against vacuity. A GENUINE deficit (configured core
    /// count raised 5→6 through the real `ClusterConfigKey` path) must be filled, proving in this
    /// exact cluster that (a) the reconciler's deficit→provision path is live, (b) the recorder is
    /// wired to the call the path travels, and (c) the `reachedFullMembership` latch opened once the
    /// held members rejoined — none of which the zero-observation alone can establish.
    ///
    /// The trigger is `POST /api/cluster/scale`, NOT `EmberCluster.setClusterSize`: the latter routes
    /// a `SetClusterSize` topology message that moves only the consensus-side `effectiveClusterSize`
    /// atomic and never writes `ClusterConfigKey.CURRENT`, so `configuredCoreCountSupplier` would
    /// never see 6 and the control would silently no-op into a false pass. Same resolved caveat as
    /// `ScaleUpFiveToSevenProbeTest` and `ArtifactChurnSurvival5to7to5ProbeTest`.
    @Test
    @Order(1)
    @TerminalOperation
    void reconcile_configuredCoreCountRaised_provisionsReplacement() {
        await().atMost(REJOIN_TIMEOUT).pollInterval(POLL).failFast(this::failIfStartedNodeDied).until(() -> observedPeak() >= INITIAL_CORES);
        recordMilestone("CONTROL armed: observedPeak=" + observedPeak() + " (reachedFullMembership latch can now open)");
        var leaderPort = cluster.getLeaderManagementPort()
                                .toResult(ProbeError.NO_LEADER)
                                .onFailure(PostRestartSlowRejoinDeficitFillProbeTest::failScenario)
                                .or(-1);

        await().atMost(FORM_TIMEOUT).pollInterval(POLL).failFast(this::failIfStartedNodeDied).until(() -> readConfigVersion(leaderPort) >= 1);
        var version = readConfigVersion(leaderPort);
        var response = postScale(leaderPort, RAISED_CORES, version);

        log.info("SLOWJOIN-PROBE CONTROL: POST /api/cluster/scale {{coreCount:{}, expectedVersion:{}}} -> {}",
                 RAISED_CORES,
                 version,
                 response);
        recordMilestone("CONTROL scale posted: coreCount=" + RAISED_CORES + " expectedVersion=" + version);
        var t0 = System.nanoTime();
        var fill = new HoldObservation();

        observeUntilFilledOrBudget(t0, fill);
        recordMilestone("CONTROL complete: countedCores=" + countedCores()
                       + " ids=" + countedCoreIds()
                       + " provisionCalls=" + recorder.provisionCalls());
        dumpTimeline("DEFICIT-FILL CONTROL", fill.timeline);
        dumpProvisionLedger();
        assertThat(recorder.provisionCalls()).as("CONTROL-1: raising the configured core count %d→%d must reach the provider through "
                                                + "the CTM provisionReplacement path within %ds. ZERO here means the deficit-fill "
                                                + "path or the recorder is INERT in this cluster — which would make the zero asserted "
                                                + "by the slow-rejoin test vacuous rather than meaningful. Reconciler suppression "
                                                + "reasons seen: %s.",
                                                 INITIAL_CORES,
                                                 RAISED_CORES,
                                                 FILL_BUDGET.toSeconds(),
                                                 fill.reasons)
                  .isGreaterThan(0);
        assertThat(countedCores()).as("CONTROL-2: the provisioned core must actually JOIN and be counted, taking the "
                                     + "counted-core denominator to %d within %ds. Counted ids=%s, provisions=%s. A "
                                     + "provision that never joins would leave the control half-proven.",
                                      RAISED_CORES,
                                      FILL_BUDGET.toSeconds(),
                                      countedCoreIds(),
                                      recorder.render())
                  .isEqualTo(RAISED_CORES);
    }

    // ----- restart mechanics -----
    /// Full-cluster restart with [#HELD_BACK] deferred. Modelled on
    /// `MultiPartitionCrashDurabilityTest.restartCluster()`, minus the stream/slice/data-dir concerns
    /// this probe has none of: membership, not durability, is the question here.
    @TerminalOperation
    private void restartWithHeldBackMembers() {
        cluster.stop().await().onFailure(PostRestartSlowRejoinDeficitFillProbeTest::failScenario);
        recordMilestone("RESTART: cluster stopped");
        // Nothing is expected alive between stop() and the restart completing.
        expectStarted(Set.of(), "RESTART stop");
        cluster.start(HELD_BACK).await().onFailure(PostRestartSlowRejoinDeficitFillProbeTest::failStart);
        expectStarted(startedAfterHoldBack(), "RESTART start (held back " + HELD_BACK + ")");
        recordMilestone("RESTART: started " + (INITIAL_CORES - HELD_BACK.size())
                       + " of " + INITIAL_CORES
                       + " members, held back " + HELD_BACK);
        await().atMost(FORM_TIMEOUT)
             .pollInterval(POLL)
             .failFast(this::failIfClusterUnhealthy)
             .until(() -> cluster.currentLeader()
                                 .isPresent());
        recordMilestone("RESTART: leader elected among the started subset: " + cluster.currentLeader().or("none"));
        log.info("SLOWJOIN-PROBE: restarted with {} members up, {} held back, leader={}, countedCores={}",
                 cluster.nodeCount(),
                 HELD_BACK.size(),
                 cluster.currentLeader().or("none"),
                 countedCores());
    }

    @TerminalOperation
    private void releaseHeldBackMembers() {
        log.info("SLOWJOIN-PROBE: releasing held-back members {}", HELD_BACK);
        cluster.startHeldBackNodes().await().onFailure(PostRestartSlowRejoinDeficitFillProbeTest::failScenario);
        expectStarted(allConfiguredIds(), "REJOIN (held-back released)");
        await().atMost(REJOIN_TIMEOUT).pollInterval(POLL).failFast(this::failIfClusterUnhealthy).until(() -> countedCores() >= INITIAL_CORES);
        await().atMost(REJOIN_TIMEOUT).pollInterval(POLL).failFast(this::failIfClusterUnhealthy).until(() -> cluster.currentLeader()
                                                                             .isPresent());
    }

    // ----- observation -----
    /// Mutable, thread-confined (single awaitility poller thread) observation state. `reasons` keeps
    /// insertion order so the timeline reads as the sequence of gates the reconciler reported.
    private static final class HoldObservation {
        private final List<String> timeline = new ArrayList<>();
        private final Set<String> reasons = new LinkedHashSet<>();
        private int totalTicks = 0;
        private int leaderTicks = 0;
        private int diagnosticTicks = 0;
        private long lastLogMs = -LOG_EVERY.toMillis();
    }

    /// Polls for the whole [#HOLD_WINDOW], failing IMMEDIATELY (with the requested node ids and their
    /// timestamps) the moment a provision is recorded — a fired deficit-fill is the defect, and
    /// waiting out the remaining window would only blur when it happened.
    private void observeThroughHoldWindow(long t0, HoldObservation hold) {
        await().pollInterval(POLL)
             .pollDelay(Duration.ZERO)
             .timeout(HOLD_WINDOW.plusSeconds(10))
             .failFast(this::failIfClusterUnhealthy)
             .until(() -> recordHoldTick(t0, hold));
    }

    private boolean recordHoldTick(long t0, HoldObservation hold) {
        var elapsed = elapsedMs(t0);

        observe(elapsed, hold, "HOLD");

        return elapsed >= HOLD_WINDOW.toMillis();
    }

    /// The control EXPECTS a provision, so the provisioned-node fail-fast must not be installed here —
    /// only the liveness half, which stays valid: a started member dying still invalidates the run.
    /// The provisioned replacement itself joins as a NEW id and is never in [#startedNodeIds].
    private void observeUntilFilledOrBudget(long t0, HoldObservation fill) {
        await().pollInterval(POLL)
             .pollDelay(Duration.ZERO)
             .timeout(FILL_BUDGET.plusSeconds(10))
             .failFast(this::failIfStartedNodeDied)
             .until(() -> recordFillTick(t0, fill));
    }

    private boolean recordFillTick(long t0, HoldObservation fill) {
        var elapsed = elapsedMs(t0);

        observe(elapsed, fill, "FILL");

        return countedCores() >= RAISED_CORES || elapsed >= FILL_BUDGET.toMillis();
    }

    private void observe(long elapsedMs, HoldObservation observation, String phase) {
        var diagnostics = provisioningDiagnostics();

        observation.totalTicks++;
        observation.leaderTicks += cluster.currentLeader().isPresent()
                                   ? 1
                                   : 0;
        observation.diagnosticTicks += diagnostics.isPresent()
                                       ? 1
                                       : 0;
        diagnostics.onPresent(d -> observation.reasons.add(d.decision().reason()));
        maybeRecord(elapsedMs, observation, phase, diagnostics);
    }

    private void maybeRecord(long elapsedMs,
                             HoldObservation observation,
                             String phase,
                             Option<ProvisioningDiagnostics> diagnostics) {
        if (elapsedMs - observation.lastLogMs < LOG_EVERY.toMillis()) {
            return;
        }

        observation.lastLogMs = elapsedMs;
        observation.timeline.add(formatTick(elapsedMs, phase, diagnostics));
    }

    /// Per-tick timeline row. `decision[...]` is the reconciler's OWN end-of-pass snapshot read from
    /// the management surface — the real `reason` token, not a stand-in derived by this test.
    private String formatTick(long elapsedMs, String phase, Option<ProvisioningDiagnostics> diagnostics) {
        return "t+" + elapsedMs
             + "ms " + phase
             + " countedCores=" + countedCores()
             + " countedIds=" + countedCoreIds()
             + " aliveNodes=" + cluster.nodeCount()
             + " observedPeak=" + observedPeak()
             + " leader=" + cluster.currentLeader()
                                   .or("none")
             + " provisionCalls=" + recorder.provisionCalls()
             + " decision[" + diagnostics.map(PostRestartSlowRejoinDeficitFillProbeTest::formatDecision)
                                         .or("not-leader-or-unavailable")
             + "]";
    }

    private static String formatDecision(ProvisioningDiagnostics diagnostics) {
        return formatSnapshot(diagnostics.decision()) + " breakerTripped=" + diagnostics.circuitBreaker()
                                                                                        .tripped();
    }

    private static String formatSnapshot(ProvisioningDecisionSnapshot decision) {
        return "trigger=" + decision.trigger()
             + " configured=" + decision.configuredCoreCount()
             + " counted=" + decision.countedCoreMembers()
             + " effective=" + decision.effective()
             + " armed=" + decision.armedForProvisioning()
             + " reachedFull=" + decision.reachedFullMembership()
             + " quorumSafe=" + decision.quorumSafe()
             + " deficitAgeMs=" + decision.deficitAgeMs()
             + " reason=" + decision.reason();
    }

    /// Awaitility fail-fast assertion — a provision during the hold IS the #509 defect, so it aborts
    /// the wait immediately and names which node ids were requested and when.
    private void failIfProvisioned() {
        if (recorder.provisionCalls() == 0) {
            return;
        }

        throw new AssertionError("#509 REPRODUCED: the reconciler provisioned replacement(s) for "
                                + "configured members that are merely slow to rejoin (held back: " + HELD_BACK
                                + "). Provisions: " + recorder.render()
                                + " | countedCores=" + countedCores()
                                + " countedIds=" + countedCoreIds()
                                + " aliveNodes=" + cluster.nodeCount()
                                + " decision[" + provisioningDiagnostics().map(PostRestartSlowRejoinDeficitFillProbeTest::formatDecision)
                                                                        .or("unavailable")
                                + "]");
    }

    /// The single fail-fast every await in this probe installs. Awaitility keeps only ONE `failFast`
    /// per await, so the two independent abort conditions are combined here rather than chained.
    /// Liveness is checked FIRST: a dead started node invalidates every other observation the probe
    /// could make, including a provision count, so it must be what the failure reports.
    private void failIfClusterUnhealthy() {
        failIfStartedNodeDied();
        failIfProvisioned();
    }

    /// Fail-fast liveness guard (#642). `EmberCluster.getNode` resolves against the RUNNING-node
    /// registry, and a self-drain lands as `handleSelfDrain` → registry REMOVE + `node.stop()`. So a
    /// started id that no longer resolves is a node that died underneath this probe.
    ///
    /// This exists because without it the probe waits out its full budget against an already-dead
    /// cluster: the 2026-08-26 run hung for 45 minutes and produced no diagnosis at all (the ghost
    /// `QuorumLossDetector`s of #642 had murdered two of the three started members). Naming the node
    /// and the moment it was first seen missing turns that into a seconds-red with a cause.
    ///
    /// Held-back nodes are excluded by construction — they are simply not in [#startedNodeIds] until
    /// [#releaseHeldBackMembers] adds them.
    private void failIfStartedNodeDied() {
        var dead = startedNodeIds.stream()
                                 .filter(id -> cluster.getNode(id).isEmpty())
                                 .sorted()
                                 .collect(Collectors.joining(","));

        if (dead.isEmpty()) {
            return;
        }

        throw new AssertionError("STARTED NODE DIED under the probe: " + dead
                                + " — first observed missing t+" + elapsedMs(startedPhaseNanos)
                                + "ms after " + startedPhase
                                + ". Started set was " + startedNodeIds
                                + " (held back, not expected alive: " + HELD_BACK
                                + "). A node leaves EmberCluster's running registry only via handleSelfDrain, "
                                + "so this is a self-fence/drain, not a crash. Every subsequent observation "
                                + "this probe could make is invalid. Live now: " + cluster.nodeCount()
                                + " node(s), leader=" + cluster.currentLeader().or("none")
                                + " countedCores=" + countedCores()
                                + " countedIds=" + countedCoreIds()
                                + " provisions=" + recorder.render()
                                + " decision[" + provisioningDiagnostics().map(PostRestartSlowRejoinDeficitFillProbeTest::formatDecision)
                                                                        .or("unavailable")
                                + "]");
    }

    /// Record which ids are expected alive from now on, and reset the phase clock the liveness failure
    /// reports against. Called after every start/release, never mid-wait.
    private void expectStarted(Set<String> nodeIds, String phase) {
        startedNodeIds = Set.copyOf(nodeIds);
        startedPhase = phase;
        startedPhaseNanos = System.nanoTime();
    }

    private static Set<String> allConfiguredIds() {
        return IntStream.rangeClosed(1, INITIAL_CORES)
                        .mapToObj(i -> NODE_PREFIX + "-" + i)
                        .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Set<String> startedAfterHoldBack() {
        return allConfiguredIds().stream()
                                 .filter(id -> !HELD_BACK.contains(id))
                                 .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static long elapsedMs(long t0) {
        return (System.nanoTime() - t0) / 1_000_000L;
    }

    // ----- in-process membership reads -----
    /// The counted-core denominator the reconciler itself uses for deficit math, read off the leader's
    /// `MembershipFsm` (falls back to any node when the leader handle is momentarily absent).
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

    private static String configuredCoreIds() {
        return String.join(",", allConfiguredIds());
    }

    /// Leader `PresenceSampler` peak (high-water mark) — the value the reconciler's
    /// `reachedFullMembership` cold-start latch reads.
    private int observedPeak() {
        return leaderOrAnyNode().map(AetherNode::observedPeakMembership)
                              .or(0);
    }

    private Option<ProvisioningDiagnostics> provisioningDiagnostics() {
        return leaderOrAnyNode().flatMap(AetherNode::provisioningDiagnostics);
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
                  .collect(Collectors.joining(","));
    }

    // ----- diagnostics (ticket evidence; nothing asserts on these) -----
    private void recordMilestone(String milestone) {
        milestones.add(milestone);
        log.info("SLOWJOIN-PROBE MILESTONE: {}", milestone);
    }

    private void dumpTimeline(String label, List<String> timeline) {
        log.info("SLOWJOIN-PROBE {} TIMELINE ------------------------------------------------", label);
        timeline.forEach(line -> log.info("SLOWJOIN-PROBE {}", line));
        log.info("SLOWJOIN-PROBE MEMBERSHIP MILESTONES -----------------------------------------");
        milestones.forEach(line -> log.info("SLOWJOIN-PROBE {}", line));
        log.info("SLOWJOIN-PROBE ---------------------------------------------------------------");
    }

    private void dumpProvisionLedger() {
        log.info("SLOWJOIN-PROBE PROVISION LEDGER: {} call(s)", recorder.provisionCalls());
        recorder.recorded().forEach(call -> log.info("SLOWJOIN-PROBE {}", call.describe()));
    }

    private static void failStart(Cause cause) {
        throw new AssertionError("Cluster start failed: " + cause.message());
    }

    private static void failScenario(Cause cause) {
        throw new AssertionError("Scenario setup failed: " + cause.message());
    }

    // ----- HTTP (positive-control scale trigger + config version read) -----
    private int readConfigVersion(int port) {
        var matcher = CONFIG_VERSION.matcher(httpGet(port, "/api/cluster/config"));

        return matcher.find()
               ? Number.parseInt(matcher.group(1)).or(0)
               : 0;
    }

    @TerminalOperation
    private String postScale(int port, int coreCount, int expectedVersion) {
        var body = "{\"coreCount\":" + coreCount + ",\"expectedVersion\":" + expectedVersion + "}";
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + "/api/cluster/scale"))
                                 .header("Content-Type", "application/json")
                                 .POST(HttpRequest.BodyPublishers.ofString(body))
                                 .timeout(Duration.ofSeconds(10))
                                 .build();

        return http.sendString(request)
                   .await()
                   .map(PostRestartSlowRejoinDeficitFillProbeTest::renderResponse)
                   .or("scale POST failed (no response)");
    }

    private static String renderResponse(HttpResult result) {
        return "HTTP " + result.statusCode() + " " + result.body();
    }

    @TerminalOperation
    private String httpGet(int port, String path) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + path))
                                 .GET()
                                 .timeout(Duration.ofSeconds(5))
                                 .build();

        return http.sendString(request)
                   .await()
                   .map(HttpResult::body)
                   .or("{}");
    }

    private enum ProbeError implements Cause {
        NO_LEADER("No leader available to receive the scale request");
        private final String message;
        ProbeError(String message) {
            this.message = message;
        }
        @Override
        public String message() {
            return message;
        }
    }

    /// RECORDING [ComputeProvider] decorator factory (authorized test seam consumer). Counts and
    /// timestamps every `createFrom` and ALWAYS delegates — see the class doc on why this probe must
    /// not inject faults. `terminate`/`listInstances`/`instanceStatus` pass straight through.
    private static final class ProvisionRecorder {
        private final List<ProvisionCall> calls = new CopyOnWriteArrayList<>();
        private final long originNanos = System.nanoTime();

        private ComputeProvider wrap(ComputeProvider delegate) {
            return new RecordingProvider(delegate);
        }

        private int provisionCalls() {
            return calls.size();
        }

        private List<ProvisionCall> recorded() {
            return List.copyOf(calls);
        }

        private String render() {
            return calls.isEmpty()
                   ? "none"
                   : calls.stream()
                          .map(ProvisionCall::describe)
                          .collect(Collectors.joining("; "));
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
                var call = ProvisionCall.provisionCall(elapsedMs(originNanos), request);

                calls.add(call);
                log.info("SLOWJOIN-PROBE RECORD: provision #{} {}", calls.size(), call.describe());

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

    /// One recorded provisioning request: when it arrived (ms since the recorder was created, i.e. a
    /// probe-wide clock spanning both formations) and which node the CTM asked for.
    private record ProvisionCall(long atMs, String requestedNodeId, String role, String provisionedBy) {
        private static ProvisionCall provisionCall(long atMs, ProvisionRequest request) {
            var context = request.context();

            return new ProvisionCall(atMs,
                                     context.nodeId().or("<unnamed>"),
                                     context.role(),
                                     context.provisionedBy());
        }

        private String describe() {
            return "t+" + atMs
                 + "ms requestedNodeId=" + requestedNodeId
                 + " role=" + role
                 + " provisionedBy=" + provisionedBy;
        }
    }
}
