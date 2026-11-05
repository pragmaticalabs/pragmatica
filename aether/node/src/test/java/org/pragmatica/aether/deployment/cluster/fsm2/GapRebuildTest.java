// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ArtifactJudgmentKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.CommunityKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentApplyKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentOutcomeKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.GovernorAnnouncementKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeDepartureKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodePlacementKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeRoutesKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SchemaMigrationLockKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SchemaVersionKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.VersionRoutingKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ArtifactJudgmentValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentApplyValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureCause;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeDepartureValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodePlacementValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaMigrationLockValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaVersionValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.VersionRoutingValue;
import org.pragmatica.aether.slice.kvstore.CommunityState;
import org.pragmatica.lang.Option;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.consensus.NodeId;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.pragmatica.aether.deployment.cluster.fsm2.SlFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;


/// `Rebuild` (C1, R-3.3.1): every key family the FSM reads is indexed from the committed mirror, the derived indexes are
/// right, and the two leader-local clocks start empty (R-2.14c, R-2.15). Then each family's committed removal drops it again.
class GapRebuildTest {
    private static final NodeIdHolder IDS = new NodeIdHolder();
    private static final BlueprintId OTHER = blueprintId("org.example:other:1.0.0");

    private static final class NodeIdHolder {
        final NodeId worker = node("worker-1");
        final NodeId gone = node("worker-2");
    }

    private FsmHarness everything() {
        var h = threeCores();
        var blueprint = blueprint("org.example:app:1.0.0",
                                  false,
                                  ATTEMPT,
                                  slice(ORDERS, 1, 1, Set.of(BILLING)),
                                  slice(BILLING, 1, 1, Set.of()));
        var id = blueprint.blueprint().id();

        h.seedQuietly(new AppBlueprintKey(id), blueprint);
        h.seedQuietly(new SliceTargetKey(ORDERS.base()), ownedTarget(ORDERS, 1, 1, id));
        h.seedQuietly(new VersionRoutingKey(ORDERS.base()),
                      new VersionRoutingValue(ORDERS.version(), ORDERS_V2.version(), 1, 1, FsmHarness.START_WALL));
        seedGoal(h, CORE_1, ORDERS, SliceGoal.ACTIVE, G1);
        seedRow(h, CORE_1, ORDERS, SliceObservedState.ACTIVE, G1, 11L);
        seedRow(h, CORE_2, BILLING, SliceObservedState.ACTIVE, G1, 12L);
        h.seedQuietly(new ActivationDirectiveKey(IDS.worker), worker("east-w-0"));
        h.seedQuietly(new ActivationDirectiveKey(IDS.gone), worker("east-w-0"));
        h.seedQuietly(new CommunityKey("east-w-0"), community("east", 3, CommunityState.ACTIVE));
        h.seedQuietly(new GovernorAnnouncementKey("east-w-0"), roster(IDS.worker, false, IDS.worker, IDS.gone));
        h.seedQuietly(new NodeDepartureKey(IDS.gone),
                      new NodeDepartureValue(DepartureState.DEPARTED,
                                             3L,
                                             process(22L),
                                             Option.some(DepartureCause.WORKER_LEAVE),
                                             5L));
        h.seedQuietly(new ArtifactJudgmentKey(ORDERS_V2),
                      new ArtifactJudgmentValue(true, Map.of(CORE_1, 2), Map.of()));
        h.seedQuietly(new DeploymentApplyKey(OTHER),
                      new DeploymentApplyValue(ATTEMPT, Option.none(), FsmHarness.START_WALL));
        h.seedQuietly(new DeploymentOutcomeKey(OTHER),
                      DeploymentOutcomeValue.inProgress(FsmHarness.START_WALL, 1L, ATTEMPT));
        h.seedQuietly(new SchemaVersionKey("ds"),
                      new SchemaVersionValue("ds",
                                             1,
                                             "m1",
                                             SchemaStatus.COMPLETED,
                                             "g:a:1",
                                             id,
                                             0,
                                             FsmHarness.START_WALL));
        h.seedQuietly(new SchemaMigrationLockKey("ds"), new SchemaMigrationLockValue("ds", CORE_1, 1L, 2L, 1L));
        h.seedQuietly(new NodeRoutesKey(CORE_1, ORDERS), new NodeRoutesValue(List.of(), Epoch.ZERO));
        h.seedQuietly(new NodePlacementKey(IDS.worker), new NodePlacementValue("east", Option.some("z1"), "i-1"));

        return h;
    }

    /// Red when: any key family is not indexed at `Rebuild`, a derived index (`stray`, `departed`, `assignedByCommunity`,
    /// `rosterCount`, `ownerByBase`, `dependents`) is wrong, or `announcementSeenAt` / `absentSince` do not start empty.
    @Test
    void rebuild_indexesEveryFamily_andStartsTheLeaderLocalClocksEmpty() {
        var h = everything();

        h.activate();
        var m = h.model();
        var id = blueprintId("org.example:app:1.0.0");

        assertThat(m.blueprints.containsKey(id)).isTrue();
        assertThat(m.ownerByBase.get(ORDERS.base())).isEqualTo(Option.some(id));
        assertThat(Idx.members(m.dependents, BILLING).toList()).as("ORDERS depends on BILLING").containsExactly(ORDERS);
        assertThat(m.targets.containsKey(ORDERS.base())).isTrue();
        assertThat(m.routings.containsKey(ORDERS.base())).isTrue();
        assertThat(m.desired(SlicePair.slicePair(CORE_1, ORDERS))).isNotEqualTo(Option.none());
        assertThat(m.observed(SlicePair.slicePair(CORE_1, ORDERS))).isNotEqualTo(Option.none());
        assertThat(h.warns("stray-instance")).as("the first reconcile found the stray (a current row with no desired key) and condemned it")
                  .hasSize(1);
        assertThat(m.hasStray(SlicePair.slicePair(CORE_1, ORDERS))).isFalse();
        assertThat(m.directives.size()).isEqualTo(2);
        assertThat(m.isDeparted(IDS.gone)).isTrue();
        assertThat(Idx.members(m.assignedByCommunity, "east-w-0").toList()).as("a departed id is not counted in the assignment")
                  .containsExactly(IDS.worker);
        assertThat(m.communities.containsKey("east-w-0")).isTrue();
        assertThat(m.rosterCount.get(IDS.worker)).isEqualTo(Option.some(1));
        assertThat(m.judgment(ORDERS_V2).map(ArtifactJudgmentValue::retries)).isEqualTo(Option.some(Map.of(CORE_1, 2)));
        assertThat(m.apply(OTHER).isPresent()).isTrue();
        assertThat(m.outcomes.containsKey(OTHER)).isTrue();
        assertThat(m.schemas.containsKey("ds")).isTrue();
        assertThat(m.schemaLocks.containsKey("ds")).isTrue();
        assertThat(m.routes.containsKey(SlicePair.slicePair(CORE_1, ORDERS))).isTrue();
        assertThat(Idx.members(m.routesByNode, CORE_1).toList()).containsExactly(ORDERS);
        assertThat(m.nodePlacements.containsKey(IDS.worker)).isTrue();
        assertThat(m.announcementSeenAt.isEmpty()).as("every announcement reads stale until this leader sees a Put (R-2.14c)")
                  .isTrue();
        assertThat(m.absentSince.isEmpty()).as("absentSince starts empty (R-2.15)").isTrue();
    }

    /// Red when: a committed removal of a family is not applied to the model (the model keeps a view the mirror no longer has).
    @Test
    void committedRemovals_dropTheirFamilies() {
        var h = everything();

        h.activate();
        var id = blueprintId("org.example:app:1.0.0");

        h.deliver(new ClusterInput.KvRemove(new VersionRoutingKey(ORDERS.base())));
        h.deliver(new ClusterInput.KvRemove(new GovernorAnnouncementKey("east-w-0")));
        h.deliver(new ClusterInput.KvRemove(new CommunityKey("east-w-0")));
        h.deliver(new ClusterInput.KvRemove(new SchemaVersionKey("ds")));
        h.deliver(new ClusterInput.KvRemove(new DeploymentApplyKey(OTHER)));
        h.deliver(new ClusterInput.KvRemove(new DeploymentOutcomeKey(OTHER)));
        h.deliver(new ClusterInput.KvRemove(new ArtifactJudgmentKey(ORDERS_V2)));
        h.deliver(new ClusterInput.KvRemove(new SchemaMigrationLockKey("ds")));
        h.deliver(new ClusterInput.KvRemove(new NodeRoutesKey(CORE_1, ORDERS)));
        h.deliver(new ClusterInput.KvRemove(new NodePlacementKey(IDS.worker)));
        h.deliver(new ClusterInput.KvRemove(new NodeDepartureKey(IDS.gone)));
        var m = h.model();

        assertThat(m.routings.isEmpty()).isTrue();
        assertThat(m.rosters.isEmpty()).isTrue();
        assertThat(m.rosterCount.isEmpty()).isTrue();
        assertThat(m.announcementSeenAt.isEmpty()).isTrue();
        assertThat(m.communities.isEmpty()).isTrue();
        assertThat(m.schemas.isEmpty()).isTrue();
        assertThat(m.applies.isEmpty()).isTrue();
        assertThat(m.outcomes.isEmpty()).isTrue();
        assertThat(m.judgments.isEmpty()).isTrue();
        assertThat(m.unresponsiveByNode.isEmpty()).isTrue();
        assertThat(m.schemaLocks.isEmpty()).isTrue();
        assertThat(m.routes.isEmpty()).isTrue();
        assertThat(m.nodePlacements.isEmpty()).isTrue();
        assertThat(m.records.isEmpty()).isTrue();
        assertThat(m.isDeparted(IDS.gone)).as("no record, not departed").isFalse();
    }

    /// Red when: an external put or removal of a judgment (an operator clearing it) is not seen by the model, so placement keeps
    /// using the old permanent mark.
    @Test
    void externalJudgmentChange_isFollowed() {
        var h = threeCores();

        seedTarget(h, ORDERS, 1, 1);
        h.seedQuietly(new ArtifactJudgmentKey(ORDERS),
                      new ArtifactJudgmentValue(true, Map.of(), Map.of()));
        h.activate();
        assertThat(h.submits()).as("a permanently failed artifact is not placed").isEmpty();
        h.seedRemove(new ArtifactJudgmentKey(ORDERS));
        h.drain();
        assertThat(h.model().judgment(ORDERS)).isEqualTo(Option.none());
        assertThat(hosts(h, ORDERS)).as("with the mark gone the artifact is placed").hasSize(1);
    }

    /// Red when: a put of the apply or outcome record is not indexed (C22) or does not queue the reconcile that closes the apply.
    @Test
    void applyAndOutcomePuts_areIndexed() {
        var h = threeCores();
        var blueprint = blueprint("org.example:app:1.0.0", false, ATTEMPT, slice(ORDERS, 1, 1, Set.of()));
        var id = blueprint.blueprint().id();

        h.activate();
        h.seed(new DeploymentApplyKey(id), new DeploymentApplyValue(ATTEMPT, Option.none(), h.wallMs));
        h.seed(new DeploymentOutcomeKey(id), DeploymentOutcomeValue.inProgress(h.wallMs, 1L, ATTEMPT));
        h.drain();
        assertThat(h.model().apply(id).isPresent()).isTrue();
        assertThat(h.model().outcomes.containsKey(id)).isTrue();
    }
}
