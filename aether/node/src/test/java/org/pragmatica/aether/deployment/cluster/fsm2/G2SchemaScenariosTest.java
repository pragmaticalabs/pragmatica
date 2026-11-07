// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.List;

import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.kvstore.AetherKey.SchemaVersionKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaVersionValue;

import org.junit.jupiter.api.Disabled;
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
/// **Not ported (stall detector, G2 §2.5 point 4):** `pendingLockPutFailed_stallWarningRaised_noDispatch` and
/// `stallWarning_namesRetryOnlyWhenTheRouteAcceptsTheStatus` need a fsm2 reconcile step that fsm2 does not have, and its condition
/// reads the local orchestrator's `inFlightMigrations` / `scheduledRetries`, which are not in the fsm2 model. The MIGRATING row of the
/// warning ("no safe operator lever until #2591") also contradicts D.1 = B (automatic MIGRATING recovery). Left as an enabled tripwire
/// plus a `@Disabled` assertion below.
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

    // G2 P4 pendingLockPutFailed_stallWarningRaised_noDispatch (stall detector: no fsm2 analogue yet)
    // Enabled tripwire: a PENDING record whose dispatch went nowhere (lock Put failed in the orchestrator) is never reported by fsm2.
    // The real assertion is disabled beside it; it needs the detector and an orchestrator-busy seam (see the class comment).
    @Test
    void pendingLockPutFailed_noStallDetectorYet_tripwire() {
        var h = threeCores();

        h.activate();
        h.seed(new SchemaVersionKey("ds"), schema(SchemaStatus.PENDING));
        h.advanceBySeconds(600L);
        assertThat(h.warns("schema-migration-stalled")).as("TRIPWIRE: fsm2 gained a schema stall detector; enable pendingLockPutFailed_stallWarningRaised_noDispatch")
                  .isEmpty();
        assertThat(h.effects(ClusterEffect.DispatchMigration.class)).as("and it dispatches nothing").hasSize(1);
    }

    @Disabled("G2 §2.5 point 4: needs the fsm2 stall detector and an orchestrator-busy seam; see the class comment")
    @Test
    void pendingLockPutFailed_stallWarningRaised_noDispatch() {
        var h = threeCores();

        h.activate();
        h.seed(new SchemaVersionKey("ds"), schema(SchemaStatus.PENDING));
        h.advanceBySeconds(600L);
        assertThat(h.warns("schema-migration-stalled")).hasSize(1);
        assertThat(h.effects(ClusterEffect.DispatchMigration.class)).as("the warning dispatches nothing").hasSize(1);
    }
}
