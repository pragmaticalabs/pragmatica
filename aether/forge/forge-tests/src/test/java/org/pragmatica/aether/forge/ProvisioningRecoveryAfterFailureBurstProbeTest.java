// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.forge;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.pragmatica.aether.deployment.cluster.ClusterTopologyManager.CircuitBreakerState;
import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.InstanceType;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// Diagnostic probe (GH #336 suspected root) — when core-provisioning fails in a transient BURST
/// that trips the #148 provisioning circuit breaker, and conditions then clear, does provisioning
/// **RECOVER** (deficit eventually re-filled) or does a gap wedge it permanently?
///
/// ## Why Ember / Forge for this question
/// The wedge hypothesis is about the LOGIC seam between three pieces, all of which Ember exercises
/// faithfully in a single JVM with no DNS / advertise-host / container boot:
///   - the CTM #148 breaker (`ClusterTopologyManagerRecord.provisioningCircuitOpen` /
///     `recordProvisioningFailure` / `resetProvisioningCircuit`),
///   - the `LeaderReconciler` `DEFICIT_FOLLOW_UP` self-rearming loop (re-fires off the RAW
///     confirmed-member deficit, ~15s spacing), and
///   - the `ProvisionDisposition.Deferred(CIRCUIT_OPEN)` placeholder-removal path.
/// If recovery works in-JVM the provisioning *logic* is sound and #336 is cloud-only; if it wedges
/// here the bug is provider-independent and lives in this trio.
///
/// ## Fault injection (authorized test seam)
/// A [ComputeProvider] WRAPPER around the real `EmberComputeProvider` (installed via the new
/// `EmberCluster.withComputeProviderDecorator` seam BEFORE `start()`) returns a FAILED `Promise`
/// (`EnvironmentError.CapacityUnavailable` — a transient, capacity-style cause) for the next
/// `K = MAX_CONSECUTIVE_PROVISIONING_FAILURES + 2 = 5` `provision()` calls, then delegates to the
/// real provider for every subsequent call. `K > 3` guarantees the breaker trips. Only `provision`
/// is intercepted; `terminate`/`listInstances`/`instanceStatus` always delegate.
///
/// ## Scenario
/// 1. Form a 5-core Ember cluster; wait until `coreCountedMembers()==5`, a leader exists, and full
///    membership has been observed (peak==5, the in-process proxy for `reachedFullMembership`).
/// 2. Kill ONE non-leader core → auto-heal deficit → reconciler → `provisionReplacement` → the
///    wrapper FAILS the first 5 provisions → breaker TRIPS (CIRCUIT_OPEN, `tripped=true`).
/// 3. After the 5 failures are exhausted, the wrapper delegates → real provisions succeed. Once the
///    breaker backoff window (`provisioningTimeout`, 60s default) elapses and the reconciler's
///    `DEFICIT_FOLLOW_UP` re-fires, a provision should be re-attempted, the replacement should join,
///    and `coreCountedMembers()` should return to 5.
/// 4. Sample `coreCountedMembers()` + `circuitBreakerState()` + provision-call/failure counters on a
///    bounded 180s timeline.
///
/// ## What the assertions ride on (no log-scraping)
/// The aether reactor logs via log4j2, so a logback `ListAppender` on the `reason=` line is NOT
/// available; the CTM `circuitBreakerState()` (`consecutiveFailures`, `trippedAt`=cap,
/// `nextAllowedMs`, `tripped`) is a strictly better, first-class observable and — together with the
/// in-process counted-core denominator and the wrapper's own call/failure counters — fully answers
/// the recovery question. The per-tick "phase" label below is DERIVED from breaker state + counts,
/// standing in for the reconciler `reason=` token.
///
/// PASS  = breaker tripped during the burst AND coreCountedMembers returns to 5 within budget
///         (recovery works → provisioning logic is sound, #336 is not provider-independent).
/// FAIL  = breaker tripped but the deficit never refills though the wrapper would now succeed
///         (the wedge — capture the stuck breaker state + counters in the dump).
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProvisioningRecoveryAfterFailureBurstProbeTest {
    private static final Logger log = LoggerFactory.getLogger(ProvisioningRecoveryAfterFailureBurstProbeTest.class);

    private static final int INITIAL_CORES = 5;
    // CTM ClusterTopologyManagerRecord.MAX_CONSECUTIVE_PROVISIONING_FAILURES = 3. K=4 (cap+1)
    // OVERSHOOTS the cap: the 3rd failure trips the breaker, the 4th (served after the 60s backoff
    // re-probe) re-confirms the trip — proving the breaker re-arms — and the 5th attempt (after the
    // SECOND backoff) delegates to the real provider and SUCCEEDS. This exercises the full
    // trip → backoff → re-probe → recover loop rather than just a single trip.
    private static final int INJECTED_FAILURES = 4;
    private static final int BASE_PORT = 5960;
    private static final int BASE_MGMT_PORT = 6060;
    private static final int BASE_APP_HTTP_PORT = 6160;

    private static final Duration FORM_TIMEOUT = Duration.ofSeconds(60);
    // Budget sized from the OBSERVED cadence: DEFICIT_FOLLOW_UP re-attempts ~every 30s pre-trip, and
    // the #148 breaker backoff is provisioningTimeout=60s. With K=4 the success attempt lands ~205s
    // after the kill (fail@~24/54/84[trip]/145[re-trip] → success@~205s), so 240s gives margin.
    private static final Duration RECOVERY_BUDGET = Duration.ofSeconds(240);
    private static final Duration POLL = Duration.ofMillis(500);
    private static final Duration LOG_EVERY = Duration.ofSeconds(5);

    private EmberCluster cluster;
    private final FaultInjectingProviderFactory faultFactory = new FaultInjectingProviderFactory(INJECTED_FAILURES);

    @BeforeAll
    @TerminalOperation
    void setUp() {
        cluster = emberCluster(INITIAL_CORES, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, "recover");
        cluster.withComputeProviderDecorator(faultFactory::wrap);
        cluster.start()
               .await()
               .onFailure(ProvisioningRecoveryAfterFailureBurstProbeTest::failStart);

        await().atMost(FORM_TIMEOUT).pollInterval(POLL).until(() -> cluster.currentLeader().isPresent());
        await().atMost(FORM_TIMEOUT).pollInterval(POLL).until(() -> countedCores() == INITIAL_CORES);
        // The reconciler's `reachedFullMembership` cold-start latch reads the leader PresenceSampler
        // PEAK (peak >= configuredCoreCount), NOT coreCountedMembers(). Both must reach 5 before a
        // kill, or the deficit pass logs reason=COLD_START_NOT_FULL and never provisions.
        await().atMost(FORM_TIMEOUT).pollInterval(POLL).until(() -> observedPeak() >= INITIAL_CORES);
        log.info("RECOVERY-PROBE: {}-core cluster formed, leader={}, countedCores={} observedPeak={} "
                 + "(full membership observed — reachedFullMembership armed)",
                 INITIAL_CORES, cluster.currentLeader().or("none"), countedCores(), observedPeak());
    }

    @AfterAll
    @TerminalOperation
    void tearDown() {
        Option.option(cluster).onPresent(c -> c.stop().await());
    }

    @Test
    @TerminalOperation
    void provisioningRecoversAfterTransientFailureBurst_breakerTripsThenDeficitRefills() {
        var leaderId = cluster.currentLeader().or("none");
        var victim = pickNonLeaderCore();
        log.info("RECOVERY-PROBE: leader={} FORCE-killing NON-LEADER core victim={} to open a 1-core deficit (auto-heal path)",
                 leaderId, victim);

        cluster.killNode(victim, false)
               .await()
               .onFailure(ProvisioningRecoveryAfterFailureBurstProbeTest::failScenario);

        var t0 = System.nanoTime();
        var probe = new ProbeState();
        observeUntilRecoveredOrBudget(t0, probe);

        var finalCounted = countedCores();
        var deficitObserved = probe.deficitObservedAt.get() >= 0;
        var breakerTripped = probe.breakerTrippedAt.get() >= 0;
        var recovered = probe.recoveredAt.get() >= 0;

        log.info("RECOVERY-PROBE TIMELINE ----------------------------------------------------------");
        probe.timeline.forEach(line -> log.info("RECOVERY-PROBE {}", line));
        log.info("RECOVERY-PROBE ----------------------------------------------------------------");
        log.info("RECOVERY-PROBE RESULT: deficitObservedAtMs={} breakerTrippedAtMs={} recoveredAtMs={} "
                 + "finalCountedCores={} injectedFailures={} provisionCalls={} provisionFailuresServed={} finalBreaker={}",
                 probe.deficitObservedAt.get(), probe.breakerTrippedAt.get(), probe.recoveredAt.get(), finalCounted,
                 INJECTED_FAILURES, faultFactory.provisionCalls(), faultFactory.failuresServed(),
                 breakerSummary());
        dumpDiagnostics();

        assertThat(deficitObserved)
            .as("PRECONDITION-1: killing core %s must register a deficit (coreCountedMembers drops "
                + "below %d, a provision is attempted, or the breaker activates) within the budget. "
                + "If false, the auto-heal path never engaged — provisionCalls=%d. Nothing about "
                + "recovery can be concluded.",
                victim, INITIAL_CORES, faultFactory.provisionCalls())
            .isTrue();

        assertThat(breakerTripped)
            .as("PRECONDITION-2: the injected burst of %d provision failures must TRIP the #148 "
                + "circuit breaker (tripped=true observed at least once). If false, the burst never "
                + "reached the breaker — provisionCalls=%d failuresServed=%d. The recovery question "
                + "is only meaningful once the breaker actually trips.",
                INJECTED_FAILURES, faultFactory.provisionCalls(), faultFactory.failuresServed())
            .isTrue();

        assertThat(recovered)
            .as("RECOVERY: after the deficit appeared AND the %d-failure burst cleared (wrapper now "
                + "delegates to the real provider, which SUCCEEDS) AND the breaker backoff window "
                + "elapsed, coreCountedMembers() must return to %d within %ds. recoveredAtMs=%d "
                + "finalCounted=%d finalBreaker=%s. FALSE = WEDGE: the provider would now succeed but "
                + "the deficit stayed open / no further provision was attempted — this is #336 "
                + "reproducing IN-JVM (provider-independent logic bug). The stuck breaker state and "
                + "the provisionCalls vs failuresServed counters above pinpoint whether the "
                + "reconciler stopped re-attempting (provisionCalls frozen) or kept attempting but the "
                + "join never counted.",
                INJECTED_FAILURES, INITIAL_CORES, RECOVERY_BUDGET.toSeconds(),
                probe.recoveredAt.get(), finalCounted, breakerSummary())
            .isTrue();
    }

    /// Mutable, thread-confined (single observer thread) probe latches. `deficitObservedAt` MUST
    /// arm before `recoveredAt` can — otherwise the still-counted SUSPECT victim (coreCountedMembers
    /// counts MEMBER+SUSPECT for ~splitTimeout after a kill) would latch "recovered" at t≈0 before a
    /// deficit ever materialised.
    private static final class ProbeState {
        private final List<String> timeline = new ArrayList<>();
        private final AtomicLong deficitObservedAt = new AtomicLong(-1L);
        private final AtomicLong breakerTrippedAt = new AtomicLong(-1L);
        private final AtomicLong recoveredAt = new AtomicLong(-1L);
        private long lastLogMs = -LOG_EVERY.toMillis();
    }

    private void observeUntilRecoveredOrBudget(long t0, ProbeState probe) {
        await().pollInterval(POLL)
               .pollDelay(Duration.ZERO)
               .timeout(RECOVERY_BUDGET.plusSeconds(10))
               .until(() -> recordTick(t0, probe));
    }

    private boolean recordTick(long t0, ProbeState probe) {
        var elapsed = (System.nanoTime() - t0) / 1_000_000L;
        var counted = countedCores();
        var state = breakerState();

        if (state.tripped() && probe.breakerTrippedAt.get() < 0) {
            probe.breakerTrippedAt.set(elapsed);
        }
        if (deficitReacted(counted, state) && probe.deficitObservedAt.get() < 0) {
            probe.deficitObservedAt.set(elapsed);
        }
        if (probe.deficitObservedAt.get() >= 0 && counted >= INITIAL_CORES && probe.recoveredAt.get() < 0) {
            probe.recoveredAt.set(elapsed);
        }
        maybeRecord(elapsed, counted, state, probe);

        return probe.recoveredAt.get() >= 0 || elapsed >= RECOVERY_BUDGET.toMillis();
    }

    /// The auto-heal path has demonstrably ENGAGED with the kill: the counted denominator dropped
    /// below target (victim left the MEMBER+SUSPECT set), or a provision was attempted, or the
    /// breaker activated. Any one is sufficient evidence a deficit was registered.
    private boolean deficitReacted(int counted, CircuitBreakerState state) {
        return counted < INITIAL_CORES
               || faultFactory.provisionCalls() > 0
               || state.consecutiveFailures() > 0
               || state.tripped();
    }

    private void maybeRecord(long elapsed, int counted, CircuitBreakerState state, ProbeState probe) {
        if (elapsed - probe.lastLogMs < LOG_EVERY.toMillis()) {
            return;
        }
        probe.lastLogMs = elapsed;
        probe.timeline.add(formatTick(elapsed, counted, state));
    }

    /// Per-tick timeline row. `phase=` is the DERIVED stand-in for the reconciler `reason=` token
    /// (the log4j backend makes the real `reason=` appender unavailable): it is computed purely from
    /// the breaker state and the counted-core denominator the reconciler itself uses for deficit
    /// math, so it tracks the same decision the reconciler is making.
    private String formatTick(long elapsedMs, int counted, CircuitBreakerState state) {
        return "t+" + elapsedMs + "ms coreCount=" + counted + "/" + INITIAL_CORES
               + " breaker[" + formatBreaker(state) + "]"
               + " provisionCalls=" + faultFactory.provisionCalls()
               + " failuresServed=" + faultFactory.failuresServed()
               + " phase=" + derivePhase(counted, state);
    }

    private String derivePhase(int counted, CircuitBreakerState state) {
        if (counted >= INITIAL_CORES) {
            return "RECOVERED_NO_DEFICIT";
        }
        if (state.tripped()) {
            return "DEFICIT_CIRCUIT_OPEN";
        }
        if (faultFactory.failuresServed() < INJECTED_FAILURES) {
            return "DEFICIT_PROVISIONING_FAILING";
        }
        return "DEFICIT_PROVISIONING_PERMITTED";
    }

    // ----- in-process membership reads -----

    private int countedCores() {
        return leaderOrAnyNode().map(node -> node.membershipFsm().coreCountedMembers().size()).or(0);
    }

    /// Leader PresenceSampler peak (high-water mark) — the value the reconciler's
    /// `reachedFullMembership` cold-start latch reads. Must reach the cluster size before a kill,
    /// or the deficit pass never arms provisioning.
    private int observedPeak() {
        return leaderOrAnyNode().map(AetherNode::observedPeakMembership).or(0);
    }

    private String pickNonLeaderCore() {
        var leader = cluster.currentLeader().or("none");

        return cluster.allNodes().stream()
                      .map(node -> node.self().id())
                      .filter(id -> !id.equals(leader))
                      .findFirst()
                      .orElse(leader);
    }

    private Option<AetherNode> leaderOrAnyNode() {
        return cluster.currentLeader()
                      .flatMap(cluster::getNode)
                      .orElse(() -> Option.from(cluster.allNodes().stream().findFirst()));
    }

    private CircuitBreakerState breakerState() {
        return leaderOrAnyNode().flatMap(AetherNode::clusterTopologyManager)
                                .map(ctm -> ctm.circuitBreakerState())
                                .or(EMPTY_BREAKER);
    }

    private static final CircuitBreakerState EMPTY_BREAKER = new CircuitBreakerState(0, 3, 0L, false);

    private String breakerSummary() {
        return formatBreaker(breakerState());
    }

    private static String formatBreaker(CircuitBreakerState state) {
        return "consecutiveFailures=" + state.consecutiveFailures()
               + " tripped=" + state.tripped()
               + " trippedAt(cap)=" + state.trippedAt()
               + " nextAllowedMs=" + state.nextAllowedMs();
    }

    @TerminalOperation
    private void dumpDiagnostics() {
        var counted = countedCores();
        log.info("RECOVERY-PROBE DUMP: leader={} countedCores={}/{} ember.nodeCount={}",
                 cluster.currentLeader().or("none"), counted, INITIAL_CORES, cluster.nodeCount());
        log.info("RECOVERY-PROBE DUMP: provisioning circuit-breaker = {}", breakerSummary());
        log.info("RECOVERY-PROBE DUMP: wrapper provisionCalls={} failuresServed={} (injected={})",
                 faultFactory.provisionCalls(), faultFactory.failuresServed(), INJECTED_FAILURES);
        cluster.allNodes().forEach(node -> log.info("RECOVERY-PROBE DUMP: node={} leader={}",
                                                    node.self().id(), node.isLeader()));
    }

    private static void failStart(Cause cause) {
        throw new AssertionError("Cluster start failed: " + cause.message());
    }

    private static void failScenario(Cause cause) {
        throw new AssertionError("Scenario setup failed: " + cause.message());
    }

    /// Fault-injecting [ComputeProvider] decorator factory (authorized test seam consumer). Fails the
    /// first `injectedFailures` `provision()` calls with a transient `CapacityUnavailable` cause, then
    /// delegates everything to the wrapped real provider. Call/failure counters drive the timeline.
    private static final class FaultInjectingProviderFactory {
        private final int injectedFailures;
        private final AtomicInteger calls = new AtomicInteger(0);
        private final AtomicInteger served = new AtomicInteger(0);

        private FaultInjectingProviderFactory(int injectedFailures) {
            this.injectedFailures = injectedFailures;
        }

        private ComputeProvider wrap(ComputeProvider delegate) {
            return new FaultInjectingProvider(delegate);
        }

        private int provisionCalls() {
            return calls.get();
        }

        private int failuresServed() {
            return served.get();
        }

        private final class FaultInjectingProvider implements ComputeProvider {
            private final ComputeProvider delegate;

            private FaultInjectingProvider(ComputeProvider delegate) {
                this.delegate = delegate;
            }

            @Override
            public Promise<InstanceInfo> provision(InstanceType instanceType) {
                calls.incrementAndGet();

                return shouldFail()
                       ? injectFailure()
                       : delegate.provision(instanceType);
            }

            private boolean shouldFail() {
                return served.get() < injectedFailures;
            }

            private Promise<InstanceInfo> injectFailure() {
                var n = served.incrementAndGet();
                log.info("RECOVERY-PROBE INJECT: provision FAILURE {}/{} (CapacityUnavailable)", n, injectedFailures);

                return TRANSIENT_CAPACITY.promise();
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

    private static final Cause TRANSIENT_CAPACITY =
        EnvironmentError.capacityUnavailable("", new RuntimeException("injected transient capacity exhaustion (#336 probe)"));
}
