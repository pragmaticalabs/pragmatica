package org.pragmatica.dht;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.DurableDeleteCluster.bytes;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #3347: can a deleted block come back through a ring change? Route: C holds the block and leaves its replica set
/// (a ring change), staying cut off so its copy is a stray it never purges; the remaining holders delete the block and
/// collect the tombstone; a later ring change makes C a holder again, so its stray is a live copy among holders that no
/// longer hold any tombstone.
///
/// Variant 2 restarts one remaining holder between the ring change and the delete: its departed-holder guard
/// (`DHTNode.holderLeftAt`) is in memory only.
class DHTRingChangeStrayResurrectionTest {
    private static final DHTConfig RF3 = new DHTConfig(3, 2, 2, timeSpan(2).seconds());
    private static final byte[] BLOCK = bytes("block");
    private static final long ONE_MINUTE = 60_000L;
    private static final int ROUNDS = 6;

    private static final String FIXED = "#3347 is fixed: the block no longer comes back. Delete this tripwire and enable the "
                                        + "@Disabled test beside it. ";

    /// TRIPWIRE, asserts TODAY's behaviour (#3347): the stray copy on C migrates back and the block is live again on A,
    /// B and C although it was deleted and its tombstone collected on A, B and D.
    @Test
    void ringChangeOutAndBack_strayOfFormerHolder_resurrectsTheDeletedBlock_today() {
        var route = new Route(false);

        route.run();

        assertThat(route.liveAtEnd()).as(FIXED + "Trace: %s", route.trace)
                                     .containsExactlyInAnyOrder(route.a, route.b, route.c);
    }

    /// TRIPWIRE, same route with a restart of holder A between the ring change and the delete.
    @Test
    void ringChangeOutAndBack_holderRestartedBeforeTheDelete_strayResurrectsTheDeletedBlock_today() {
        var route = new Route(true);

        route.run();

        assertThat(route.liveAtEnd()).as(FIXED + "Trace: %s", route.trace)
                                     .containsExactlyInAnyOrder(route.a, route.b, route.c);
    }

    @Disabled("#3347: enable when a deleted block stays deleted across a ring change out and back")
    @Test
    void ringChangeOutAndBack_deletedBlockStaysDeleted() {
        var route = new Route(false);

        route.run();

        assertThat(route.liveAtEnd()).as("#3347 route, no restart: nodes holding the deleted block live. Trace: %s", route.trace)
                                     .isEmpty();
    }

    @Disabled("#3347: enable when a deleted block stays deleted across a ring change out and back")
    @Test
    void ringChangeOutAndBack_holderRestartedBeforeTheDelete_deletedBlockStaysDeleted() {
        var route = new Route(true);

        route.run();

        assertThat(route.liveAtEnd()).as("#3347 route, holder restarted: nodes holding the deleted block live. Trace: %s",
                                         route.trace)
                                     .isEmpty();
    }

    private static final class Route {
        final DurableDeleteCluster cluster = new DurableDeleteCluster(4, RF3);
        final byte[] key = bytes("t3347-block");
        final boolean restart;
        final StringBuilder trace = new StringBuilder();
        NodeId a;
        NodeId b;
        NodeId c;
        NodeId d;
        List<NodeId> holders;

        Route(boolean restart) {
            this.restart = restart;
        }

        void run() {
            var replicas = cluster.replicasOf(key);
            var writer = replicas.getFirst();

            c = replicas.getLast();
            d = cluster.members().stream().map(DurableDeleteCluster.Member::id).filter(id -> !replicas.contains(id)).findFirst().orElseThrow();
            holders = replicas.stream().filter(id -> !id.equals(c)).toList();
            a = holders.getFirst();
            b = holders.getLast();
            trace.append("roles[A=").append(a.id()).append(",B=").append(b.id()).append(",C=").append(c.id()).append(",D=").append(d.id()).append("] ");

            // (a) write K to A, B, C
            cluster.member(writer).client().put(key, BLOCK).await().unwrap();
            assertThat(replicas).as("(a) K is live on all three replicas").allMatch(id -> cluster.holdsLive(id, key));
            snapshot("a-written");

            // (b) ring change: C leaves K's replica set (every node applies it, C included); C is cut off from then on
            cluster.unreachable.add(c);
            cluster.members().forEach(m -> m.node().changeRing(ring -> ring.removeNode(c)));
            assertThat(cluster.replicasOf(key)).as("(b) after removing C, K's replica set is A, B and D")
                                               .doesNotContain(c)
                                               .contains(d);
            // D takes C's place: catch-up rounds on the reachable nodes only
            reachable().forEach(m -> m.antiEntropy().catchUpNow());
            reachable().forEach(m -> m.antiEntropy().catchUpNow());
            assertThat(cluster.holdsLive(d, key)).as("(b) D holds K after catching up").isTrue();
            assertThat(cluster.holdsLive(c, key)).as("(b) C still holds its copy").isTrue();
            assertThat(cluster.member(c).node().strayPartitionsSince(Long.MAX_VALUE)).as("(b) C's copy is a stray in C's view")
                                                                                     .isNotEmpty();
            snapshot("b-ring-changed");

            if (restart) {
                cluster.restart(holders.getFirst(), RF3);
                snapshot("b2-restarted-" + holders.getFirst().id());
            }

            // (c) delete K on the holders; run rounds until the tombstone is collected on every reachable node
            cluster.member(holders.getFirst()).client().remove(key).await().unwrap();
            assertThat(reachable().stream().map(m -> m.id())).as("(c) A, B and D hold the tombstone")
                                                              .allMatch(id -> cluster.holdsTombstone(id, key));
            cluster.advanceClockBy(cluster.members().getFirst().node().tombstoneRetention().millis() + ONE_MINUTE);

            for (var i = 0; i < ROUNDS && !collectedOnReachable(); i++) {
                reachable().forEach(m -> m.antiEntropy().synchronizeNow());
            }

            snapshot("c-collected");
            assertThat(collectedOnReachable()).as("(c) control: the tombstone is collected on A, B and D. Trace: %s", trace).isTrue();
            assertThat(cluster.holdsLive(c, key)).as("(c) C still holds its stray, cut off").isTrue();

            // (d) ring change back: C is a holder of K again; heal C; run rounds
            cluster.members().forEach(m -> m.node().changeRing(ring -> ring.addNode(c)));
            assertThat(cluster.replicasOf(key)).as("(d) K's replica set is A, B, C again").contains(c).doesNotContain(d);
            cluster.unreachable.remove(c);
            snapshot("d-healed");

            for (var i = 0; i < ROUNDS; i++) {
                cluster.catchUpAll(1);
                snapshot("d-catchup-" + i);
                cluster.synchronizeAll();
                snapshot("d-sync-" + i);
            }
        }

        List<NodeId> liveAtEnd() {
            return cluster.members().stream().map(m -> m.id()).filter(id -> cluster.holdsLive(id, key)).toList();
        }

        private List<DurableDeleteCluster.Member> reachable() {
            return cluster.members().stream().filter(m -> !m.id().equals(c)).toList();
        }

        private boolean collectedOnReachable() {
            return reachable().stream().allMatch(m -> cluster.entryAt(m.id(), key).isEmpty());
        }

        private void snapshot(String step) {
            trace.append(step)
                 .append('[')
                 .append(cluster.members()
                                .stream()
                                .map(m -> m.id().id() + "=" + state(m.id()))
                                .collect(Collectors.joining(",")))
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
}
