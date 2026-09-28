package org.pragmatica.consensus.rabia;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/// #1554 — the VIEW-layer claim only: a pending node's view never shrinks while its process lives, so an
/// action that leaves the pending processes running (stopping the extra candidates, stopping a retired core,
/// adding a fresh-identity replacement) leaves the survivors waiting, and fresh views agree.
///
/// This layer has no boot tokens, so it says nothing about how to restart the cores: on real transport a live
/// peer that recorded a core's old token refuses its new process (#1545). The operator procedure (stop all,
/// then start) is pinned on real transport by `EmberGenesisRecoveryTest`. (Adapted from v1554's round-3 probe.)
class GenesisRecoveryActionsTest {
    private static final NodeId A = new NodeId("a");
    private static final NodeId B = new NodeId("b");
    private static final NodeId C = new NodeId("c");
    private static final NodeId X = new NodeId("x");
    private static final NodeId D = new NodeId("d");

    @Test
    void exceeds_stoppingTheExtraCandidate_leavesTheRemainingCoresWaiting() {
        var world = new World(3, Set.of(A, B, C, X));
        world.rounds(4);
        assertThat(world.stage(A)).isEqualTo(GenesisViewAgreement.Stage.EXCEEDS_COUNT);

        world.stop(X);
        world.rounds(40);

        assertThat(world.agreed()).as("a live process never forgets X, so no genesis forms").isEmpty();
        assertThat(world.stage(A)).isEqualTo(GenesisViewAgreement.Stage.EXCEEDS_COUNT);
    }

    @Test
    void waiting_addingAFreshIdentityReplacement_leavesTheRemainingCoresWaiting() {
        var world = new World(3, Set.of(A, B, C));
        world.rounds(1);
        world.stop(C);
        world.rounds(4);
        assertThat(world.stage(A)).isEqualTo(GenesisViewAgreement.Stage.WAITING);

        world.add(D);
        world.rounds(40);

        assertThat(world.agreed()).as("A and B still hold C in their views").isEmpty();
    }

    @Test
    void exceeds_everyPendingViewReset_agreesOnTheRemainingCores() {
        var world = new World(3, Set.of(A, B, C, X));
        world.rounds(4);

        world.stop(X);
        world.restartAll(Option.none());
        world.rounds(10);

        assertThat(world.agreed()).containsOnlyKeys(A, B, C);
        assertThat(Set.copyOf(world.agreed().values())).containsExactly(Set.of(A, B, C));
    }

    @Test
    void waiting_everyPendingViewResetWithTheReplacement_agreesOnTheNewRoster() {
        var world = new World(3, Set.of(A, B, C));
        world.rounds(1);
        world.stop(C);
        world.rounds(4);

        world.add(D);
        world.restartAll(Option.none());
        world.rounds(10);

        assertThat(Set.copyOf(world.agreed().values())).containsExactly(Set.of(A, B, D));
    }

    @Test
    void exceeds_everyPendingViewResetWithGenesisVoters_agreesOnTheIntendedRoster() {
        var world = new World(3, Set.of(A, B, C, X));
        world.rounds(4);

        world.stop(X);
        world.restartAll(Option.some(Set.of(A, B, C)));
        world.rounds(10);

        assertThat(Set.copyOf(world.agreed().values())).containsExactly(Set.of(A, B, C));
    }

    /// Genesis view agreement among in-memory nodes; every node hears every other each round.
    private static final class World {
        final int count;
        final Map<NodeId, GenesisViewAgreement> nodes = new HashMap<>();
        final Map<NodeId, Set<NodeId>> agreedViews = new HashMap<>();

        World(int count, Set<NodeId> ids) {
            this.count = count;
            ids.forEach(this::add);
        }

        void add(NodeId id) {
            nodes.put(id, GenesisViewAgreement.genesisViewAgreement(id, count, Option.none()));
        }

        void stop(NodeId id) {
            nodes.remove(id);
        }

        void restartAll(Option<Set<NodeId>> genesisVoters) {
            List.copyOf(nodes.keySet())
                .forEach(id -> nodes.put(id, GenesisViewAgreement.genesisViewAgreement(id, count, genesisVoters)));
        }

        void rounds(int n) {
            for (int i = 0; i < n; i++) {
                var alive = Set.copyOf(nodes.keySet());
                var reports = new HashMap<NodeId, GenesisViewAgreement.Report>();

                nodes.forEach((id, agreement) -> reports.put(id, agreement.tick(alive)));
                reports.forEach((from, report) -> nodes.forEach((to, agreement) -> deliver(from, to, report, agreement)));
                nodes.forEach((id, agreement) -> agreement.agreed().onPresent(view -> agreedViews.put(id, view)));
            }
        }

        private static void deliver(NodeId from, NodeId to, GenesisViewAgreement.Report report, GenesisViewAgreement agreement) {
            if (!to.equals(from)) {
                agreement.receive(from, report.round(), report.view());
            }
        }

        GenesisViewAgreement.Stage stage(NodeId id) {
            return nodes.get(id).status().stage();
        }

        Map<NodeId, Set<NodeId>> agreed() {
            return agreedViews;
        }
    }
}
