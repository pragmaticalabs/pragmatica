// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Set;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.kvstore.AetherValue.VersionRoutingValue;
import org.pragmatica.lang.Option;

import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentApplyValue;
import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.pragmatica.aether.deployment.cluster.fsm2.SlFixtures.target;
import static org.assertj.core.api.Assertions.assertThat;


/// Comparisons and guards of `Blueprints` that the mutation battery left unpinned (lane fsmf1). Each test names the line whose operator it holds.
class BlueprintsSurvivorPinsTest {
    private static final Artifact ORDERS_V3 = artifact("org.example:orders:3.0.0");

    private static ClusterModel withOpenWait() {
        var m = withoutBlueprint();
        var value = blueprint("org.example:app:1.0.0", false, "a1", slice(ORDERS, 1, 1, Set.of()));

        Indexer.indexBlueprint(m, value.blueprint().id(), value);

        return m;
    }

    private static ClusterModel withoutBlueprint() {
        var m = new ClusterModel();

        // The pending put names its owner, as every declaration's put does (`Blueprints.declaredTarget`): the watch re-sends only while
        // the owner is published (CF-5).
        m.pendingTargetPuts = m.pendingTargetPuts.put(ORDERS.base(),
                                                      SlFixtures.ownedTarget(ORDERS, 1, 1, blueprintId("org.example:app:1.0.0")));

        return m;
    }

    private static long bound() {
        return SurvivorPinSupport.settings().timing().awaitedTarget();
    }

    /// Red when: `routingNames` needs both versions to match (`&&` instead of `||`): during a rolling update the old version alone, or the new one alone, is permitted.
    @Test
    void permits_namesEitherRoutedVersion_duringRollingUpdate() {
        var m = new ClusterModel();

        m.targets = m.targets.put(ORDERS.base(), target(ORDERS_V3, 1, 1, "CORE_ONLY"));
        m.routings = m.routings.put(ORDERS.base(), new VersionRoutingValue(ORDERS.version(), ORDERS_V2.version(), 1, 1, 0L));

        assertThat(Blueprints.permits(m, ORDERS)).as("the old version of the routing").isTrue();
        assertThat(Blueprints.permits(m, ORDERS_V2)).as("the new version of the routing").isTrue();
        assertThat(Blueprints.permits(m, ORDERS_V3)).as("the committed version").isTrue();
        assertThat(Blueprints.permits(m, artifact("org.example:orders:4.0.0"))).as("control: a version no row names").isFalse();
    }

    /// Red when: the watch ends only when the target is committed AND the blueprint is gone (`&&` instead of `||`): a committed target
    /// whose blueprint is still desired keeps its pending put.
    @Test
    void sweepAwaited_forgetsThePut_whenTheTargetCommitted() {
        var m = withOpenWait();

        m.targets = m.targets.put(ORDERS.base(), target(ORDERS, 1, 1, "CORE_ONLY"));
        Blueprints.sweepAwaited(m, SurvivorPinSupport.ctx(1_000L, 0L));

        assertThat(m.pendingTargetPuts.containsKey(ORDERS.base())).isFalse();
    }

    /// Red when: the same operator keeps a put whose blueprint is gone while the target never committed.
    @Test
    void sweepAwaited_forgetsThePut_whenTheBlueprintIsGone() {
        var m = withoutBlueprint();

        Blueprints.sweepAwaited(m, SurvivorPinSupport.ctx(1_000L, 0L));

        assertThat(m.pendingTargetPuts.containsKey(ORDERS.base())).isFalse();
    }

    /// Red when: the wait is due only past the bound (`>` instead of `>=`): at exactly the bound the operator is told.
    @Test
    void sweepAwaited_warnsAtExactlyTheBound() {
        assertThat(warnsAt(1_000L + bound())).as("at the bound").isTrue();
        assertThat(warnsAt(1_000L + bound() - 1)).as("control: one ms before it").isFalse();
    }

    private static boolean warnsAt(long nowMs) {
        var m = withOpenWait();
        var ctx = SurvivorPinSupport.ctx(nowMs, 0L);

        m.awaitedTargets = m.awaitedTargets.put(ORDERS, new ClusterModel.AwaitedTarget(1_000L, false, 1_000L));
        Blueprints.sweepAwaited(m, ctx);

        return ctx.effects()
                  .stream()
                  .anyMatch(effect -> effect instanceof ClusterEffect.Warn warn && warn.code().equals(Blueprints.AWAITING_TARGET));
    }

    /// Red when: the warned flag is `existing.warned() && warn` (`||` -> `&&`): the first warning is never remembered, so it repeats every
    /// sweep, and an already-warned wait forgets it has warned.
    @Test
    void sweepAwaited_remembersTheWarning_andKeepsItOnce() {
        var first = withOpenWait();
        var ctx = SurvivorPinSupport.ctx(1_000L + bound() + 1, 0L);

        first.awaitedTargets = first.awaitedTargets.put(ORDERS, new ClusterModel.AwaitedTarget(1_000L, false, 1_000L));
        Blueprints.sweepAwaited(first, ctx);
        assertThat(first.awaitedTargets.get(ORDERS).map(w -> w.warned())).as("warned now").isEqualTo(Option.some(true));

        var warned = withOpenWait();
        var later = SurvivorPinSupport.ctx(1_000L + 2 * bound(), 0L);

        warned.awaitedTargets = warned.awaitedTargets.put(ORDERS, new ClusterModel.AwaitedTarget(1_000L, true, 1_000L));
        Blueprints.sweepAwaited(warned, later);
        assertThat(warned.awaitedTargets.get(ORDERS).map(w -> w.warned())).as("an earlier warning is kept").isEqualTo(Option.some(true));
        assertThat(later.effects()).as("and is not repeated").noneMatch(effect -> effect instanceof ClusterEffect.Warn);
    }

    private static int resubmissionsAt(long nowMs) {
        var m = withOpenWait();

        m.awaitedTargets = m.awaitedTargets.put(ORDERS, new ClusterModel.AwaitedTarget(0L, true, 1_000L));
        Blueprints.sweepAwaited(m, SurvivorPinSupport.ctx(nowMs, 0L));

        return m.outbox.size();
    }

    /// Red when: a wait is re-sent only once the last re-send is older than the bound (`>` instead of `>=`): at exactly the bound it is not.
    @Test
    void sweepAwaited_resubmitsThePut_atExactlyTheBoundSinceTheLastResubmit() {
        assertThat(resubmissionsAt(1_000L + bound())).as("at the bound").isEqualTo(1);
        assertThat(resubmissionsAt(1_000L + bound() - 1)).as("control: one ms before it").isZero();
    }

    /// CF-5 (#2759). Red when: a pending put whose owner blueprint is no longer published is re-sent because another blueprint still
    /// lists the artifact: its witness would expect the owner absent, so it could commit as an ownerless target.
    @Test
    void sweepAwaited_aPutWhoseOwnerIsUnpublished_isNotResent_andTheWaitEnds() {
        var m = withoutBlueprint();
        var other = blueprint("org.example:app:2.0.0", false, "a2", slice(ORDERS, 1, 1, Set.of()));

        Indexer.indexBlueprint(m, other.blueprint().id(), other);
        m.awaitedTargets = m.awaitedTargets.put(ORDERS, new ClusterModel.AwaitedTarget(0L, true, 1_000L));
        Blueprints.sweepAwaited(m, SurvivorPinSupport.ctx(1_000L + bound(), 0L));

        assertThat(m.blueprintsByArtifact.containsKey(ORDERS)).as("control: another blueprint lists the artifact").isTrue();
        assertThat(m.outbox).as("not re-sent").isEmpty();
        assertThat(m.pendingTargetPuts.containsKey(ORDERS.base())).as("no longer watched").isFalse();
    }

    private static Option<Long> redriveStampAt(long monoNs) {
        var m = new ClusterModel();
        var value = blueprint("org.example:app:1.0.0", false, "a1", slice(ORDERS, 1, 1, Set.of()));
        var id = value.blueprint().id();

        Indexer.indexBlueprint(m, id, value);
        Indexer.indexApply(m, id, new DeploymentApplyValue("a1", Option.none(), 0L));
        m.redriveAt = m.redriveAt.put(id, 0L);
        Blueprints.redrive(m, SurvivorPinSupport.ctx(1_000L, monoNs));

        return m.redriveAt.get(id);
    }

    /// Red when: the re-drive is throttled while the last one is no older than the bound (`<=` instead of `<`): at exactly the bound it waits.
    @Test
    void redrive_redeclaresTheBatch_atExactlyTheBoundSinceTheLastRedrive() {
        var boundNs = bound() * 1_000_000L;

        assertThat(redriveStampAt(boundNs)).as("at the bound").isEqualTo(Option.some(boundNs));
        assertThat(redriveStampAt(boundNs - 1)).as("control: one ns before it").isEqualTo(Option.some(0L));
    }
}
