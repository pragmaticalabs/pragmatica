package org.pragmatica.dht;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.DurableDeleteCluster.bytes;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #3347 tripwires beside [DHTRingChangeStrayResurrectionTest]: a stale copy on a node that is no longer a holder (a
/// stray) is believed after the tombstone that deleted it is gone.
///
/// - T3: a delete missed by C, every holder that knew the tombstone restarts with an empty store after it expired, C
///   regains the partition: C's copy comes back.
/// - T5: C is displaced by a join but stays in the ring and reachable, its anti-entropy never ran; the tombstone is
///   collected on the holders; a get from a holder falls back to C and answers the deleted value.
/// - T5b: the same, the reader is C itself (the client's local read).
///
/// Each tripwire asserts TODAY's behaviour and is green on the base; the `@Disabled` twin beside it holds the real
/// assertion.
class DHTStrayCurrencyTripwireTest {
    private static final DHTConfig RF3 = new DHTConfig(3, 2, 2, timeSpan(2).seconds());
    private static final byte[] BLOCK = bytes("block");
    private static final long ONE_MINUTE = 60_000L;
    private static final int ROUNDS = 6;

    private static String fixed(String twin) {
        return "#3347 fixed? enable " + twin + ", delete this tripwire. ";
    }

    /// Fixture pin: `advanceClockBy` moves BOTH node clocks (wall and monotonic) by exactly the step.
    @Test
    void advanceClockBy_movesWallAndMonotonicClocksOfEveryNode() {
        var cluster = new DurableDeleteCluster(3, RF3);
        var wallBefore = cluster.members().stream().map(m -> m.node().nowMillis()).toList();
        var monotonicBefore = cluster.members().stream().map(m -> m.node().monotonicMillis()).toList();

        cluster.advanceClockBy(ONE_MINUTE);

        assertThat(cluster.members().stream().map(m -> m.node().nowMillis() - wallBefore.get(0)).toList())
            .as("wall clock of every node moved by one step").containsOnly(ONE_MINUTE);
        assertThat(cluster.members().stream().map(m -> m.node().monotonicMillis() - monotonicBefore.get(0)).toList())
            .as("monotonic clock of every node moved by one step").containsOnly(ONE_MINUTE);
    }

    /// Fixture pin: `restartEmpty` gives a new node over a NEW empty store; `restart` keeps the old store.
    @Test
    void restartEmpty_dropsTheStore_restartKeepsIt() {
        var cluster = new DurableDeleteCluster(3, RF3);
        var key = bytes("fixture-key");
        var holder = cluster.replicasOf(key).getFirst();

        cluster.member(holder).client().put(key, BLOCK).await().unwrap();
        cluster.restart(holder, RF3);
        assertThat(cluster.holdsLive(holder, key)).as("restart keeps the store").isTrue();

        cluster.restartEmpty(holder, RF3);
        assertThat(cluster.entryAt(holder, key)).as("restartEmpty starts over an empty store").isEmpty();
    }

    // ---- T3

    /// TRIPWIRE (T3), asserts TODAY's behaviour (#3347): C's missed-delete copy is live again on A, B and C after the
    /// regain although the tombstone expired and A, B, D restarted empty.
    @Test
    void missedDelete_holdersRestartedEmptyAfterExpiry_cRegains_resurrectsTheDeletedBlock_today() {
        var route = new MissedDeleteRoute();

        route.run();

        assertThat(route.liveAtEnd()).as(fixed("missedDelete_holdersRestartedEmptyAfterExpiry_cRegains_deletedBlockStaysDeleted")
                                         + "Trace: %s", route.trace)
                                     .containsExactlyInAnyOrder(route.a, route.b, route.c);
    }

    @Disabled("#3347: enable when a missed delete stays deleted after the tombstone expired and the holders restarted empty")
    @Test
    void missedDelete_holdersRestartedEmptyAfterExpiry_cRegains_deletedBlockStaysDeleted() {
        var route = new MissedDeleteRoute();

        route.run();

        assertThat(route.liveAtEnd()).as("#3347 T3: nodes holding the deleted block live. Trace: %s", route.trace)
                                     .isEmpty();
        assertThat(route.cluster.member(route.a).client().get(route.key).await().unwrap())
            .as("#3347 T3: a get from A answers none. Trace: %s", route.trace)
            .isEmpty();
    }

    // ---- T5, T5b

    /// TRIPWIRE (T5), asserts TODAY's behaviour (#3347): the fallback read from A reaches C, which still holds the
    /// deleted block and answers it.
    @Test
    void displacedStray_getFromHolder_answersTheDeletedBlock_today() {
        var route = new DisplacedRoute();

        route.run();

        assertThat(route.cluster.member(route.a).client().get(route.key).await().unwrap())
            .as(fixed("displacedStray_getFromHolder_answersNone") + "Trace: %s", route.trace)
            .isEqualTo(Option.some(BLOCK));
    }

    @Disabled("#3347: enable when a get does not answer a deleted block held by a displaced stray")
    @Test
    void displacedStray_getFromHolder_answersNone() {
        var route = new DisplacedRoute();

        route.run();

        assertThat(route.cluster.member(route.a).client().get(route.key).await().unwrap())
            .as("#3347 T5: a get from A answers none. Trace: %s", route.trace)
            .isEmpty();
        assertThat(route.liveAnywhere()).as("#3347 T5: K is live nowhere. Trace: %s", route.trace).isEmpty();
    }

    /// TRIPWIRE (T5b), asserts TODAY's behaviour (#3347): C's own get is served from its stray copy.
    @Test
    void displacedStray_getFromTheStrayItself_answersTheDeletedBlock_today() {
        var route = new DisplacedRoute();

        route.run();

        assertThat(route.cluster.member(route.c).client().get(route.key).await().unwrap())
            .as(fixed("displacedStray_getFromTheStrayItself_answersNone") + "Trace: %s", route.trace)
            .isEqualTo(Option.some(BLOCK));
    }

    @Disabled("#3347: enable when the stray's own get does not answer a deleted block")
    @Test
    void displacedStray_getFromTheStrayItself_answersNone() {
        var route = new DisplacedRoute();

        route.run();

        assertThat(route.cluster.member(route.c).client().get(route.key).await().unwrap())
            .as("#3347 T5b: a get from C answers none. Trace: %s", route.trace)
            .isEmpty();
    }

    private abstract static class Route {
        final DurableDeleteCluster cluster = new DurableDeleteCluster(4, RF3);
        final byte[] key = bytes("t3347-stray-currency");
        final StringBuilder trace = new StringBuilder();
        NodeId a;
        NodeId b;
        NodeId c;
        NodeId d;

        abstract void run();

        long retention() {
            return cluster.members().getFirst().node().tombstoneRetention().millis();
        }

        List<DurableDeleteCluster.Member> reachable() {
            return cluster.members().stream().filter(m -> cluster.unreachable.contains(m.id()) == false).toList();
        }

        List<NodeId> liveAtEnd() {
            return cluster.members().stream().map(m -> m.id()).filter(id -> cluster.holdsLive(id, key)).toList();
        }

        List<NodeId> liveAnywhere() {
            return liveAtEnd();
        }

        void ringRemove(NodeId id) {
            cluster.members().forEach(m -> m.node().changeRing(ring -> ring.removeNode(id)));
        }

        void ringAdd(NodeId id) {
            cluster.members().forEach(m -> m.node().changeRing(ring -> ring.addNode(id)));
        }

        boolean collected(List<NodeId> ids) {
            return ids.stream().allMatch(id -> cluster.entryAt(id, key).isEmpty());
        }

        void collect(List<NodeId> ids) {
            for (var i = 0; i < ROUNDS && !collected(ids); i++) {
                reachable().forEach(m -> m.antiEntropy().synchronizeNow());
            }
        }

        void snapshot(String step) {
            trace.append(step)
                 .append('[')
                 .append(cluster.members().stream().map(m -> m.id().id() + "=" + state(m.id())).collect(Collectors.joining(",")))
                 .append("] ");
        }

        private String state(NodeId id) {
            return cluster.holdsLive(id, key)
                   ? "live"
                   : cluster.holdsTombstone(id, key)
                     ? "tomb"
                     : "none";
        }
    }

    /// T3 on rv F1's schedule: t0 write and agreed round; C cut off; t_d = t0 + 1 min delete on A, B; t1 = t_d + 20 min
    /// D joins and C leaves; past t_d + R the tombstone expires; A, B, D restart empty in turn; t1 + 45 min C regains.
    private static final class MissedDeleteRoute extends Route {
        @Override
        void run() {
            var replicas = cluster.replicasOf(key);

            c = replicas.getLast();
            a = replicas.getFirst();
            b = replicas.get(1);
            d = cluster.members().stream().map(m -> m.id()).filter(id -> !replicas.contains(id)).findFirst().orElseThrow();
            var holders = List.of(a, b, d);

            cluster.member(a).client().put(key, BLOCK).await().unwrap();
            cluster.synchronizeAll();
            assertThat(replicas).as("t0: K live on A, B, C").allMatch(id -> cluster.holdsLive(id, key));
            snapshot("t0");

            cluster.unreachable.add(c);
            cluster.advanceClockBy(ONE_MINUTE);
            var tD = cluster.clock.get();

            cluster.member(a).client().remove(key).await().unwrap();
            assertThat(List.of(a, b)).as("t_d: the delete reached A and B").allMatch(id -> cluster.holdsTombstone(id, key));
            assertThat(cluster.holdsLive(c, key)).as("t_d: C missed the delete").isTrue();
            snapshot("td");

            cluster.advanceClockBy(20 * ONE_MINUTE);
            var t1 = cluster.clock.get();

            ringRemove(c);
            reachable().forEach(m -> m.antiEntropy().catchUpNow());
            reachable().forEach(m -> m.antiEntropy().catchUpNow());
            assertThat(cluster.holdsTombstone(d, key)).as("t1: D joined the holders with the tombstone").isTrue();
            snapshot("t1");

            cluster.advanceClockBy(tD + retention() + ONE_MINUTE - cluster.clock.get());
            collect(holders);
            assertThat(collected(holders)).as("control: the tombstone expired and is collected on A, B, D. Trace: %s", trace).isTrue();
            snapshot("expired");

            holders.forEach(id -> {
                cluster.restartEmpty(id, RF3);
                assertThat(cluster.entryAt(id, key)).as("restarted " + id.id() + " holds an empty store").isEmpty();
            });
            snapshot("restarted-empty");

            cluster.advanceClockBy(Math.max(t1 + 45 * ONE_MINUTE, cluster.clock.get() + ONE_MINUTE) - cluster.clock.get());
            assertThat(cluster.holdsLive(c, key)).as("t1+45: C still holds its copy, cut off").isTrue();
            ringAdd(c);
            cluster.unreachable.remove(c);
            snapshot("regain");

            for (var i = 0; i < ROUNDS; i++) {
                cluster.catchUpAll(1);
                cluster.synchronizeAll();
                snapshot("round-" + i);
            }
        }
    }

    /// T5: D is out of the ring while K is written to A, B, C; D joins and displaces C, which stays in the ring and
    /// reachable with its anti-entropy never run; the holders delete K and collect the tombstone.
    private static final class DisplacedRoute extends Route {
        @Override
        void run() {
            var full = cluster.replicasOf(key);

            d = full.getLast();
            a = full.getFirst();
            b = full.get(1);
            c = cluster.members().stream().map(m -> m.id()).filter(id -> !full.contains(id)).findFirst().orElseThrow();

            ringRemove(d);
            assertThat(cluster.replicasOf(key)).as("before the join K's replicas are A, B, C").containsExactlyInAnyOrder(a, b, c);
            cluster.member(a).client().put(key, BLOCK).await().unwrap();
            assertThat(List.of(a, b, c)).as("K live on A, B, C").allMatch(id -> cluster.holdsLive(id, key));
            snapshot("written");

            ringAdd(d);
            assertThat(cluster.replicasOf(key)).as("after the join K's replicas are A, B, D; C is displaced")
                                               .containsExactlyInAnyOrder(a, b, d)
                                               .doesNotContain(c);
            var holders = List.of(a, b, d);

            holders.forEach(id -> cluster.member(id).antiEntropy().catchUpNow());
            holders.forEach(id -> cluster.member(id).antiEntropy().catchUpNow());
            assertThat(cluster.holdsLive(d, key)).as("D holds K after catching up").isTrue();
            snapshot("joined");

            cluster.member(a).client().remove(key).await().unwrap();
            assertThat(holders).as("the delete reached A, B, D").allMatch(id -> cluster.holdsTombstone(id, key));
            cluster.advanceClockBy(retention() + ONE_MINUTE);

            for (var i = 0; i < ROUNDS && !collected(holders); i++) {
                holders.forEach(id -> cluster.member(id).antiEntropy().synchronizeNow());
            }

            snapshot("collected");
            assertThat(collected(holders)).as("control: tombstone collected on A, B, D. Trace: %s", trace).isTrue();
            assertThat(cluster.holdsLive(c, key)).as("control: C still holds its stray, reachable. Trace: %s", trace).isTrue();
        }
    }
}
