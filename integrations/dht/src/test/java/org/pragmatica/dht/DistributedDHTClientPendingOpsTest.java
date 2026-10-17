package org.pragmatica.dht;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.DHTNode.dhtNode;
import static org.pragmatica.dht.DistributedDHTClient.distributedDHTClient;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #1776 follow-ups. Put, Remove and Exists replies delivered through the BASE client resolve an operation issued on a
/// DERIVED one (the GET case is pinned in [DistributedDHTClientDerivedReplyTest]); and an unanswered request leaves no
/// entry in the shared reply table once its operation times out, with a late reply then ignored.
class DistributedDHTClientPendingOpsTest {
    private static final NodeId LOCAL = new NodeId("local");
    private static final NodeId REPLICA = new NodeId("replica-1");
    private static final DHTConfig BASE_CONFIG = new DHTConfig(3, 2, 2, timeSpan(10).seconds());
    private static final DHTConfig SHORT_CONFIG = new DHTConfig(1, 1, 1, timeSpan(300).millis());

    private final CopyOnWriteArrayList<ProtocolMessage> sent = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<NodeId> sentTo = new CopyOnWriteArrayList<>();
    private ConsistentHashRing<NodeId> ring;
    private DistributedDHTClient base;

    @BeforeEach
    void setUp() {
        ring = ConsistentHashRing.<NodeId> consistentHashRing();

        for (var i = 1; i <= 6; i++) {
            ring.addNode(new NodeId("replica-" + i));
        }
        DHTNetwork network = new DHTNetwork() {
            @Override
            public void send(NodeId nodeId, ProtocolMessage message) {
                sentTo.add(nodeId);
                sent.add(message);
            }

            @Override
            public Promise<WriteOutcome> sendOutcome(NodeId target, ProtocolMessage message) {
                send(target, message);
                return Promise.success(new WriteOutcome.Sent(target));
            }
        };
        base = distributedDHTClient(dhtNode(LOCAL, memoryStorageEngine(), ring, BASE_CONFIG), network, BASE_CONFIG);
    }

    private static byte[] key() {
        return new byte[]{1, 2, 3};
    }

    private <R extends DHTMessage> List<R> requests(Class<R> type) {
        return sent.stream().filter(type::isInstance).map(type::cast).toList();
    }

    @Test
    void put_viaDerivedClient_resolves_whenRepliesArriveViaBase() {
        var derived = base.withResolveFallbackObserver(ResolveFallbackObserver.noop());
        var op = derived.put(key(), new byte[]{9});
        var requests = requests(DHTMessage.PutRequest.class);

        assertThat(requests).hasSize(3);
        requests.forEach(r -> base.onPutResponse(new DHTMessage.PutResponse(r.requestId(), REPLICA, true, false, false, false, false)));

        assertThat(op.await(timeSpan(2).seconds()).isSuccess()).isTrue();
        assertThat(base.pendingOperations()).isZero();
    }

    @Test
    void remove_viaDerivedClient_resolves_whenRepliesArriveViaBase() {
        var derived = base.withResolveFallbackObserver(ResolveFallbackObserver.noop());
        var op = derived.remove(key());
        var requests = requests(DHTMessage.RemoveRequest.class);

        assertThat(requests).hasSize(3);
        requests.forEach(r -> base.onRemoveResponse(new DHTMessage.RemoveResponse(r.requestId(), REPLICA, true)));

        assertThat(op.await(timeSpan(2).seconds()).isSuccess()).isTrue();
        assertThat(base.pendingOperations()).isZero();
    }

    @Test
    void exists_viaDerivedClient_resolves_whenRepliesArriveViaBase() {
        var derived = base.withResolveFallbackObserver(ResolveFallbackObserver.noop());
        var op = derived.exists(key());
        var requests = requests(DHTMessage.ExistsRequest.class);

        assertThat(requests).hasSize(3);
        requests.forEach(r -> base.onExistsResponse(new DHTMessage.ExistsResponse(r.requestId(), REPLICA, true, DHTMessage.Readiness.SERVING)));

        boolean found = op.await(timeSpan(2).seconds()).fold(_ -> false, value -> value);

        assertThat(found).isTrue();
        assertThat(base.pendingOperations()).isZero();
    }

    @Test
    void scopedPut_viaDerivedClient_resolves_whenReplyArrivesViaBase() {
        var op = base.scoped(SHORT_CONFIG).put(key(), new byte[]{9});
        var requests = requests(DHTMessage.PutRequest.class);

        assertThat(requests).hasSize(1);
        base.onPutResponse(new DHTMessage.PutResponse(requests.getFirst().requestId(), REPLICA, true, false, false, false, false));

        assertThat(op.await(timeSpan(2).seconds()).isSuccess()).isTrue();
    }

    private void assertTimedOutRequestLeavesNoEntry(Function<DHTClient, Promise<?>> operation,
                                                    Class<? extends DHTMessage> requestType,
                                                    Function<String, DHTMessage> lateReply,
                                                    java.util.function.Consumer<DHTMessage> deliver) {
        var op = operation.apply(base.scoped(SHORT_CONFIG));
        var requests = sent.stream().filter(requestType::isInstance).toList();

        assertThat(requests).hasSize(1);
        assertThat(base.pendingOperations()).as("the request is pending while the operation runs").isEqualTo(1);

        assertThat(op.await(timeSpan(5).seconds()).isFailure()).as("unanswered operation fails by timeout").isTrue();
        // the entry is dropped by the same timeout that failed the promise; allow for it to run just after
        for (var i = 0; i < 200 && base.pendingOperations() != 0; i++) {
            sleepQuietly(10);
        }
        assertThat(base.pendingOperations()).as("no entry left after the operation timed out").isZero();

        var requestId = requests.getFirst() instanceof DHTMessage.GetRequest g ? g.requestId()
                        : requests.getFirst() instanceof DHTMessage.PutRequest p ? p.requestId()
                        : requests.getFirst() instanceof DHTMessage.RemoveRequest r ? r.requestId()
                        : ((DHTMessage.ExistsRequest) requests.getFirst()).requestId();

        deliver.accept(lateReply.apply(requestId));
        assertThat(base.pendingOperations()).isZero();
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void timedOutGet_leavesNoPendingEntry_andLateReplyIsIgnored() {
        assertTimedOutRequestLeavesNoEntry(c -> c.get(key()),
                                           DHTMessage.GetRequest.class,
                                           id -> new DHTMessage.GetResponse(id, REPLICA, Option.some(new byte[]{1}), DHTMessage.Readiness.SERVING),
                                           m -> base.onGetResponse((DHTMessage.GetResponse) m));
    }

    @Test
    void timedOutPut_leavesNoPendingEntry_andLateReplyIsIgnored() {
        assertTimedOutRequestLeavesNoEntry(c -> c.put(key(), new byte[]{1}),
                                           DHTMessage.PutRequest.class,
                                           id -> new DHTMessage.PutResponse(id, REPLICA, true, false, false, false, false),
                                           m -> base.onPutResponse((DHTMessage.PutResponse) m));
    }

    @Test
    void timedOutRemove_leavesNoPendingEntry_andLateReplyIsIgnored() {
        assertTimedOutRequestLeavesNoEntry(c -> c.remove(key()),
                                           DHTMessage.RemoveRequest.class,
                                           id -> new DHTMessage.RemoveResponse(id, REPLICA, true),
                                           m -> base.onRemoveResponse((DHTMessage.RemoveResponse) m));
    }

    @Test
    void timedOutExists_leavesNoPendingEntry_andLateReplyIsIgnored() {
        assertTimedOutRequestLeavesNoEntry(c -> c.exists(key()),
                                           DHTMessage.ExistsRequest.class,
                                           id -> new DHTMessage.ExistsResponse(id, REPLICA, true, DHTMessage.Readiness.SERVING),
                                           m -> base.onExistsResponse((DHTMessage.ExistsResponse) m));
    }

    private DistributedDHTClient shortWindowClient() {
        var config = new DHTConfig(3, 2, 2, timeSpan(600).millis());
        var node = dhtNode(LOCAL, memoryStorageEngine(), ring, config);

        return distributedDHTClient(node, new DHTNetwork() {
            @Override
            public void send(NodeId nodeId, ProtocolMessage message) {
                sentTo.add(nodeId);
                sent.add(message);
            }

            @Override
            public Promise<WriteOutcome> sendOutcome(NodeId target, ProtocolMessage message) {
                send(target, message);
                return Promise.success(new WriteOutcome.Sent(target));
            }
        }, config);
    }

    private static void awaitPending(DistributedDHTClient client, int expected) {
        for (var i = 0; i < 300 && client.pendingOperations() != expected; i++) {
            sleepQuietly(10);
        }
        assertThat(client.pendingOperations()).isEqualTo(expected);
    }

    private static GetResponseFactory present() {
        return (id, sender) -> new DHTMessage.GetResponse(id, sender, Option.some(new byte[]{1}), DHTMessage.Readiness.SERVING);
    }

    private interface GetResponseFactory {
        DHTMessage.GetResponse of(String requestId, NodeId sender);
    }

    /// RF3 / R2: two replies make the quorum and the read returns; the third replica stays silent and in the ring.
    /// Its entry must not outlive the operation window, yet a reply that does land inside the window is consumed.
    @Test
    void silentReplicaAfterQuorum_entryExpires_andLateReplyAfterExpiryIsIgnored() {
        var client = shortWindowClient().withResolveFallbackObserver(ResolveFallbackObserver.noop());
        var op = client.get(key());
        var requests = requests(DHTMessage.GetRequest.class);

        assertThat(requests).hasSize(3);
        requests.subList(0, 2).forEach(r -> client.onGetResponse(present().of(r.requestId(), REPLICA)));

        assertThat(op.await(timeSpan(2).seconds()).isSuccess()).as("quorum of two resolves the read").isTrue();
        assertThat(client.pendingOperations()).as("the silent replica's entry is still held inside the window").isEqualTo(1);

        awaitPending(client, 0);
        client.onGetResponse(present().of(requests.get(2).requestId(), REPLICA));
        assertThat(client.pendingOperations()).isZero();
    }

    /// The late reply lands 200 ms into a 600 ms window: the entry must still be there to take it. An expiry that fires
    /// at (almost) once leaves nothing, and the held-count assertion fails.
    @Test
    void lateReplyInsideTheWindow_isStillConsumed_andReleasesItsEntry() {
        var client = shortWindowClient();
        var op = client.get(key());
        var requests = requests(DHTMessage.GetRequest.class);

        requests.subList(0, 2).forEach(r -> client.onGetResponse(present().of(r.requestId(), REPLICA)));
        assertThat(op.await(timeSpan(2).seconds()).isSuccess()).isTrue();

        sleepQuietly(200);
        assertThat(client.pendingOperations()).as("the silent replica's entry is still held 200 ms into the window").isEqualTo(1);

        client.onGetResponse(present().of(requests.get(2).requestId(), REPLICA));

        assertThat(client.pendingOperations()).as("the late reply took its entry").isZero();
    }

    /// Once every reply of an operation has arrived its expiry timer is cancelled, not left to run out the window.
    @Test
    void allRepliesArrived_leavesNoExpiryTimerArmed() {
        var client = shortWindowClient();
        var op = client.get(key());
        var requests = requests(DHTMessage.GetRequest.class);

        assertThat(client.pendingExpiries()).isEqualTo(3);
        requests.forEach(r -> client.onGetResponse(present().of(r.requestId(), REPLICA)));

        assertThat(op.await(timeSpan(2).seconds()).isSuccess()).isTrue();
        // the cancel runs on a callback; give it up to 300 ms, still well inside the 600 ms window a left-armed timer
        // would run out
        for (var i = 0; i < 30 && client.pendingExpiries() != 0; i++) {
            sleepQuietly(10);
        }
        assertThat(client.pendingExpiries()).as("expiry timers armed after every reply arrived").isZero();
        assertThat(client.pendingOperations()).isZero();
    }

    /// A departed target is replaced by a read to another replica; if that replacement never answers, its entry
    /// expires like any other.
    @Test
    void silentReplacementRead_entryExpires() {
        var client = shortWindowClient();
        var op = client.get(key());
        var initial = requests(DHTMessage.GetRequest.class);

        assertThat(initial).hasSize(3);
        ring.removeNode(sentTo.get(0));

        var all = requests(DHTMessage.GetRequest.class);

        assertThat(all).as("the departure dispatched a replacement read").hasSize(4);
        client.onGetResponse(present().of(initial.get(1).requestId(), REPLICA));
        client.onGetResponse(present().of(initial.get(2).requestId(), REPLICA));

        assertThat(op.await(timeSpan(2).seconds()).isSuccess()).isTrue();
        assertThat(client.pendingOperations()).as("the replacement is silent and still held").isEqualTo(1);

        awaitPending(client, 0);
        client.onGetResponse(present().of(all.get(3).requestId(), REPLICA));
        assertThat(client.pendingOperations()).isZero();
    }
}
