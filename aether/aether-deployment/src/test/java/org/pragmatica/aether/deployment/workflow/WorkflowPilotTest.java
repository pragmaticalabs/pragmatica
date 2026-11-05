// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.workflow;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.pragmatica.aether.deployment.workflow.WorkflowStep.Readiness;
import org.pragmatica.aether.slice.kvstore.AetherValue.WorkflowClaimValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.WorkflowRunValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.WorkflowState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.workflow.WorkflowTestCluster.CORE_1;
import static org.pragmatica.aether.deployment.workflow.WorkflowTestCluster.CORE_2;
import static org.assertj.core.api.Assertions.assertThat;


/// rc6 f2a item 4: a TEST-ONLY pilot kind driven by the real reconciler over a real KV applier, through every state: RUNNING,
/// WAITING, DONE, PAUSED, FAILED, ESCALATED and the UNKNOWN sentinel; across a leader change mid-run; with an effect whose call
/// fails although the effect happened (the postcondition decides); and to exhaustion (escalated, with the incident raised).
///
/// The pilot "provisions" then "verifies" a node of a modelled world. `World.provisionCalls` counts the effects actually issued,
/// which is what "never re-issued blindly" is measured by.
class WorkflowPilotTest {
    private static final Cause CALL_FAILED = Causes.cause("provider call timed out");
    private static final long SETTLE_MS = 10_000L;
    private static final long T0 = 1_000_000L;

    /// How the provision call behaves.
    enum Mode {
        /// The effect happens and the call answers success.
        PERFORM,
        /// The effect happens, but the call fails (an answer lost in transit): uncertain.
        PERFORM_THEN_FAIL,
        /// The call fails and nothing happened: uncertain, and this time the effect really is missing.
        LOST,
        /// The call answers success, but the effect never shows.
        NO_EFFECT
    }

    static final class World {
        final AtomicInteger provisionCalls = new AtomicInteger();
        final AtomicInteger verifyCalls = new AtomicInteger();
        volatile boolean provisioned;
        volatile boolean verified;
        volatile Mode mode = Mode.PERFORM;
        volatile Readiness readiness = Readiness.READY;

        Obs observe() {
            return new Obs(provisioned, verified, readiness);
        }

        Promise<Unit> provision(WorkflowRunValue run) {
            provisionCalls.incrementAndGet();

            return switch (mode) {
                case PERFORM -> performProvision();
                case PERFORM_THEN_FAIL -> performProvision().flatMap(_ -> CALL_FAILED.<Unit> promise());
                case LOST -> CALL_FAILED.promise();
                case NO_EFFECT -> Promise.unitPromise();
            };
        }

        private Promise<Unit> performProvision() {
            provisioned = true;

            return Promise.unitPromise();
        }

        Promise<Unit> verify(WorkflowRunValue run) {
            verifyCalls.incrementAndGet();
            verified = true;

            return Promise.unitPromise();
        }
    }

    record Obs(boolean provisioned, boolean verified, Readiness readiness) {}

    private WorkflowTestCluster cluster;
    private World world;
    private AtomicLong clock;
    private List<OperatorWarning> warnings;
    private Map<String, WorkflowKind<?>> kinds;

    @BeforeEach
    void setUp() {
        cluster = new WorkflowTestCluster();
        world = new World();
        clock = new AtomicLong(T0);
        warnings = new CopyOnWriteArrayList<>();
        kinds = Map.of("pilot", pilot(world));
    }

    private static WorkflowKind<Obs> pilot(World world) {
        var steps = List.of(new WorkflowStep<Obs>("provision",
                                                  Obs::readiness,
                                                  world::provision,
                                                  Obs::provisioned,
                                                  "node provisioned",
                                                  TimeSpan.timeSpan(SETTLE_MS).millis()),
                            new WorkflowStep<Obs>("verify",
                                                  _ -> Readiness.READY,
                                                  world::verify,
                                                  Obs::verified,
                                                  "node verified",
                                                  TimeSpan.timeSpan(SETTLE_MS).millis()));
        WorkflowPlanner<Obs> planner = run -> run.stepIndex() < steps.size()
                                              ? Option.some(steps.get(run.stepIndex()))
                                              : Option.none();

        return new WorkflowKind<>("pilot",
                                  new WorkflowBudget(2,
                                                     TimeSpan.timeSpan(100).seconds(),
                                                     4),
                                  planner,
                                  world::observe);
    }

    private WorkflowReconciler reconciler(NodeId self) {
        return WorkflowReconciler.workflowReconciler(cluster.ledger(self), kinds, clock::get, warnings::add);
    }

    private WorkflowRunValue admit() {
        return WorkflowAdmission.workflowAdmission(cluster.ledger(CORE_1),
                                                   kinds,
                                                   () -> "wf-pilot")
                                .admit("pilot",
                                       Map.of("node", "n1"),
                                       List.of("node:n1"),
                                       clock.get())
                                .await()
                                .unwrap();
    }

    private WorkflowRunValue committed() {
        return cluster.run("wf-pilot")
                      .unwrap();
    }

    private static void tick(WorkflowReconciler reconciler) {
        reconciler.reconcile().await().unwrap();
    }

    @Test
    void pilot_happyPath_runningWaitingThenDone_releasesItsClaim() {
        admit();
        var leader = reconciler(CORE_1);

        assertThat(committed().state()).isEqualTo(WorkflowState.RUNNING);
        tick(leader);
        assertThat(committed().state()).isEqualTo(WorkflowState.WAITING);
        assertThat(committed().detail()).isEqualTo("node provisioned");
        tick(leader);
        assertThat(committed().stepIndex()).isEqualTo(1);
        assertThat(committed().state()).isEqualTo(WorkflowState.RUNNING);
        tick(leader);
        tick(leader);
        assertThat(committed().state()).isEqualTo(WorkflowState.DONE);
        assertThat(world.provisionCalls.get()).isEqualTo(1);
        assertThat(world.verifyCalls.get()).isEqualTo(1);
        assertThat(committed().effects()).isEqualTo(2);
        assertThat(cluster.claim("node:n1")).as("DONE releases the claim").isEqualTo(Option.none());
        tick(leader);
        assertThat(world.provisionCalls.get() + world.verifyCalls.get()).as("a DONE run is inert").isEqualTo(2);
        assertThat(warnings).isEmpty();
    }

    /// INV-06: the old leader issued the effect and lost leadership; the new leader continues from the committed run, adopting the
    /// effect by its postcondition, and the old leader's reconciler does nothing.
    @Test
    void pilot_leaderChangeMidRun_newLeaderContinues_withoutReissuingOrResetting() {
        admit();
        var oldLeader = reconciler(CORE_1);
        var newLeader = reconciler(CORE_2);

        tick(oldLeader);
        var issued = committed();

        assertThat(issued.state()).isEqualTo(WorkflowState.WAITING);
        cluster.lead(CORE_2);
        tick(oldLeader);
        assertThat(committed()).as("a deposed leader commits nothing").isEqualTo(issued);
        tick(newLeader);
        assertThat(committed().stepIndex()).as("the new leader adopts the issued effect").isEqualTo(1);
        assertThat(committed().effects()).as("failover resets nothing").isEqualTo(1);
        assertThat(committed().deadline()).isEqualTo(issued.deadline());
        tick(newLeader);
        tick(newLeader);
        assertThat(committed().state()).isEqualTo(WorkflowState.DONE);
        assertThat(world.provisionCalls.get()).as("issued exactly once across the failover").isEqualTo(1);
    }

    /// The old leader committed the issue, but its effect never happened (it died before the call, or the call was lost). The new
    /// leader does not re-issue inside the settle window; after it, the effect is issued again as attempt 2, not attempt 1.
    @Test
    void pilot_leaderChangeAfterALostEffect_reissuesOnlyAfterTheSettleWindow_continuingTheAttemptCount() {
        admit();
        world.mode = Mode.LOST;
        tick(reconciler(CORE_1));
        assertThat(committed().attempts()).isEqualTo(1);
        cluster.lead(CORE_2);
        world.mode = Mode.PERFORM;
        var newLeader = reconciler(CORE_2);

        clock.addAndGet(SETTLE_MS - 1);
        tick(newLeader);
        assertThat(world.provisionCalls.get()).as("never re-issued blindly inside the settle window").isEqualTo(1);
        assertThat(committed().state()).isEqualTo(WorkflowState.WAITING);
        clock.addAndGet(1);
        tick(newLeader);
        assertThat(world.provisionCalls.get()).isEqualTo(2);
        assertThat(committed().attempts()).as("the attempt count survives the failover").isEqualTo(2);
        tick(newLeader);
        tick(newLeader);
        tick(newLeader);
        assertThat(committed().state()).isEqualTo(WorkflowState.DONE);
    }

    /// INV-05: the call failed, but the effect happened. The postcondition decides: the step advances, nothing is re-issued.
    @Test
    void pilot_uncertainEffectThatHappened_isAdoptedByItsPostcondition() {
        admit();
        world.mode = Mode.PERFORM_THEN_FAIL;
        var leader = reconciler(CORE_1);

        tick(leader);
        assertThat(committed().state()).as("a failed call leaves the run WAITING, not failed")
                  .isEqualTo(WorkflowState.WAITING);
        clock.addAndGet(SETTLE_MS * 2);
        tick(leader);
        assertThat(committed().stepIndex()).isEqualTo(1);
        assertThat(world.provisionCalls.get()).as("the uncertain effect was not re-issued").isEqualTo(1);
    }

    /// Exhaustion: the effect never shows. After the attempt budget the run is ESCALATED, the incident names the postcondition, it is
    /// raised once on the operator-event path, the claim is KEPT, and the run is inert.
    @Test
    void pilot_effectThatNeverShows_escalatesAfterTheAttemptBudget_raisingOneIncident_keepingTheClaim() {
        admit();
        world.mode = Mode.NO_EFFECT;
        var leader = reconciler(CORE_1);

        tick(leader);
        clock.addAndGet(SETTLE_MS);
        tick(leader);
        assertThat(committed().attempts()).isEqualTo(2);
        assertThat(warnings).isEmpty();
        clock.addAndGet(SETTLE_MS);
        tick(leader);
        var escalated = committed();

        assertThat(escalated.state()).isEqualTo(WorkflowState.ESCALATED);
        assertThat(escalated.detail()).contains("attempt budget of 2", "'node provisioned'");
        assertThat(warnings).hasSize(1);
        assertThat(warnings.getFirst().code()).isEqualTo(OperatorWarningCode.WORKFLOW_ESCALATED);
        assertThat(warnings.getFirst().subject()).isEqualTo("wf-pilot");
        assertThat(warnings.getFirst().message()).contains("'node provisioned'", "resume", "abandon");
        assertThat(cluster.claim("node:n1")).isEqualTo(Option.some(new WorkflowClaimValue("wf-pilot")));
        clock.addAndGet(SETTLE_MS * 100);
        tick(leader);
        tick(leader);
        assertThat(world.provisionCalls.get()).as("an escalated run is inert").isEqualTo(2);
        assertThat(warnings).as("the incident is raised once").hasSize(1);
    }

    @Test
    void pilot_pastItsDeadline_escalatesOnTheElapsedBudget() {
        admit();
        world.readiness = new Readiness.NotYet("no capacity");
        var leader = reconciler(CORE_1);

        tick(leader);
        assertThat(committed().state()).isEqualTo(WorkflowState.RUNNING);
        clock.addAndGet(100_000L);
        tick(leader);
        assertThat(committed().state()).isEqualTo(WorkflowState.ESCALATED);
        assertThat(committed().detail()).contains("elapsed-time budget");
        assertThat(world.provisionCalls.get()).isZero();
        assertThat(warnings).hasSize(1);
    }

    @Test
    void pilot_impossiblePrecondition_fails_andReleasesTheClaim() {
        admit();
        world.readiness = new Readiness.Impossible("node n1 left the cluster");
        tick(reconciler(CORE_1));
        assertThat(committed().state()).isEqualTo(WorkflowState.FAILED);
        assertThat(committed().detail()).contains("node n1 left the cluster");
        assertThat(cluster.claim("node:n1")).isEqualTo(Option.none());
        assertThat(world.provisionCalls.get()).isZero();
    }

    /// PAUSED is inert; a resume continues from the committed step, and an issued effect is re-checked before anything is re-issued.
    @Test
    void pilot_paused_isInert_andResumeContinues() {
        admit();
        var ledger = cluster.ledger(CORE_1);
        var leader = reconciler(CORE_1);

        tick(leader);
        var waiting = committed();

        assertThat(ledger.commit(waiting, waiting.in(WorkflowState.PAUSED, "operator pause")).await().unwrap()).isTrue();
        clock.addAndGet(SETTLE_MS * 3);
        tick(leader);
        assertThat(committed().state()).isEqualTo(WorkflowState.PAUSED);
        assertThat(committed().stepIndex()).isZero();
        var paused = committed();

        assertThat(ledger.commit(paused,
                                 paused.resumed(clock.get() + 100_000L)).await().unwrap()).isTrue();
        tick(leader);
        assertThat(committed().stepIndex()).as("the issued effect is adopted after the resume").isEqualTo(1);
        tick(leader);
        tick(leader);
        assertThat(committed().state()).isEqualTo(WorkflowState.DONE);
        assertThat(world.provisionCalls.get()).isEqualTo(1);
    }

    /// The decode sentinel: a run in a state this node cannot name is never advanced, and keeps its claim.
    @Test
    void pilot_unknownState_isInert() {
        var run = admit();

        cluster.putRaw(run.in(WorkflowState.UNKNOWN, ""));
        tick(reconciler(CORE_1));
        assertThat(committed().state()).isEqualTo(WorkflowState.UNKNOWN);
        assertThat(world.provisionCalls.get()).isZero();
        assertThat(cluster.claim("node:n1")).isPresent();
    }

    /// A run of a kind this node does not know (admitted by a newer node) is held, never failed.
    @Test
    void pilot_runOfAnUnknownKind_isHeld() {
        var run = admit();

        kinds = Map.of();
        tick(reconciler(CORE_1));
        assertThat(committed()).isEqualTo(run);
    }

    /// One run's failure (its observation throws) does not stop another run's tick.
    @Test
    void reconcile_aThrowingObservation_doesNotStopTheOtherRuns() {
        var broken = new WorkflowKind<Obs>("broken",
                                           new WorkflowBudget(2,
                                                              TimeSpan.timeSpan(100).seconds(),
                                                              4),
                                           _ -> Option.none(),
                                           () -> {
                                               throw new IllegalStateException("observation failed");
                                           });

        kinds = Map.of("pilot", pilot(world), "broken", broken);
        WorkflowAdmission.workflowAdmission(cluster.ledger(CORE_1),
                                            kinds,
                                            () -> "wf-broken")
                         .admit("broken",
                                Map.of(),
                                List.of("node:n9"),
                                T0 - 1)
                         .await()
                         .unwrap();
        admit();
        tick(reconciler(CORE_1));
        assertThat(committed().state()).isEqualTo(WorkflowState.WAITING);
        assertThat(cluster.run("wf-broken").unwrap().state()).isEqualTo(WorkflowState.RUNNING);
    }
}
