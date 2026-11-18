package org.pragmatica.dht;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.dht.storage.OwnerEpochGate;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.DHTAntiEntropy.dhtAntiEntropy;
import static org.pragmatica.dht.DHTNode.dhtNode;
import static org.pragmatica.dht.DHTRebalancer.dhtRebalancer;
import static org.pragmatica.dht.DHTTopologyListener.dhtTopologyListener;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;

/// Issue #3345, shape 1: can ONE drain at N=3, RF=2 lose the last copy of a key when its two possible targets are each
/// absent in turn during the push? The drainer's view of a peer is changed through the product's own edges
/// ([DHTTopologyListener#onNodeDeparting] / [DHTTopologyListener#onNodeRecovered]); an absent peer's traffic is dropped, so its
/// page is never acknowledged. Controls: the same drain with no absence delivers; with one absence episode the other target
/// receives the key.
@Timeout(60)
class DHTDepartureSequentialAbsenceTest {
    private static final DHTConfig CONFIG = new DHTConfig(3, 2, 2, DHTConfig.DEFAULT_TIMEOUT);
    private static final byte[] V0 = "v0".getBytes(StandardCharsets.UTF_8);
    private static final NodeId DRAINER = new NodeId("n-d");
    private static final NodeId N_A = new NodeId("n-a");
    private static final NodeId N_B = new NodeId("n-b");
    /// Short windows so a repeat is quick: a page resent every 300 ms, the nack pause capped at 1 s.
    private static final DeparturePushTuning TUNING = new DeparturePushTuning(TimeSpan.timeSpan(300).millis(),
                                                                              TimeSpan.timeSpan(1).seconds(),
                                                                              DeparturePushTuning.DEFAULT.reportAfter(),
                                                                              TimeSpan.timeSpan(200).millis());

    /// Longer than one resend (300 ms): every page still pending is attempted again while the edge is held.
    /// The target that is absent first and returns; the other is then absent and pruned.
    private static final NodeId RETURNED = N_A;
    private static final long RESEND_PLUS = 700L;

    @Test
    void control_noAbsence_theKeyIsDeliveredToALiveNode() {
        var h = new Harness(true);
        var report = h.push().await().or(DeparturePushReport.NOT_ENUMERATED);

        h.halt();
        assertThat(report.soleCopyAtRisk()).as("no absence: nothing at risk").isZero();
        assertThat(h.heldByALiveNode()).as("no absence: the key is on a live node").isTrue();
        assertThat(Set.copyOf(h.pageTargets)).as("instrument: the plan pages the key to both live nodes").containsExactlyInAnyOrder(N_A, N_B);
        assertThat(h.holds(N_A) && h.holds(N_B)).as("no absence: both pages were applied").isTrue();
    }

    @Test
    void control_oneAbsenceEpisode_theOtherTargetReceivesTheKey() {
        var first = RETURNED;
        var other = other(first);
        var h = new Harness(true);

        h.absent.add(first);
        var push = h.push();
        h.await(() -> h.pageTargets.contains(first), "page to the first target");
        h.listener.onNodeDeparting(first);
        h.pause(RESEND_PLUS);
        var report = push.await().or(DeparturePushReport.NOT_ENUMERATED);

        h.halt();
        assertThat(h.pageTargets).as("instrument: the page was retargeted to the other node").contains(other);
        assertThat(report.soleCopyAtRisk()).as("one absence: nothing at risk").isZero();
        assertThat(h.holds(other)).as("one absence: the other target holds the key").isTrue();
    }

    /// The product's working arm, pinned: the drainer is primary of the key in the two-node ring {drainer, returned target}, so
    /// when the second target is pruned the drainer's survivor-side rebalance ([DHTRebalancer#onNodeRemoved]) pushes the key to the
    /// returned target. The survival comes from that rebalance, not from the departure push (the push's page was abandoned).
    @RepeatedTest(5)
    void twoAbsenceEpisodesInTurn_primaryInPair_theKeyStillSurvivesTheHalt() {
        assertSurvives(sequentialAbsence(false, true), "drainer primary in the pair ring, in its own ring");
    }

    // ---- #3345 shape 1: the push re-plans when a target is lost and pages the key again, so a later target receives it.

    /// Drainer secondary in the pair ring: the survivor-side rebalance does not run on it; the departure push re-pages the key to
    /// the returned target once the second target is lost, so a live node holds it after the halt.
    @Test
    void twoAbsenceEpisodesInTurn_secondaryInPair_theKeyStillSurvivesTheHalt() {
        assertSurvives(sequentialAbsence(false, false), "drainer secondary in the pair ring, in its own ring");
    }

    @Test
    void twoAbsenceEpisodesInTurn_primaryInPairPrunedFromOwnRing_theKeyStillSurvivesTheHalt() {
        assertSurvives(sequentialAbsence(true, true), "drainer primary in the pair ring, pruned from its own ring");
    }

    @Test
    void twoAbsenceEpisodesInTurn_secondaryInPairPrunedFromOwnRing_theKeyStillSurvivesTheHalt() {
        assertSurvives(sequentialAbsence(true, false), "drainer secondary in the pair ring, pruned from its own ring");
    }

    private static void assertSurvives(Run run, String arm) {
        assertThat(run.heldByALiveNode)
            .as("#3345 shape 1 [%s]: key on no live node after the halt; report=%s targets=%s", arm, run.report, run.targets)
            .isTrue();
    }

    private record Run(DeparturePushReport report, boolean heldByALiveNode, List<NodeId> targets) {}

    /// Both targets are paged at once (RF=2 at N=3, the key's two new owners). The first is absent and pruned across a resend; it
    /// returns and is re-added; the second is then absent and pruned across a resend; the push runs out; the drainer halts.
    private static Run sequentialAbsence(boolean drainerPrunedFromOwnRing, boolean primaryInPair) {
        var first = RETURNED;
        var other = other(first);
        var h = new Harness(primaryInPair);

        if (drainerPrunedFromOwnRing) {
            h.listener.onNodeDeparting(DRAINER);
        }
        h.absent.add(first);
        h.absent.add(other);
        var push = h.push();
        h.await(() -> h.pageTargets.contains(first) && h.pageTargets.contains(other), "pages to both targets");
        h.listener.onNodeDeparting(first);
        h.pause(RESEND_PLUS);
        h.absent.remove(first);
        h.listener.onNodeRecovered(first);
        h.listener.onNodeDeparting(other);
        h.pause(RESEND_PLUS);
        var report = push.await().or(DeparturePushReport.NOT_ENUMERATED);

        h.halt();
        h.absent.remove(other);
        h.listener.onNodeRecovered(other);

        return new Run(report, h.heldByALiveNode(), List.copyOf(h.pageTargets));
    }

    private static NodeId other(NodeId target) {
        return target.equals(N_A) ? N_B : N_A;
    }

    private static final class Harness {
        final Map<NodeId, Member> members = new LinkedHashMap<>();
        final Set<NodeId> absent = ConcurrentHashMap.newKeySet();
        final List<NodeId> pageTargets = new CopyOnWriteArrayList<>();
        final DHTTopologyListener listener;
        final byte[] key;

        Harness(boolean primaryInPair) {
            var ids = List.of(N_A, N_B, DRAINER);

            ids.forEach(id -> {
                var ring = ConsistentHashRing.<NodeId>consistentHashRing();
                ids.forEach(ring::addNode);
                var node = dhtNode(id, memoryStorageEngine(new Gate()), ring, CONFIG);
                DHTNetwork net = AcceptingNetwork.accepting(this::deliver);
                members.put(id, new Member(id, node, dhtRebalancer(node, net, CONFIG), dhtAntiEntropy(node, net, CONFIG, _ -> false)));
            });
            var drainer = members.get(DRAINER);

            listener = dhtTopologyListener(drainer.node(), drainer.rebalancer(), drainer.antiEntropy());
            key = soleKeyOn(drainer, primaryInPair);
            drainer.node().putLocalVersioned(key, V0, 1L, 0L, 1L, 1L).await();
        }

        private byte[] soleKeyOn(Member drainer, boolean primary) {
            var rf = CONFIG.effectiveReplicationFactor(3);
            var pair = ConsistentHashRing.<NodeId>consistentHashRing();

            pair.addNode(DRAINER);
            pair.addNode(RETURNED);

            return java.util.stream.IntStream.range(0, 200)
                       .mapToObj(i -> ("k-" + i).getBytes(StandardCharsets.UTF_8))
                       .filter(k -> drainer.node().ring().nodesFor(k, rf).contains(DRAINER))
                       .filter(k -> pair.nodesFor(k, rf).get(primary ? 0 : 1).equals(DRAINER))
                       .findFirst().orElseThrow();
        }

        Promise<DeparturePushReport> push() {
            return members.get(DRAINER).rebalancer()
                          .pushOnDeparture(DepartureWindow.departureWindow(System.nanoTime() + 5_200_000_000L),
                                           TUNING,
                                           Set::of,
                                           DeparturePushObserver.noop());
        }

        /// The drainer's LEAVE: its traffic stops, and the survivors keep what they hold.
        void halt() {
            members.remove(DRAINER);
        }

        boolean holds(NodeId id) {
            return Option.option(members.get(id))
                         .map(m -> m.node().getLocal(key).await().or(Option.<byte[]>none()).isPresent())
                         .or(false);
        }

        boolean heldByALiveNode() {
            return holds(N_A) || holds(N_B);
        }

        void pause(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }

        void await(BooleanSupplier condition, String what) {
            var deadline = System.nanoTime() + 10_000_000_000L;

            while (!condition.getAsBoolean()) {
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("instrument: never saw " + what);
                }
                Thread.onSpinWait();
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }
        }

        private void deliver(NodeId target, ProtocolMessage message) {
            if (message instanceof DHTMessage.MigrationDataResponse r && r.ackRequested()) {
                pageTargets.add(target);
            }
            if (!absent.contains(target)) {
                Option.option(members.get(target)).onPresent(m -> route(m, message));
            }
        }

        private void route(Member m, ProtocolMessage message) {
            switch (message) {
                case DHTMessage.DigestRequest r -> m.node().handleDigestRequest(r, resp -> deliver(r.sender(), resp));
                case DHTMessage.StampDigestRequest r -> m.node().handleStampDigestRequest(r, resp -> deliver(r.sender(), resp));
                case DHTMessage.DigestResponse r -> m.antiEntropy().onDigestResponse(r);
                case DHTMessage.MigrationDataRequest r -> m.node().handleMigrationDataRequest(r, resp -> deliver(r.sender(), resp));
                case DHTMessage.MigrationPageRequest r -> m.node().handleMigrationPageRequest(r, resp -> deliver(r.sender(), resp));
                case DHTMessage.MigrationDataResponse r -> m.antiEntropy().onMigrationDataResponse(r);
                case DHTMessage.MigrationPageResponse r -> m.antiEntropy().onMigrationPageResponse(r);
                case DHTMessage.MigrationDataAck a -> m.rebalancer().onMigrationDataAck(a);
                default -> {}
            }
        }
    }

    private record Member(NodeId id, DHTNode node, DHTRebalancer rebalancer, DHTAntiEntropy antiEntropy) {}

    private static final class Gate implements OwnerEpochGate {
        private final AtomicReference<long[]> highWater = new AtomicReference<>(new long[]{0L, 0L, 0L});

        @Override
        public boolean isStale(byte[] key, long i, long t, long c) {
            return Arrays.compare(highWater.get(), new long[]{i, t, c}) > 0;
        }

        @Override
        public void advance(byte[] key, long i, long t, long c) {
            highWater.accumulateAndGet(new long[]{i, t, c}, (cur, p) -> Arrays.compare(p, cur) > 0 ? p : cur);
        }
    }
}
