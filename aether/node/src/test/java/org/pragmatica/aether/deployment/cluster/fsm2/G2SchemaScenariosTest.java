// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Arrays;
import java.util.List;

import org.pragmatica.aether.deployment.schema.SchemaOrchestratorService;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.kvstore.AetherKey.FsmWarningKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SchemaMigrationLockKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SchemaVersionKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaMigrationLockValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaVersionValue;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.pragmatica.aether.deployment.cluster.fsm2.SlFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;


/// CF-8 (#2759), G2 P4 (schema status writes: S02, S04, S09 and the stall detector) against fsm2.
///
/// **Where the G2 sites live.** S02 (MIGRATING write), S09 (COMPLETED write) and S04 (FAILED write) are issued by
/// `SchemaOrchestratorService` through its own `cluster.apply`; fsm2 never issues a schema status (package-info: no applier) and its
/// only schema write is the rebuild-time reset of a lock-less MIGRATING record to PENDING (`Schemas.resetStalled`). The classification
/// fix (`StatusWriteFailed`), the in-term owed terminals (`settleOwedTerminals`) and the guarded first-issue status Mutations are
/// therefore orchestrator code that the legacy and the new FSM share; they have no fsm2 analogue and are not changed here.
/// What fsm2 owes in their rows is pinned below: it adds no wrong terminal, no second dispatch and no PENDING writer. D.1 = B (the DB-side
/// migration fence, #2591) is what makes the rebuild reset's re-run safe, so a lost terminal is recovered at the next rebuild without a
/// FAILED write.
///
/// **Stall detector (G2 §2.5 point 4, #2773):** `Schemas.sweepStalls` re-derives the condition from what fsm2 holds (the record's status
/// and `updatedAt`, the lock's expiry; the orchestrator's `inFlightMigrations` / `scheduledRetries` are not in the model, and a live lock
/// stands for a live attempt). A failed lock Put abandons the attempt with no retry and no write, which leaves a PENDING record and no
/// lock: that is what the sweep reports. The MIGRATING row no longer says "no safe lever until #2591": the database fence (D.1 = B) makes the
/// rebuild's reset safe to re-run, and the warning names that reset. No in-term writer was added (marked guess, see `Schemas.sweepStalls`).
class G2SchemaScenariosTest {
    private static final BlueprintId OWNER = blueprintId("org.example:app:1.0.0");

    private static SchemaVersionValue schema(SchemaStatus status) {
        return new SchemaVersionValue("ds", 1, "m1", status, "g:a:1", OWNER, 2, FsmHarness.START_WALL);
    }

    private static List<SchemaStatus> writtenStatuses(FsmHarness h) {
        return h.submits(ClusterModel.SubmitKind.SCHEMA)
                .stream()
                .flatMap(submit -> submit.transaction()
                                         .mutations()
                                         .stream())
                .map(mutation -> mutation.replacement()
                                         .map(value -> ((SchemaVersionValue) value).status())
                                         .or(SchemaStatus.UNKNOWN))
                .toList();
    }

    // G2 P4 S02_migratingWriteFails_noFailedWritten
    // Red when: a PENDING record left by a failed MIGRATING write is dispatched again by the tick, or fsm2 writes any schema status
    // for it (FAILED included). Cites D.1 = B: nothing here writes PENDING or runs a script.
    @Test
    void S02_migratingWriteFails_noFailedWritten() {
        var h = threeCores();

        h.activate();
        h.seed(new SchemaVersionKey("ds"), schema(SchemaStatus.PENDING));
        h.drain();
        assertThat(h.effects(ClusterEffect.DispatchMigration.class)).as("control: the PENDING Put dispatched once")
                  .hasSize(1);
        h.advanceBySeconds(120L);
        assertThat(h.effects(ClusterEffect.DispatchMigration.class)).as("the tick does not dispatch the record again")
                  .hasSize(1);
        assertThat(writtenStatuses(h)).as("fsm2 writes no schema status for a PENDING record").isEmpty();
    }

    // G2 P4 S09_completedWriteFails_noFailedWritten_completedReissued and S04_failedWriteLost_failedReissued_noScriptRun
    // Both leave the record MIGRATING with no lock (the terminal write was lost). The orchestrator's in-term re-issue is not fsm2's;
    // fsm2's part is residual and pinned as it is: in-term it writes nothing (never FAILED, never a re-run), and the successor leader's
    // rebuild resets the record to PENDING (guarded on the MIGRATING value), which the DB fence (D.1 = B) makes safe to re-run.
    // Red when: fsm2 writes FAILED or COMPLETED for the record, or the rebuild reset stops being a PENDING write guarded by MIGRATING.
    // A change of the in-term arm means the residual moved: the failure message says so.
    @Test
    void S04_S09_terminalWriteLost_inTermFsm2WritesNothing_rebuildResetsToPending() {
        var h = threeCores();

        h.activate();
        h.seed(new SchemaVersionKey("ds"), schema(SchemaStatus.MIGRATING));
        h.advanceBySeconds(300L);
        assertThat(h.submits(ClusterModel.SubmitKind.SCHEMA)).as("TRIPWIRE: the in-term residual moved (fsm2 now writes for a lock-less MIGRATING record)")
                  .isEmpty();
        assertThat(h.effects(ClusterEffect.DispatchMigration.class)).as("no script run in-term").isEmpty();
        var successor = threeCores();

        successor.seedQuietly(new SchemaVersionKey("ds"), schema(SchemaStatus.MIGRATING));
        successor.activate();
        assertThat(writtenStatuses(successor)).as("the rebuild writes PENDING only, never FAILED or COMPLETED")
                  .containsExactly(SchemaStatus.PENDING);
        var mutation = successor.submits(ClusterModel.SubmitKind.SCHEMA)
                                .getFirst()
                                .transaction()
                                .mutations()
                                .getFirst();

        assertThat(mutation.expected().map(value -> ((SchemaVersionValue) value).status()).or(SchemaStatus.UNKNOWN)).as("guarded on the MIGRATING value")
                  .isEqualTo(SchemaStatus.MIGRATING);
        assertThat(successor.effects(ClusterEffect.DispatchMigration.class)).as("the PENDING reset re-fires the dispatch once")
                  .hasSize(1);
    }

    // G2 P4 lateMigratingPut_overCompleted_refused
    // The refusal is the orchestrator's guarded Mutation (expected = the value read), enforced by the store's validMutation; fsm2 mirrors the
    // committed store. Its part: a MIGRATING record that is nevertheless committed over COMPLETED causes no live action (no write, no dispatch,
    // the ACTIVE slice stays ACTIVE); the rebuild resets it, and the DB fence (D.1 = B) makes the re-run a no-op on the migrated database.
    // Red when: a live MIGRATING Put over COMPLETED makes fsm2 write, dispatch or unload anything.
    @Test
    void lateMigratingPut_overCompleted_fsm2TakesNoLiveAction() {
        var h = threeCores();

        h.activate();
        h.seed(new SchemaVersionKey("ds"), schema(SchemaStatus.COMPLETED));
        h.drain();
        h.seed(new SchemaVersionKey("ds"), schema(SchemaStatus.MIGRATING));
        h.advanceBySeconds(120L);
        assertThat(h.submits(ClusterModel.SubmitKind.SCHEMA)).isEmpty();
        assertThat(h.effects(ClusterEffect.DispatchMigration.class)).isEmpty();
        assertThat(h.warns("schema-hold")).isEmpty();
    }

    // G2 P4 pendingLockPutFailed_stallWarningRaised_noDispatch (#2773)
    // A PENDING record whose dispatch went nowhere (the orchestrator's lock Put failed: that abandons the attempt with no retry and no
    // status write) is reported by the leader's stall sweep, and the warning dispatches and writes nothing.
    // Red when: the sweep is absent or never fires (no warning), or it dispatches again or writes a schema status.
    @Test
    void pendingLockPutFailed_stallWarningRaised_noDispatch() {
        var h = threeCores();

        h.activate();
        h.seed(new SchemaVersionKey("ds"), schema(SchemaStatus.PENDING));
        h.advanceBySeconds(600L);
        assertThat(h.warns("schema-migration-stalled")).hasSize(1);
        assertThat(h.warns("schema-migration-stalled").getFirst().subject()).isEqualTo("ds");
        assertThat(h.effects(ClusterEffect.DispatchMigration.class)).as("the warning dispatches nothing").hasSize(1);
        assertThat(h.submits(ClusterModel.SubmitKind.SCHEMA)).as("and writes no schema status").isEmpty();
        assertThat(h.warningPuts()).as("the open warning is persisted (#2834)")
                  .contains(new FsmWarningKey("schema-migration-stalled", "ds"));
    }

    // G2 P4 stallWarning_namesRetryOnlyWhenTheRouteAcceptsTheStatus (#2773, M15), over every SchemaStatus value
    // Red when: a status other than PENDING or MIGRATING is reported, PENDING does not name `aether schema retry`, or MIGRATING names it (the
    // route refuses MIGRATING: the lever would be a lie).
    @Test
    void stallWarning_namesRetryOnlyWhenTheRouteAcceptsTheStatus() {
        for (var status : SchemaStatus.values()) {
            var h = threeCores();

            h.activate();
            h.seed(new SchemaVersionKey("ds"), schema(status));
            h.advanceBySeconds(600L);
            var warns = h.warns("schema-migration-stalled");

            switch (status) {
                case PENDING -> {
                    assertThat(warns).as("PENDING").hasSize(1);
                    assertThat(warns.getFirst().message()).contains("status PENDING", "aether schema retry ds");
                }
                case MIGRATING -> {
                    assertThat(warns).as("MIGRATING").hasSize(1);
                    assertThat(warns.getFirst().message()).contains("status MIGRATING", "rebuild resets the record to PENDING")
                              .doesNotContain("aether schema retry");
                }
                default -> assertThat(warns).as(status.name()).isEmpty();
            }
        }
    }

    // #2773: the predicate both the retry route and the warning ask. Red when: the accepted set moves.
    @Test
    void canRetryManually_acceptsFailedAndPendingOnly() {
        assertThat(Arrays.stream(SchemaStatus.values()).filter(SchemaStatus::canRetryManually).toList())
                  .containsExactlyInAnyOrder(SchemaStatus.FAILED, SchemaStatus.PENDING);
    }

    // #2773: the sweep waits out the orchestrator's longest legitimate no-lock dwell (a scheduled retry backoff) plus two ticks.
    // Red when: the threshold drops below the ceiling (a retry waiting out its backoff is reported as stalled) or grows past it.
    @Test
    void stall_waitsOutTheOrchestratorBackoff_thenReports() {
        var h = threeCores();

        h.activate();
        h.seed(new SchemaVersionKey("ds"), schema(SchemaStatus.PENDING));
        h.advanceBySeconds(90L);
        assertThat(h.warns("schema-migration-stalled")).as("90 s after the write: inside 45 s + 2 ticks").isEmpty();
        h.advanceBySeconds(60L);
        assertThat(h.warns("schema-migration-stalled")).as("150 s: past it").hasSize(1);
    }

    // #2773: the ceiling the sweep waits out IS the orchestrator's: the fenced-in-the-database cap and the largest transient retry backoff.
    // Red when: either orchestrator constant moves without the sweep.
    @Test
    void stall_ceiling_equalsTheOrchestratorBackoffs() {
        assertThat(Schemas.RETRY_CEILING_MS).isEqualTo(SchemaOrchestratorService.HELD_BACKOFF_CAP_MS)
                  .isEqualTo(SchemaOrchestratorService.calculateBackoff(SchemaOrchestratorService.MAX_RETRIES - 1));
    }

    // #2773: a live lock is a live attempt (the holder renews it for as long as it runs), so a long migration is never reported.
    // Red when: the lock is not consulted.
    @Test
    void stall_liveLock_isNotReported() {
        for (var status : List.of(SchemaStatus.PENDING, SchemaStatus.MIGRATING)) {
            var h = threeCores();

            h.activate();
            h.seed(SchemaMigrationLockKey.schemaMigrationLockKey("ds"), liveLock());
            h.seed(new SchemaVersionKey("ds"), schema(status));
            h.advanceBySeconds(600L);
            assertThat(h.warns("schema-migration-stalled")).as(status.name()).isEmpty();
        }
    }

    // #2773: a retry (a fresh write) or any terminal status ends the episode: one recovery, then silence.
    // Red when: the open set is never closed, or the recovery is raised twice.
    @Test
    void stall_endsWhenTheRecordMoves_recoveryRaisedOnce() {
        var h = threeCores();

        h.activate();
        h.seed(new SchemaVersionKey("ds"), schema(SchemaStatus.PENDING));
        h.advanceBySeconds(600L);
        assertThat(h.warns("schema-migration-progressed")).isEmpty();
        h.seed(new SchemaVersionKey("ds"), schema(SchemaStatus.COMPLETED));
        h.advanceBySeconds(60L);
        assertThat(h.warns("schema-migration-progressed")).hasSize(1);
        assertThat(h.warns("schema-migration-progressed").getFirst().subject()).isEqualTo("ds");
        h.advanceBySeconds(600L);
        assertThat(h.warns("schema-migration-progressed")).hasSize(1);
        assertThat(h.warns("schema-migration-stalled")).hasSize(1);
    }

    // #2773: an episode is raised once however many passes see it; the removal of the record closes it with subject-gone (D.6 row 17 shape).
    // Red when: the report repeats every pass, or the removal leaves it open.
    @Test
    void stall_recordRemoved_closesWithSubjectGone() {
        var h = threeCores();

        h.activate();
        h.seed(new SchemaVersionKey("ds"), schema(SchemaStatus.MIGRATING));
        h.advanceBySeconds(600L);
        assertThat(h.warns("schema-migration-stalled")).as("once per episode").hasSize(1);
        h.seedRemove(new SchemaVersionKey("ds"));
        h.drain();
        assertThat(h.warns("schema-migration-progressed")).hasSize(1);
        assertThat(h.warns("schema-migration-progressed").getFirst().message()).contains("subject-gone");
    }

    // #2773 with #2834/#3078: the open warning is committed and the successor leader inherits it: it is not raised again while the stall
    // lasts, and it is closed when the record moves while no leader watched.
    // Red when: the family is not in the committed open set (the successor raises it a second time, or never closes it).
    @Test
    void stall_survivesLeaderLoss_inheritedNotRaisedAgain_closedBySuccessor() {
        var h = threeCores();

        h.activate();
        h.seed(new SchemaVersionKey("ds"), schema(SchemaStatus.PENDING));
        h.advanceBySeconds(600L);
        assertThat(h.warns("schema-migration-stalled")).hasSize(1);
        h.deliver(new ClusterInput.Deactivate());
        h.commitLeader(FsmHarness.SELF, 2L);
        h.drain();
        h.activate();
        h.advanceBySeconds(90L);
        assertThat(h.warns("schema-migration-stalled")).as("inherited, not raised again").hasSize(1);
        assertThat(h.warns("schema-migration-progressed")).isEmpty();
        h.seed(new SchemaVersionKey("ds"), schema(SchemaStatus.COMPLETED));
        h.advanceBySeconds(60L);
        assertThat(h.warns("schema-migration-progressed")).as("closed by the successor").hasSize(1);
    }

    private static SchemaMigrationLockValue liveLock() {
        return new SchemaMigrationLockValue("ds", FsmHarness.SELF, FsmHarness.START_WALL, FsmHarness.START_WALL + 3_600_000L, 1L);
    }
}
