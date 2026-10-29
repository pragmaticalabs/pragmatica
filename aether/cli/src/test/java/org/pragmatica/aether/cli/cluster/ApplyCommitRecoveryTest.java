// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.cli.cluster.ApplyCommitRecovery.ClusterConfigView;
import org.pragmatica.aether.cli.cluster.ApplyCommitRecovery.Verb;
import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

/// 4.6: exactly one verb is usable once the cluster can be read, for every place a crash can leave the commit.
class ApplyCommitRecoveryTest {
    private static final ClusterName CLUSTER = ClusterName.clusterName("recovery-test").unwrap();
    private static final long STORED = 5;
    private static final String HASH = "hash-desired";

    private static ApplyState ledgerIn(ApplyPhase phase) {
        var base = ApplyStateFixtures.state(CLUSTER);

        return ApplyState.applyState(base.applyId(), CLUSTER, HASH, STORED, base.startedAt(), phase, List.of(), List.of(), List.of(), List.of(), List.of());
    }

    /// What the cluster says: the three outcomes of the commit request, plus unreadable.
    private static final ClusterConfigView COMMITTED = new ClusterConfigView(HASH, STORED + 1);
    private static final ClusterConfigView UNMOVED = new ClusterConfigView("hash-previous", STORED);
    private static final ClusterConfigView MOVED_BY_OTHER = new ClusterConfigView("hash-other", STORED + 3);

    /// c1 (COMMITTING durable, request not sent), c2 (sent, outcome unknown), c3 (committed, reply lost) and c4 (committed,
    /// COMMITTED write failed) all leave the LEDGER at COMMITTING; c5 (COMMITTED durable) leaves it at COMMITTED. The
    /// cluster's answer is what tells them apart.
    @Test
    void crashAtEachCommittingStep_exactlyOneVerbUsable() {
        var cells = new java.util.LinkedHashMap<String, ApplyCommitRecovery.Decision>();

        for (var step : List.of("c1", "c2", "c3", "c4")) {
            cells.put(step + " committed", ApplyCommitRecovery.decide(ledgerIn(ApplyPhase.COMMITTING), Option.some(COMMITTED)));
            cells.put(step + " unmoved", ApplyCommitRecovery.decide(ledgerIn(ApplyPhase.COMMITTING), Option.some(UNMOVED)));
            cells.put(step + " moved-by-other", ApplyCommitRecovery.decide(ledgerIn(ApplyPhase.COMMITTING), Option.some(MOVED_BY_OTHER)));
        }

        for (var outcome : List.of(COMMITTED, UNMOVED, MOVED_BY_OTHER)) {
            cells.put("c5 " + outcome.configHash(), ApplyCommitRecovery.decide(ledgerIn(ApplyPhase.COMMITTED), Option.some(outcome)));
        }

        cells.forEach((cell, decision) -> assertThat(decision.usable()).as(cell).hasSize(1));
    }

    @Test
    void committedProof_resumeFinalises_rollbackRefused() {
        var decision = ApplyCommitRecovery.decide(ledgerIn(ApplyPhase.COMMITTING), Option.some(COMMITTED));

        assertThat(decision.usable()).containsExactly(Verb.RESUME);
        assertThat(decision.phase()).isEqualTo(ApplyPhase.COMMITTED);
    }

    @Test
    void unmovedConfig_resumeRetriesTheCommit_rollbackWaitsForADefinitiveAnswer() {
        var decision = ApplyCommitRecovery.decide(ledgerIn(ApplyPhase.COMMITTING), Option.some(UNMOVED));

        assertThat(decision.usable()).containsExactly(Verb.RESUME);
        assertThat(decision.phase()).isEqualTo(ApplyPhase.COMMITTING);
        assertThat(decision.explanation()).contains("may still land");
    }

    /// The B1 wedge of draft 2: resume refused on a moved version, the commit retry fenced, rollback refused while
    /// COMMITTING. Moved by another: not committed and never can be, so rollback opens and resume closes.
    @Test
    void committingUnresolved_versionMoved_rollbackAllowed() {
        var decision = ApplyCommitRecovery.decide(ledgerIn(ApplyPhase.COMMITTING), Option.some(MOVED_BY_OTHER));

        assertThat(decision.usable()).containsExactly(Verb.ROLLBACK);
        assertThat(decision.phase()).isEqualTo(ApplyPhase.ABORTED);
        assertThat(decision.explanation()).contains("moved");
    }

    @Test
    void unreadableCluster_noVerbProceeds_andTheLedgerIsNotMoved() {
        var decision = ApplyCommitRecovery.decide(ledgerIn(ApplyPhase.COMMITTING), Option.none());

        assertThat(decision.usable()).isEmpty();
        assertThat(decision.phase()).isEqualTo(ApplyPhase.COMMITTING);
        assertThat(decision.explanation()).contains("cannot determine the commit state").contains("retry");
    }

    @Test
    void definitiveRefusal_movesToAborted() {
        var aborted = ApplyCommitRecovery.afterDefinitiveRefusal(ledgerIn(ApplyPhase.COMMITTING));

        assertThat(aborted.phase()).isEqualTo(ApplyPhase.ABORTED);
        assertThat(ApplyCommitRecovery.decide(aborted, Option.none()).usable()).containsExactly(Verb.ROLLBACK);
    }

    @Test
    void abortedOpensRollbackOnly_whateverTheCluster() {
        for (var view : List.of(Option.some(COMMITTED), Option.some(UNMOVED), Option.<ClusterConfigView> none())) {
            assertThat(ApplyCommitRecovery.decide(ledgerIn(ApplyPhase.ABORTED), view).usable()).containsExactly(Verb.ROLLBACK);
        }
    }

    @Test
    void ordinaryPhases_bothVerbs_exceptRollingBack_whichFinishesWithRollback() {
        assertThat(ApplyCommitRecovery.decide(ledgerIn(ApplyPhase.RUNNING), Option.none()).usable()).isEqualTo(Set.of(Verb.RESUME, Verb.ROLLBACK));
        assertThat(ApplyCommitRecovery.decide(ledgerIn(ApplyPhase.PLANNED), Option.none()).usable()).isEqualTo(Set.of(Verb.RESUME, Verb.ROLLBACK));
        assertThat(ApplyCommitRecovery.decide(ledgerIn(ApplyPhase.ROLLING_BACK), Option.none()).usable()).containsExactly(Verb.ROLLBACK);
    }
}
