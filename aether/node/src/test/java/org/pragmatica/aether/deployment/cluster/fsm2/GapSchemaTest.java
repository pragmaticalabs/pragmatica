// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey.SchemaMigrationLockKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SchemaVersionKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaMigrationLockValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaVersionValue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.pragmatica.aether.deployment.cluster.fsm2.SlFixtures.*;

/// Rebuild-time schema recovery (C1, B-042): a PENDING record whose Put landed before the FSM was Active is dispatched again,
/// and a MIGRATING record whose lock is absent or expired is reset to PENDING, which re-fires the live arm.
class GapSchemaTest {
    private static final org.pragmatica.aether.slice.blueprint.BlueprintId OWNER = blueprintId("org.example:app:1.0.0");

    private static SchemaVersionValue schema(String datasource, SchemaStatus status) {
        return new SchemaVersionValue(datasource, 1, "m1", status, "g:a:1", OWNER, 2, FsmHarness.START_WALL);
    }

    /// Red when: a PENDING record found at rebuild is not dispatched to the orchestrator.
    @Test
    void pendingAtRebuild_isDispatched() {
        var h = threeCores();

        h.seedQuietly(new SchemaVersionKey("pending-ds"), schema("pending-ds", SchemaStatus.PENDING));
        h.seedQuietly(new SchemaVersionKey("done-ds"), schema("done-ds", SchemaStatus.COMPLETED));
        h.activate();

        assertThat(h.effects(ClusterEffect.DispatchMigration.class)).extracting(ClusterEffect.DispatchMigration::datasource).containsExactly("pending-ds");
    }

    /// Red when: a MIGRATING record whose lock is gone is not reset to PENDING in one transaction expecting the committed value,
    /// or the reset does not re-fire the dispatch.
    @Test
    void migratingWithNoLock_isResetToPending_andRedispatched() {
        var h = threeCores();

        h.seedQuietly(new SchemaVersionKey("ds"), schema("ds", SchemaStatus.MIGRATING));
        h.activate();

        var resets = h.submits(ClusterModel.SubmitKind.SCHEMA);

        assertThat(resets).hasSize(1);
        var mutation = resets.getFirst().transaction().mutations().getFirst();

        assertThat(mutation.expected().map(value -> ((SchemaVersionValue) value).status())).isEqualTo(org.pragmatica.lang.Option.some(SchemaStatus.MIGRATING));
        assertThat(mutation.replacement().map(value -> ((SchemaVersionValue) value).status())).isEqualTo(org.pragmatica.lang.Option.some(SchemaStatus.PENDING));
        assertThat(h.effects(ClusterEffect.DispatchMigration.class)).as("the reset Put is the PENDING arm").extracting(ClusterEffect.DispatchMigration::datasource).containsExactly("ds");
    }

    /// Red when: a MIGRATING record with a live lock is reset (a running migration is disturbed), or one whose lock expired one
    /// millisecond ago is left (the boundary).
    @Test
    void migrating_isResetOnlyWhenItsLockExpired() {
        var live = threeCores();

        live.seedQuietly(new SchemaVersionKey("ds"), schema("ds", SchemaStatus.MIGRATING));
        live.seedQuietly(new SchemaMigrationLockKey("ds"), new SchemaMigrationLockValue("ds", CORE_1, FsmHarness.START_WALL - 10L, FsmHarness.START_WALL + 1L, 1L));
        live.activate();
        assertThat(live.submits(ClusterModel.SubmitKind.SCHEMA)).as("the lock is alive until after now").isEmpty();

        var expired = threeCores();

        expired.seedQuietly(new SchemaVersionKey("ds"), schema("ds", SchemaStatus.MIGRATING));
        expired.seedQuietly(new SchemaMigrationLockKey("ds"), new SchemaMigrationLockValue("ds", CORE_1, FsmHarness.START_WALL - 10L, FsmHarness.START_WALL - 1L, 1L));
        expired.activate();
        assertThat(expired.submits(ClusterModel.SubmitKind.SCHEMA)).as("the lock expired a millisecond ago").hasSize(1);
    }

    /// Red when: the same hold is warned about twice (the dedup of #760), or its release is not announced once.
    @Test
    void schemaHold_warnsOncePerSignature_andReleaseIsAnnounced() {
        var h = threeCores();
        var owner = blueprintId("org.example:app:1.0.0");

        h.seedQuietly(new SchemaVersionKey("ds"), schema("ds", SchemaStatus.PENDING));
        h.seedQuietly(new org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey(ORDERS.base()), ownedTarget(ORDERS, 1, 1, owner));
        h.seedQuietly(new org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey(owner), blueprint("org.example:app:1.0.0", false, ATTEMPT, slice(ORDERS, 1, 1, java.util.Set.of())));
        h.activate();
        node(h, CORE_1, 11L).follows(ORDERS);
        h.drain();
        assertThat(h.warns("schema-hold")).as("held in LOADED by the pending migration").hasSize(1);

        h.seed(new SchemaVersionKey("other"), schema("other", SchemaStatus.COMPLETED));
        h.drain();
        assertThat(h.warns("schema-hold")).as("another datasource completing re-evaluates the slice; an unchanged hold is not warned again").hasSize(1);

        h.seed(new SchemaVersionKey("ds"), schema("ds", SchemaStatus.COMPLETED));
        h.drain();
        assertThat(h.warns("schema-hold-cleared")).hasSize(1);
        assertThat(goalOf(h, CORE_1, ORDERS)).isEqualTo(org.pragmatica.lang.Option.some(org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal.ACTIVE));
    }
}
