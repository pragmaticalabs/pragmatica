package org.pragmatica.dht;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.DrainCluster.id;

/// The two departure-drain counterexamples the TLA+ departure model (#3205) found, reproduced exactly. Shared root: a LIVE node that
/// is only a stale drain-set entry (#1818) was treated as leaving.
///
/// - #3208 (`allnamed`, NoLastCopyLoss): N=3, RF=2, nodes 1 and 2 drain, node 3 is live but named in the drain set, every copy is
///   on 1 or 2. The exclusion left no target at all, so both drainers halted with the key.
/// - #3209 (`strand`, ReplicaPlacement): N=4, RF=2, ring order 1..4 for the key, node 1 drains holding the only copy, node 3 is a
///   stale entry. The delta branch targeted only 4, which is outside the final replica set {2,3}.
@Timeout(30)
class DHTDepartureDrainTargetTest {
    @Test
    void allNamed_staleLiveEntryIsTheOnlyNonDrainer_keySurvivesOnIt_andTheUnprovableCopyIsReportedAtRisk() {
        var cluster = new DrainCluster(List.of("n1", "n2", "n3"));
        var key = cluster.keyInOrder("n1", "n2", "n3");
        var drainers = Set.of(id("n1"), id("n2"));
        var drainSet = Set.of(id("n1"), id("n2"), id("n3"));

        cluster.put(key, "n1", "n2");
        var reports = cluster.drain(drainers, drainSet);

        assertThat(cluster.holds("n3", key))
            .as("#3208: the live node named only by a stale drain-set entry receives the last copy").isTrue();
        assertThat(cluster.atRiskRaised.get())
            .as("#3208: no target outside the drain set can be shown to survive, so the sole copy is raised at risk").isPositive();
        assertThat(reports).as("#3208: the drain reports the unprovable copy as data at risk")
                           .allMatch(DeparturePushReport::dataAtRisk);
    }

    @Test
    void strand_staleEntryShiftsTheNewcomerPastTheFinalReplicaSet_aFinalReplicaHoldsTheKey() {
        var cluster = new DrainCluster(List.of("n1", "n2", "n3", "n4"));
        var key = cluster.keyInOrder("n1", "n2", "n3", "n4");

        cluster.put(key, "n1");
        cluster.drain(Set.of(id("n1")), Set.of(id("n1"), id("n3")));

        var finalRing = ConsistentHashRing.<NodeId>consistentHashRing();
        List.of("n2", "n3", "n4").forEach(name -> finalRing.addNode(id(name)));
        var finalReplicas = finalRing.nodesFor(key, 2);

        assertThat(finalReplicas).as("instrument: the final replica set of the model's shape").containsExactly(id("n2"), id("n3"));
        assertThat(finalReplicas.stream().anyMatch(replica -> cluster.holds(replica.id(), key)))
            .as("#3209: a member of the final replica set {2,3} holds the key the drainer held").isTrue();
    }

    @Test
    void exactDrainSet_coDrainerAckDoesNotDeliver_onlyANonNamedTargetDoes() {
        var cluster = new DrainCluster(List.of("n1", "n2", "n3", "n4"));
        var key = cluster.keyInOrder("n1", "n2", "n3", "n4");
        var drainers = Set.of(id("n1"), id("n2"));

        cluster.put(key, "n1");
        var reports = cluster.drain(drainers, drainers);

        assertThat(cluster.holds("n3", key) || cluster.holds("n4", key))
            .as("a node outside the drain set holds the key").isTrue();
        assertThat(reports).as("a non-named target acknowledged the key: nothing at risk")
                           .noneMatch(DeparturePushReport::dataAtRisk);
    }
}
