package org.pragmatica.dht;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.DrainCluster.id;

/// What counts as delivery, where a retarget goes, and what a receiver keeps, for the departure drain's final-replica-set targets
/// (#3208, #3209; follow-up to [DHTDepartureDrainTargetTest]). Each test names the mutation of the production line it pins.
@Timeout(30)
class DHTDepartureDrainDeliveryTest {
    /// #3209, n2 (the final primary) is unreachable but stays in the ring: the delta target n4 holds the copy, yet n4 is outside the
    /// final replica set {n2,n3}. Its ack must not show the key delivered, so the unreachable primary leaves the key reported at risk.
    /// Pins `Page.settle` / `DepartureKey.allowDelivery`: counting any non-named ack leaves the copy only on n4 with nothing reported.
    @Test
    void finalPrimaryUnreachable_deltaAckDoesNotDeliver_aFinalReplicaHoldsTheKeyOrItIsReportedAtRisk() {
        var cluster = new DrainCluster(List.of("n1", "n2", "n3", "n4"));
        var key = cluster.keyInOrder("n1", "n2", "n3", "n4");
        cluster.dropTo.add(id("n2"));

        cluster.put(key, "n1");
        var reports = cluster.drain(Set.of(id("n1")), Set.of(id("n1"), id("n3")));

        assertThat(cluster.sentTo).as("instrument: the drainer pushed to n2 (the final primary) and n4 (the delta target)")
                                  .contains(id("n2"), id("n4"));
        assertThat(cluster.holds("n4", key)).as("instrument: the delta target n4 holds the key").isTrue();

        var finalReplicaHolds = cluster.holds("n2", key) || cluster.holds("n3", key);
        var report = reports.get(0);

        assertThat(finalReplicaHolds || report.soleCopyAtRisk() + report.replicatedAtRisk() > 0)
            .as("a member of the final replica set {n2,n3} holds the key, or the drain reports an unconfirmed key (report=%s)", report)
            .isTrue();
    }

    /// n2 is the first unnamed node at plan time and leaves the drainer's ring at the first push to it, never answering. The final
    /// ring is {n3,n4,n5} (n3, n4 stale entries), whose replica set {n3,n4} only the retarget reaches.
    /// Pins `retargets` using `drainTargets`: with the delta targets alone the retarget sends nothing to n3 or n4.
    @Test
    void retarget_afterTheFirstUnnamedTargetLeavesTheRing_reachesTheFinalReplicaSet() {
        var cluster = new DrainCluster(List.of("n1", "n2", "n3", "n4", "n5"));
        var key = cluster.keyInOrder("n1", "n2", "n3", "n4", "n5");
        cluster.dropTo.add(id("n2"));
        cluster.removeOnFirstSend = id("n2");

        cluster.put(key, "n1");
        cluster.drain(Set.of(id("n1")), Set.of(id("n1"), id("n3"), id("n4")));

        assertThat(cluster.sentTo).as("instrument: n2 was pushed to at plan time").contains(id("n2"));
        assertThat(cluster.members.get(id("n1")).node().ring().nodes())
            .as("instrument: n2 left the drainer's ring").doesNotContain(id("n2"));
        assertThat(cluster.holds("n5", key)).as("instrument: the delta target n5 holds the key").isTrue();
        assertThat(cluster.holds("n3", key) || cluster.holds("n4", key))
            .as("a member of the final replica set {n3,n4} holds the key after the retarget (sent=%s)", cluster.sentTo).isTrue();
    }

    /// Two named nodes lie ahead of the first unnamed one. The named prefix is every node up to it, and the first unnamed node n4
    /// delivers: a prefix cut to its first entry names n2 and n3 only, leaves no deliverer, and reports the key at risk.
    /// Pins `namedPrefixWithFirstUnnamed` taking all of `takeWhile(excluded::contains)`.
    @Test
    void twoNamedAheadOfTheFirstUnnamed_theWholePrefixAndTheFirstUnnamedAreTargeted_andNothingIsAtRisk() {
        var cluster = new DrainCluster(List.of("n1", "n2", "n3", "n4", "n5"));
        var key = cluster.keyInOrder("n1", "n2", "n3", "n4", "n5");

        cluster.put(key, "n1");
        var reports = cluster.drain(Set.of(id("n1")), Set.of(id("n1"), id("n2"), id("n3")));

        assertThat(cluster.sentTo).as("both named nodes and the first unnamed node are pushed to")
                                  .contains(id("n2"), id("n3"), id("n4"));
        assertThat(cluster.holds("n3", key) && cluster.holds("n4", key)).as("n3 (named) and n4 (first unnamed) hold the key").isTrue();
        assertThat(reports).as("n4 is outside the drain set and acked: nothing at risk").noneMatch(DeparturePushReport::dataAtRisk);
        assertThat(cluster.atRiskRaised.get()).as("no sole-copy-at-risk event").isZero();
    }

    /// A receiver the pusher did not name, yet that already knows itself to be departing, refuses the copy: the pusher's `leaving`
    /// set does not hold it, so it is not a "named self" (#3208) and stays excluded from its own view.
    /// Pins `namedSelf` checking the pusher's leaving set, and `leftInDepartureView` keeping the receiver's own departing term.
    @Test
    void receiverKnowingItselfDepartingButNotNamedByThePusher_refusesTheCopy() {
        var cluster = new DrainCluster(List.of("n1", "n2", "n3", "n4"));
        var key = cluster.keyInOrder("n1", "n2", "n3", "n4");
        cluster.departing.add(id("n3"));

        cluster.put(key, "n1");
        cluster.drain(Set.of(id("n1")), Set.of(id("n1")), Set.of(id("n1"), id("n3")));

        assertThat(cluster.sentTo).as("instrument: the pusher targeted n3 (n2 is a current replica, so not a target)").contains(id("n3"));
        assertThat(cluster.holds("n3", key)).as("n3 knows it departs and the pusher did not name it: it refuses").isFalse();
    }

    /// Receivers that have not heard of the co-drainer must still exclude it, because the pusher carries its leaving set: with
    /// drain {n1,n2} the last replica n4 is inside RF only once n2 is excluded from its view.
    /// Pins `leftInDepartureView` keeping the pusher's `pusherLeaving` term.
    @Test
    void receiversThatHaveNotHeardOfTheCoDrainer_excludeItFromThePushersCarriedLeavingSet() {
        var cluster = new DrainCluster(List.of("n1", "n2", "n3", "n4"));
        var key = cluster.keyInOrder("n1", "n2", "n3", "n4");

        cluster.put(key, "n1");
        cluster.drain(Set.of(id("n1")), Set.of(id("n1"), id("n2")), Set.of());

        assertThat(cluster.sentTo).as("instrument: the pusher targeted n3 and n4").contains(id("n3"), id("n4"));
        assertThat(cluster.holds("n3", key) && cluster.holds("n4", key))
            .as("n4 is within RF of the view without n1 and n2 only: it accepts the copy").isTrue();
    }
}
