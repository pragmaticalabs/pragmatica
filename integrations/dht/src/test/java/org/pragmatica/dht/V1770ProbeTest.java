package org.pragmatica.dht;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.DHTNode.dhtNode;
import static org.pragmatica.dht.DistributedDHTClient.distributedDHTClient;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// v1770 adversarial probes against PR #1770. Not for merge as-is.
class V1770ProbeTest {
    private static final NodeId LOCAL = new NodeId("local");
    private static final DHTConfig CONFIG = new DHTConfig(3, 2, 2, timeSpan(1).seconds());

    private record Sent(NodeId target, ProtocolMessage message) {}

    private static final class Net implements DHTNetwork {
        final CopyOnWriteArrayList<Sent> sent = new CopyOnWriteArrayList<>();
        final Set<NodeId> departAtSend = new HashSet<>();
        final Set<NodeId> refuseAtSend = new HashSet<>();
        ConsistentHashRing<NodeId> ring;

        @Override
        public void send(NodeId nodeId, ProtocolMessage message) {
            sent.add(new Sent(nodeId, message));
        }

        @Override
        public Promise<WriteOutcome> sendOutcome(NodeId target, ProtocolMessage message) {
            send(target, message);
            if (departAtSend.remove(target)) {
                // the departure interleaves between pendingOps.put and the refusal the transport reports for it
                ring.removeNode(target);
                return Promise.success(new WriteOutcome.ConnectionDead(target));
            }
            if (refuseAtSend.remove(target)) {
                return Promise.success(new WriteOutcome.ConnectionDead(target));
            }
            return Promise.success(new WriteOutcome.Sent(target));
        }
    }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private ConsistentHashRing<NodeId> ring;
    private Net net;
    private DistributedDHTClient client;

    private void setUp() {
        ring = ConsistentHashRing.consistentHashRing();
        for (var i = 1; i <= 12; i++) {
            ring.addNode(new NodeId("replica-" + i));
        }
        net = new Net();
        net.ring = ring;
        client = distributedDHTClient(dhtNode(LOCAL, memoryStorageEngine(), ring, CONFIG), net, CONFIG);
    }

    private List<Sent> gets() {
        return net.sent.stream().filter(s -> s.message() instanceof DHTMessage.GetRequest).toList();
    }

    private void reply(Sent s, Option<byte[]> v) {
        var req = (DHTMessage.GetRequest) s.message();
        client.onGetResponse(new DHTMessage.GetResponse(req.requestId(), s.target(), v));
    }

    /// W=2 put reached A and C only (B missed it): the value is on 2 of the 3 R-set replicas, so any
    /// R=2 quorum of {A,B,C} contains a holder. A departs before replying; its replacement X is the new
    /// ring successor and does not hold the key yet (the survivor rebalance runs AFTER removeNode's
    /// listeners). B (absent) + X (absent) must not make the read resolve absent while holder C still owes a reply.
    @Test
    void replacementsAbsentAnswer_mustNotOutvoteAPendingHolder() {
        setUp();
        var read = client.get(b("k1"));
        var initial = gets();
        assertThat(initial).hasSize(3);
        var a = initial.get(0);
        var bReq = initial.get(1);
        var c = initial.get(2);

        reply(bReq, Option.none());          // B missed the W=2 write
        ring.removeNode(a.target());         // A (holder) leaves before answering
        var afterDeparture = gets().size();
        if (afterDeparture > 3) {
            reply(gets().get(3), Option.none());   // X: new successor, not yet rebalanced
        }

        // any fallback probe sent now means the quorum stage already resolved as a MISS
        var fallbackProbesBeforeHolderAnswered = gets().size() - afterDeparture;
        reply(c, Option.some(b("v1")));      // holder C answers

        var result = read.await(timeSpan(5).seconds());
        assertThat(fallbackProbesBeforeHolderAnswered).as("quorum resolved MISS before holder C answered").isZero();
        assertThat(result.map(Option::isPresent).or(false)).as("read must find the value held by C").isTrue();
    }

    /// A departure that lands while the INITIAL dispatch loop is still running (first target departs as it
    /// is sent) must not pick a not-yet-dispatched ORIGINAL target as the replacement: that replica would be
    /// addressed twice, under two correlation ids, and its single answer would fill two quorum slots.
    @Test
    void departureDuringInitialDispatch_mustNotAddressAnOriginalTargetTwice() {
        setUp();
        var targets = ring.nodesFor(b("k1"), 3);
        net.departAtSend.add(targets.get(0));

        var read = client.get(b("k1"));

        var perTarget = new java.util.HashMap<NodeId, Integer>();
        gets().forEach(s -> perTarget.merge(s.target(), 1, Integer::sum));
        // the doubly-addressed replica answers "absent" on both requests while holder targets[2] is pending
        gets().stream().filter(s -> s.target().equals(targets.get(1))).forEach(s -> reply(s, Option.none()));
        var resolvedOnOneReplica = read.isResolved();

        assertThat(perTarget.values()).as("requests per target " + perTarget).allMatch(n -> n == 1);
        assertThat(resolvedOnOneReplica).as("quorum of 2 resolved on ONE replica's answers").isFalse();
    }

    /// The LAST target departs in the window between pendingOps.put and the transport refusal the
    /// departure causes; another target is refused outright. Each slot must be owned by exactly one side
    /// (replacement OR refusal): counting the departed slot twice fails a quorum that target 0 plus the
    /// replacement can still reach.
    @Test
    void departureDuringDispatch_mustNotCountASlotTwice() {
        setUp();
        var targets = ring.nodesFor(b("k1"), 3);
        net.refuseAtSend.add(targets.get(1));
        net.departAtSend.add(targets.get(2));

        var read = client.get(b("k1"));

        var failedEarly = read.isResolved();
        gets().stream()
              .filter(s -> !targets.subList(1, 3).contains(s.target()))
              .forEach(s -> reply(s, Option.some(b("v1"))));
        var result = read.await(timeSpan(5).seconds());

        assertThat(failedEarly).as("read failed before the replacement could answer").isFalse();
        assertThat(result.map(Option::isPresent).or(false)).isTrue();
    }

    /// If removeNode ran listeners while holding the ring's write lock, a listener that hands off to
    /// ANOTHER thread which reads the ring would block (same-thread reentrancy hides this).
    @Test
    void listener_canBeServedByAnotherThreadReadingTheRing() throws InterruptedException {
        var r = ConsistentHashRing.<NodeId>consistentHashRing();
        r.addNode(new NodeId("a"));
        r.addNode(new NodeId("b"));
        var seen = new AtomicReference<Integer>();
        r.onNodeRemoved(_ -> {
            var t = Thread.ofPlatform().start(() -> seen.set(r.nodeCount()));
            try {
                t.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        r.removeNode(new NodeId("a"));

        assertThat(seen.get()).isEqualTo(1);
    }

    /// Stress: the replacement pick must be atomic per candidate across concurrent departures.
    @Test
    void nextReplacement_isAtomic_underContention() throws InterruptedException {
        var candidates = new ArrayList<NodeId>();
        for (var i = 0; i < 64; i++) {
            candidates.add(new NodeId("n" + i));
        }
        for (var round = 0; round < 2000; round++) {
            var read = InFlightRead.inFlightRead(new byte[]{1},
                                                 QuorumCollector.quorumCollector(2, 3, Promise.promise()),
                                                 System.nanoTime() + 3_600_000_000_000L,
                                                 1000);
            var picked = java.util.Collections.synchronizedList(new ArrayList<NodeId>());
            var start = new java.util.concurrent.CountDownLatch(1);
            var threads = new ArrayList<Thread>();
            for (var t = 0; t < 8; t++) {
                threads.add(Thread.ofPlatform().start(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    for (var k = 0; k < 4; k++) {
                        read.nextReplacement(candidates).onPresent(picked::add);
                    }
                }));
            }
            start.countDown();
            for (var t : threads) {
                t.join();
            }
            assertThat(picked).as("round " + round).doesNotHaveDuplicates();
        }
    }

    /// Author's get_doesNotReissue_afterReadCompleted, repeated: counts runs where a departure after
    /// await() still produced a replacement request (unsubscribe not yet run).
    @Test
    void unsubscribe_isOrderedBeforeAwaitReturns_repeated() {
        var leaks = 0;
        for (var run = 0; run < 500; run++) {
            setUp();
            var read = client.get(b("k1"));
            var initial = gets();
            reply(initial.get(0), Option.some(b("v1")));
            reply(initial.get(1), Option.some(b("v1")));
            read.await(timeSpan(2).seconds());
            ring.removeNode(initial.get(2).target());
            if (gets().size() != 3) {
                leaks++;
            }
        }
        assertThat(leaks).as("runs (of 500) re-issuing after await returned").isZero();
    }
}
