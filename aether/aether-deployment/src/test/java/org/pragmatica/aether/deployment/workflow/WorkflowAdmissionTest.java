// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.workflow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.workflow.WorkflowAdmission.AdmissionRefusal;
import org.pragmatica.aether.slice.kvstore.AetherValue.WorkflowClaimValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.WorkflowRunValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.WorkflowState;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.workflow.WorkflowTestCluster.CORE_1;
import static org.pragmatica.aether.deployment.workflow.WorkflowTestCluster.CORE_2;

/// rc6 §4.3: admission is the one fence. Every case runs against a real [org.pragmatica.cluster.state.kvstore.KVStore] applier, so
/// "refused" means the applier refused the transaction, not that the code declined to send it.
class WorkflowAdmissionTest {
    private static final WorkflowKind<Unit0> PILOT = new WorkflowKind<>("pilot",
                                                                        new WorkflowBudget(3,
                                                                                           TimeSpan.timeSpan(60)
                                                                                                   .seconds(),
                                                                                           5),
                                                                        _ -> Option.none(),
                                                                        () -> Unit0.INSTANCE);

    enum Unit0 {
        INSTANCE
    }

    private WorkflowTestCluster cluster;
    private WorkflowLedger ledger;
    private WorkflowAdmission admission;
    private AtomicInteger ids;

    @BeforeEach
    void setUp() {
        cluster = new WorkflowTestCluster();
        ledger = cluster.ledger(CORE_1);
        ids = new AtomicInteger();
        admission = admission(ledger);
    }

    private WorkflowAdmission admission(WorkflowLedger over) {
        return WorkflowAdmission.workflowAdmission(over,
                                                   Map.of("pilot", PILOT),
                                                   () -> "wf-" + ids.incrementAndGet());
    }

    private Result<WorkflowRunValue> admit(List<String> keys) {
        return admission.admit("pilot", Map.of("node", "n1"), keys, 1_000L)
                        .await();
    }

    private static Cause refusal(Result<?> result) {
        return result.fold(cause -> cause, value -> {
            throw new AssertionError("expected a refusal, got " + value);
        });
    }

    @Test
    void admit_createsTheRunAndClaimsEveryKey_inOneTransaction() {
        var run = admit(List.of("node:n1", "slice:a")).unwrap();

        assertThat(run.id()).isEqualTo("wf-1");
        assertThat(run.state()).isEqualTo(WorkflowState.RUNNING);
        assertThat(run.deadline()).as("deadline = now + the kind's elapsed budget").isEqualTo(61_000L);
        assertThat(cluster.run("wf-1")).isEqualTo(Option.some(run));
        assertThat(cluster.claim("node:n1")).isEqualTo(Option.some(new WorkflowClaimValue("wf-1")));
        assertThat(cluster.claim("slice:a")).isEqualTo(Option.some(new WorkflowClaimValue("wf-1")));
    }

    /// The overlap is the LAST key of the second admission.
    @Test
    void admit_overlappingOnItsLastKey_isRefusedWhole_namingTheHolder() {
        admit(List.of("node:n1", "node:n2")).unwrap();

        var refused = refusal(admit(List.of("node:n3", "node:n2")));

        assertThat(refused).isEqualTo(new AdmissionRefusal.ConflictKeyHeld("node:n2", "wf-1"));
        assertThat(refused.message()).contains("node:n2", "wf-1");
        assertThat(cluster.run("wf-2")).as("the refused run is not created").isEqualTo(Option.none());
        assertThat(cluster.claim("node:n3")).as("all-or-nothing: the free key is not claimed either").isEqualTo(Option.none());
        assertThat(cluster.claim("node:n2")).isEqualTo(Option.some(new WorkflowClaimValue("wf-1")));
    }

    /// The overlap is the FIRST key of the second admission.
    @Test
    void admit_overlappingOnItsFirstKey_isRefusedWhole_namingTheHolder() {
        admit(List.of("node:n1", "node:n2")).unwrap();

        assertThat(refusal(admit(List.of("node:n1", "node:n4")))).isEqualTo(new AdmissionRefusal.ConflictKeyHeld("node:n1", "wf-1"));
        assertThat(cluster.claim("node:n4")).isEqualTo(Option.none());
        assertThat(cluster.claim("node:n1")).isEqualTo(Option.some(new WorkflowClaimValue("wf-1")));
    }

    @Test
    void admit_disjointKeys_admitsBoth() {
        admit(List.of("node:n1")).unwrap();
        admit(List.of("node:n2")).unwrap();

        assertThat(cluster.claim("node:n1")).isEqualTo(Option.some(new WorkflowClaimValue("wf-1")));
        assertThat(cluster.claim("node:n2")).isEqualTo(Option.some(new WorkflowClaimValue("wf-2")));
    }

    /// Two admissions racing on overlapping keys, from two threads released together, 200 rounds: exactly one is admitted and the
    /// other is refused with the WINNER's id, every round.
    @Test
    void admit_concurrentOverlappingAdmissions_exactlyOneWins_theOtherNamesTheWinner() throws Exception {
        for (int round = 0; round < 200; round++) {
            setUp();
            var barrier = new CyclicBarrier(2);
            var results = new ArrayList<Result<WorkflowRunValue>>();
            var first = racer(barrier, List.of("node:a", "node:shared"), results);
            var second = racer(barrier, List.of("node:shared", "node:b"), results);

            first.join();
            second.join();

            var won = results.stream().filter(Result::isSuccess).toList();
            var lost = results.stream().filter(Result::isFailure).toList();

            assertThat(won).as("round " + round).hasSize(1);
            assertThat(lost).as("round " + round).hasSize(1);

            var winner = won.getFirst().unwrap();

            assertThat(refusal(lost.getFirst())).as("round " + round)
                                                .isEqualTo(new AdmissionRefusal.ConflictKeyHeld("node:shared", winner.id()));
            assertThat(cluster.claim("node:shared")).isEqualTo(Option.some(new WorkflowClaimValue(winner.id())));
            assertThat(cluster.claim("node:a").isPresent() ^ cluster.claim("node:b").isPresent())
                .as("round " + round + ": only the winner's private key is claimed")
                .isTrue();
        }
    }

    private Thread racer(CyclicBarrier barrier, List<String> keys, List<Result<WorkflowRunValue>> results) {
        return Thread.ofPlatform()
                     .start(() -> race(barrier, keys, results));
    }

    private void race(CyclicBarrier barrier, List<String> keys, List<Result<WorkflowRunValue>> results) {
        try {
            barrier.await();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        var result = admit(keys);

        synchronized (results) {
            results.add(result);
        }
    }

    @Test
    void commit_ofATerminalState_releasesEveryClaim_inTheSameTransaction() {
        var run = admit(List.of("node:n1", "slice:a")).unwrap();
        var done = run.in(WorkflowState.DONE, "observed");

        assertThat(ledger.commit(run, done).await().unwrap()).isTrue();
        assertThat(cluster.run(run.id())).isEqualTo(Option.some(done));
        assertThat(cluster.claim("node:n1")).isEqualTo(Option.none());
        assertThat(cluster.claim("slice:a")).isEqualTo(Option.none());
        assertThat(admit(List.of("node:n1")).isSuccess()).as("a released key is admissible again").isTrue();
    }

    @Test
    void commit_ofFailed_releasesClaims() {
        var run = admit(List.of("node:n1")).unwrap();

        assertThat(ledger.commit(run, run.in(WorkflowState.FAILED, "abandoned")).await().unwrap()).isTrue();
        assertThat(cluster.claim("node:n1")).isEqualTo(Option.none());
    }

    @Test
    void commit_ofEscalatedOrWaiting_keepsTheClaims() {
        var run = admit(List.of("node:n1")).unwrap();
        var waiting = run.issued("post", 2_000L);
        var escalated = waiting.in(WorkflowState.ESCALATED, "incident");

        assertThat(ledger.commit(run, waiting).await().unwrap()).isTrue();
        assertThat(ledger.commit(waiting, escalated).await().unwrap()).isTrue();
        assertThat(cluster.claim("node:n1")).isEqualTo(Option.some(new WorkflowClaimValue(run.id())));
        assertThat(refusal(admit(List.of("node:n1")))).isEqualTo(new AdmissionRefusal.ConflictKeyHeld("node:n1", run.id()));
    }

    @Test
    void commit_onAStaleRun_isRefused_andChangesNothing() {
        var run = admit(List.of("node:n1")).unwrap();
        var waiting = run.issued("post", 2_000L);

        assertThat(ledger.commit(run, waiting).await().unwrap()).isTrue();
        assertThat(ledger.commit(run, run.in(WorkflowState.DONE, "stale")).await().unwrap())
            .as("compare-and-set on the exact run read")
            .isFalse();
        assertThat(cluster.run(run.id())).isEqualTo(Option.some(waiting));
        assertThat(cluster.claim("node:n1")).as("a refused terminal commit releases nothing").isPresent();
    }

    @Test
    void admit_refusesAnUnknownKind() {
        var refused = admission.admit("nope", Map.of(), List.of("node:n1"), 1L).await();

        assertThat(refusal(refused)).isEqualTo(new AdmissionRefusal.UnknownKind("nope"));
        assertThat(cluster.claim("node:n1")).isEqualTo(Option.none());
    }

    @Test
    void admit_refusesNoBlankOrDuplicateKeys() {
        assertThat(refusal(admit(List.of()))).isInstanceOf(AdmissionRefusal.InvalidConflictKeys.class);
        assertThat(refusal(admit(List.of("node:n1", " ")))).isInstanceOf(AdmissionRefusal.InvalidConflictKeys.class);
        assertThat(refusal(admit(List.of("node:n1", "node:n1")))).isInstanceOf(AdmissionRefusal.InvalidConflictKeys.class);
        assertThat(cluster.claim("node:n1")).isEqualTo(Option.none());
    }

    @Test
    void admit_onANodeThatIsNotTheCommittedLeader_isRefused() {
        var follower = admission(cluster.ledger(CORE_2));

        assertThat(refusal(follower.admit("pilot", Map.of(), List.of("node:n1"), 1L).await())).isEqualTo(AdmissionRefusal.NOT_LEADER);
        assertThat(cluster.claim("node:n1")).isEqualTo(Option.none());
    }

    @Test
    void admit_afterLeadershipMoved_isRefusedByTheOldLeader_andAdmittedByTheNew() {
        cluster.lead(CORE_2);

        assertThat(refusal(admit(List.of("node:n1")))).isEqualTo(AdmissionRefusal.NOT_LEADER);
        assertThat(admission(cluster.ledger(CORE_2)).admit("pilot", Map.of(), List.of("node:n1"), 1L).await().isSuccess()).isTrue();
    }
}
