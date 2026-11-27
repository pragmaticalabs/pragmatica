// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Set;

import org.pragmatica.aether.slice.kvstore.AetherKey.GovernorAnnouncementKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.CommunityState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.pragmatica.aether.deployment.cluster.fsm2.SlFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

/// #3815: a committed target whose placement audience is empty is visible to the operator as `placement-audience-empty`, once per episode,
/// closed by `placement-audience-available` when the audience is non-empty (or the target or its policy goes away). The floor is unchanged.
class EmptyAudienceWarningTest {
    private static final String OPEN = "placement-audience-empty";
    private static final String CLOSE = "placement-audience-available";
    private static final NodeId W1 = node("worker-1");
    private static final NodeId W2 = node("worker-2");
    private static final NodeId W3 = node("worker-3");
    private static final Set<NodeId> ALL = Set.of(CORE_1, CORE_2, CORE_3, W1, W2, W3);

    private static MbWorld formingWorld(String placement, int floor, NodeId... workers) {
        var w = MbWorld.worldWithFloor(floor, CORE_1, CORE_2, CORE_3)
                       .target(ORDERS, 1, placement)
                       .community("east-w-0", 3, CommunityState.FORMING)
                       .seams(seams -> seams.withReady(ALL)
                                            .withLocalAlive(Option.some(Set.of(workers))));

        for (var worker : workers) {
            w.directive(worker, worker("east-w-0"));
        }

        w.h.seedQuietly(new GovernorAnnouncementKey("east-w-0"), roster(workers[0], false, workers));
        w.activate();

        return w;
    }

    /// Red when: the empty audience raises nothing, raises on every pass, or the text leaves out the target, the policy, the community
    /// state, the live members, the floor or the way out (`allow_below_rf`).
    @Test
    void oneWorker_workersOnly_raisesOnce_acrossTicks_andNamesWhatTheOperatorNeeds() {
        var w = formingWorld("WORKERS_ONLY", 3, W1);

        w.h.deliver(new ClusterInput.Reconcile());
        w.h.deliver(new ClusterInput.Reconcile());
        w.h.deliver(new ClusterInput.Reconcile());

        var warns = w.h.warns(OPEN);

        assertThat(warns).as("once per episode").hasSize(1);
        assertThat(warns.getFirst().subject()).isEqualTo(ORDERS.base().asString());
        assertThat(warns.getFirst().message()).contains(ORDERS.base().asString(), "WORKERS_ONLY", "east-w-0", "FORMING", "1 live", "floor is 3", "allow_below_rf");
        assertThat(w.h.warns(CLOSE)).isEmpty();
        assertThat(hosts(w.h, ORDERS)).as("the floor stays: nothing is placed").isEmpty();
    }

    /// Red when: the episode is not closed once the audience is non-empty (two more workers make the community ACTIVE), or the target is
    /// not placed then, or the episode does not reopen for a later empty audience (checked by the count staying at one open).
    @Test
    void audienceBecomesNonEmpty_closesTheEpisode_andTheTargetPlaces() {
        var w = formingWorld("WORKERS_ONLY", 3, W1);

        w.h.deliver(new ClusterInput.Reconcile());
        assertThat(w.h.warns(OPEN)).hasSize(1);

        w.directive(W2, worker("east-w-0")).directive(W3, worker("east-w-0"));
        w.refreshSeams(seams -> seams.withLocalAlive(Option.some(Set.of(W1, W2, W3))));
        w.announce("east-w-0", W1, W1, W2, W3);
        w.h.deliver(new ClusterInput.Reconcile());
        w.h.deliver(new ClusterInput.Reconcile());

        var closes = w.h.warns(CLOSE);

        assertThat(closes).hasSize(1);
        assertThat(closes.getFirst().subject()).isEqualTo(ORDERS.base().asString());
        assertThat(closes.getFirst().message()).doesNotContain(Recoveries.SUBJECT_GONE);
        assertThat(w.h.warns(OPEN)).as("no second opener").hasSize(1);
        assertThat(hosts(w.h, ORDERS)).hasSize(1);
        assertThat(hosts(w.h, ORDERS).getFirst()).isIn(W1, W2, W3);
    }

    /// Red when: a cluster with three workers (the default floor) raises the event at any pass.
    @Test
    void threeWorkers_fromTheStart_raiseNothing() {
        var w = MbWorld.worldWithFloor(3, CORE_1, CORE_2, CORE_3)
                       .target(ORDERS, 1, "WORKERS_ONLY")
                       .community("east-w-0", 3, CommunityState.FORMING)
                       .seams(seams -> seams.withReady(ALL).withLocalAlive(Option.some(Set.of(W1, W2, W3))));

        w.directive(W1, worker("east-w-0")).directive(W2, worker("east-w-0")).directive(W3, worker("east-w-0"));
        w.h.seedQuietly(new GovernorAnnouncementKey("east-w-0"), roster(W1, false, W1, W2, W3));
        w.activate();
        w.h.deliver(new ClusterInput.Reconcile());
        w.h.deliver(new ClusterInput.Reconcile());
        w.h.deliver(new ClusterInput.Reconcile());

        assertThat(w.h.warns(OPEN)).isEmpty();
        assertThat(w.h.warns(CLOSE)).isEmpty();
        assertThat(hosts(w.h, ORDERS)).hasSize(1);
    }

    /// Red when: a policy other than WORKERS_ONLY that computes an empty audience (CORE_ONLY with no ready core) stays silent: the one
    /// site covers every policy kind.
    @Test
    void coreOnly_withNoReadyCore_raisesToo() {
        var w = MbWorld.worldWithFloor(3, CORE_1, CORE_2, CORE_3)
                       .target(ORDERS, 1, "CORE_ONLY")
                       .seams(seams -> seams.withReady(Set.of()))
                       .activate();

        w.h.deliver(new ClusterInput.Reconcile());
        w.h.deliver(new ClusterInput.Reconcile());

        assertThat(w.h.warns(OPEN)).hasSize(1);
        assertThat(w.h.warns(OPEN).getFirst().message()).contains("CORE_ONLY");
    }

    /// Red when: removing the target leaves the episode open (subject gone).
    @Test
    void targetRemoved_closesTheEpisode_subjectGone() {
        var w = formingWorld("WORKERS_ONLY", 3, W1);

        w.h.deliver(new ClusterInput.Reconcile());
        w.h.seedRemove(new SliceTargetKey(ORDERS.base()));
        w.h.drain();

        var closes = w.h.warns(CLOSE);

        assertThat(closes).hasSize(1);
        assertThat(closes.getFirst().message()).contains(Recoveries.SUBJECT_GONE);
    }

    /// Red when: a target whose placement becomes unparsable keeps `placement-audience-empty` open for a policy that no longer exists.
    @Test
    void placementTurnsInvalid_closesTheEpisode() {
        var w = formingWorld("WORKERS_ONLY", 3, W1);

        w.h.deliver(new ClusterInput.Reconcile());
        w.h.seed(new SliceTargetKey(ORDERS.base()), target(ORDERS, 1, 1, "SIDEWAYS"));
        w.h.drain();
        w.h.deliver(new ClusterInput.Reconcile());

        assertThat(w.h.warns("invalid-placement")).hasSize(1);
        assertThat(w.h.warns(CLOSE)).hasSize(1);
    }
}
