// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// See LICENSE in the repository root for full terms.
package org.pragmatica.cluster.node.passive;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.cluster.state.kvstore.StructuredKey;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NetworkMessage.KVSyncRequest;
import org.pragmatica.consensus.net.NetworkMessage.KVSyncResponse;
import org.pragmatica.consensus.net.NetworkServiceMessage.ConnectionEstablished;
import org.pragmatica.consensus.net.NetworkServiceMessage.Send;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.Message;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.TlsConfig;
import org.pragmatica.net.tcp.security.SelfSignedCertificateProvider;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #2033: a passive node's KV snapshot request used to be one-shot. These tests drive the REAL
/// `PassiveNode` wiring (route entries, `SnapshotSync`) with a hand-driven clock and timer, so each
/// one reddens when the corresponding wiring or retry logic is reverted.
class PassiveNodeSnapshotRetryTest {
    record Key(String id) implements StructuredKey {}

    private static final NodeId SELF = nodeId("passive-1").unwrap();
    private static final NodeId PEER_A = nodeId("core-a").unwrap();
    private static final NodeId PEER_B = nodeId("core-b").unwrap();
    private static final long INITIAL = 1_000L;
    private static final long MAX = 4_000L;
    private static final long STALL = 10_000L;

    private final AtomicLong now = new AtomicLong(0);
    private final ArrayDeque<Scheduled> timers = new ArrayDeque<>();
    private final List<Send> sent = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private final AtomicBoolean restoreFails = new AtomicBoolean(false);
    private final AtomicInteger restores = new AtomicInteger();
    private MessageRouter router;

    record Scheduled(Runnable task, long delayMs) {}

    @BeforeEach
    void setUp() {
        var policy = new SnapshotSyncPolicy((task, delay) -> schedule(task, delay),
                                            now::get,
                                            INITIAL,
                                            MAX,
                                            STALL,
                                            observer());
        var self = NodeInfo.nodeInfo(SELF, NodeAddress.nodeAddress("localhost", 5000).unwrap());
        var config = new TopologyConfig(SELF, 3, timeSpan(1).seconds(), timeSpan(1).seconds(), List.of(self));
        var node = PassiveNode.<Key, String>passiveNode(config, serializer(), deserializer(), tls(), policy)
                              .unwrap();
        var mutable = MessageRouter.mutable();

        node.routeEntries().forEach(entry -> entry.entries().forEach(tuple -> {
            // The node's own Send handler would talk to a network that was never started; capture instead.
            if (tuple.first() != Send.class) {
                mutable.addRoute((Class<Message>) tuple.first(), (java.util.function.Consumer<Message>) tuple.last());
            }
        }));
        mutable.addRoute(Send.class, (java.util.function.Consumer<Send>) sent::add);
        router = mutable;
        node.delegateRouter().replaceDelegate(mutable);
    }

    @Test
    void droppedFirstResponse_isRetried_andSnapshotApplied() {
        connect(PEER_A);
        assertThat(requests()).as("initial request on first connection").hasSize(1);

        fireNextTimer(); // response never arrives; the retry timer re-asks
        assertThat(requests()).as("the lost request/response is re-asked").hasSize(2);

        respond(PEER_A);
        assertThat(events).isEmpty();
        fireNextTimerIfAny();
        assertThat(requests()).as("applied: nothing further is sent").hasSize(2);
    }

    @Test
    void failedRestore_isRetried() {
        connect(PEER_A);
        restoreFails.set(true);
        respond(PEER_A);
        assertThat(requests()).hasSize(1);

        fireNextTimer();
        assertThat(requests()).as("a failed restore leaves the node asking").hasSize(2);

        restoreFails.set(false);
        respond(PEER_A);
        fireNextTimerIfAny();
        assertThat(requests()).as("success after the retry stops the asking").hasSize(2);
    }

    @Test
    void afterApplied_noFurtherRequests_andLateResponseIgnored() {
        connect(PEER_A);
        respond(PEER_A);
        var before = requests().size();

        connect(PEER_B);
        fireNextTimerIfAny();
        fireNextTimerIfAny();

        assertThat(requests()).as("no request after a snapshot is applied").hasSize(before);
        assertThat(timers).as("the retry timer is not re-armed once applied").isEmpty();
    }

    @Test
    void lateDuplicateResponse_afterApplied_isNotRestoredAgain() {
        connect(PEER_A);
        respond(PEER_A);
        var restoresBefore = restores.get();

        respond(PEER_B);

        assertThat(restores.get())
                .as("a late snapshot would roll the store back over decisions applied since")
                .isEqualTo(restoresBefore);
    }

    @Test
    void laterConnectionToDifferentNode_asksThatNode() {
        connect(PEER_A);
        connect(PEER_B);

        assertThat(requests()).extracting(Send::target).containsExactly(PEER_A, PEER_B);
    }

    @Test
    void backoffGrows_andIsCapped() {
        connect(PEER_A);
        var delays = new ArrayList<Long>();

        for (int i = 0; i < 4; i++) {
            delays.add(timers.peekFirst().delayMs());
            fireNextTimer();
        }
        delays.add(timers.peekFirst().delayMs());

        assertThat(delays).containsExactly(1_000L, 2_000L, 4_000L, 4_000L, 4_000L);
    }

    @Test
    void stalled_firesOnce_thenRecoveryFires_andNotBefore() {
        connect(PEER_A);

        while (now.get() < STALL + 2 * MAX) {
            fireNextTimer();
        }
        assertThat(events).as("one stall event, flood-guarded across many ticks").containsExactly("stalled");

        respond(PEER_A);
        assertThat(events).containsExactly("stalled", "recovered");
    }

    @Test
    void appliedBeforeBound_emitsNeitherEvent() {
        connect(PEER_A);
        fireNextTimer();
        respond(PEER_A);

        assertThat(events).as("no stall, so no recovery event either").isEmpty();
    }

    // === helpers ===

    /// Built as production and Ember build it: a CA derived from a shared secret, then an identity under it.
    private static TlsConfig tls() {
        var provider = SelfSignedCertificateProvider.selfSignedCertificateProvider("passive-test-secret".getBytes(StandardCharsets.UTF_8))
                                                    .unwrap();

        return TlsConfig.fromProvider(provider, SELF.id(), "localhost").unwrap();
    }

    private SnapshotSyncObserver observer() {
        return new SnapshotSyncObserver() {
            @Override public Unit stalled(NodeId self, int attempts, long elapsedMs) { events.add("stalled"); return unit(); }
            @Override public Unit recovered(NodeId self, int attempts, long elapsedMs) { events.add("recovered"); return unit(); }
        };
    }

    private Unit schedule(Runnable task, long delay) {
        timers.add(new Scheduled(task, delay));

        return unit();
    }

    private void connect(NodeId peer) {
        router.route(ConnectionEstablished.connectionEstablished(peer));
    }

    private void respond(NodeId from) {
        router.route(new KVSyncResponse(from, new byte[]{1}));
    }

    private void fireNextTimer() {
        var next = timers.removeFirst();

        now.addAndGet(next.delayMs());
        next.task().run();
    }

    private void fireNextTimerIfAny() {
        if (!timers.isEmpty()) {
            fireNextTimer();
        }
    }

    private List<Send> requests() {
        return sent.stream().filter(send -> send.payload() instanceof KVSyncRequest).toList();
    }

    private Serializer serializer() {
        return new Serializer() {
            @Override public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    private Deserializer deserializer() {
        return new Deserializer() {
            @Override @SuppressWarnings("unchecked")
            public <T> T read(ByteBuf byteBuf) {
                restores.incrementAndGet();

                if (restoreFails.get()) {
                    throw new IllegalStateException("corrupt snapshot");
                }
                return (T) new HashMap<Key, String>(Map.of(new Key("k"), "v"));
            }
        };
    }
}
