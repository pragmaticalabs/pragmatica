/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.pragmatica.consensus.net.quic;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.BootTokens;
import org.pragmatica.consensus.net.quic.TransportTripwireFixtures.TripwireTopology;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.messaging.StreamType;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.serialization.SliceCodec;

import io.netty.handler.codec.quic.QuicStreamChannel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.awaitTrue;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.connected;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.infoOf;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.startedNetwork;

/// #3313 T6 — the lane streams' writability event reaches the backpressure-episode closer at every production
/// construction of a [QuicLaneDataHandler]. Before #3313 each construction left the event as a no-op, so an episode
/// whose stream turned writable with no write left stayed open.
///
/// The six constructions, each driven on ONE real QUIC connection (dialer = [QuicClusterClient], acceptor =
/// [QuicClusterServer]) built with a recording settler: the acceptor's CONTROL stream (Hello), the acceptor's lazily
/// opened lane, the dialer's CONTROL stream (Hello response), the dialer's data lanes, the dialer's lazily opened lane,
/// and the peer-opened lanes both ends attach through [PeerOpenedLaneRouter]. Each stream's pipeline fires the event;
/// the stream must reach its end's settler. A last test shows [QuicClusterNetwork] hands its own closer to both ends.
@Timeout(60)
class QuicWritabilityEventWiringTest {
    private static final NodeId ACCEPTOR = new NodeId("wire-acceptor");
    private static final NodeId DIALER = new NodeId("wire-dialer");
    private static final NodeAddress UNUSED_ADDRESS = new NodeAddress("127.0.0.1", 9000);
    private static final TimeSpan AWAIT = TimeSpan.timeSpan(10).seconds();

    private final SliceCodec codec = LaneProbe.codec();
    private final Set<QuicStreamChannel> settledAtAcceptor = ConcurrentHashMap.newKeySet();
    private final Set<QuicStreamChannel> settledAtDialer = ConcurrentHashMap.newKeySet();
    private final AtomicReference<QuicPeerConnection> acceptorSide = new AtomicReference<>();
    private final List<QuicClusterNetwork> networks = new ArrayList<>();
    private QuicClusterServer server;
    private QuicClusterClient client;
    private QuicPeerConnection dialerSide;

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close().await(AWAIT);
        }
        if (server != null) {
            server.stop().await(AWAIT);
        }
        networks.forEach(network -> network.stop().await(AWAIT));
    }

    @Test
    void acceptorControlStream_deliversTheWritabilityEvent() {
        connect();

        assertDelivers(acceptorSide.get().stream(StreamType.CONTROL).unwrap(), settledAtAcceptor, "acceptor CONTROL (Hello)");
    }

    @Test
    void acceptorPeerOpenedLane_deliversTheWritabilityEvent() {
        connect();

        assertDelivers(acceptorSide.get().stream(StreamType.CONSENSUS).unwrap(),
                       settledAtAcceptor,
                       "acceptor's router-attached CONSENSUS lane (opened by the dialer)");
    }

    @Test
    void acceptorLazilyOpenedLane_deliversTheWritabilityEvent() {
        connect();
        var opened = reopen(acceptorSide.get(), StreamType.KV);

        assertDelivers(opened, settledAtAcceptor, "acceptor's lazily opened KV lane");
    }

    @Test
    void dialerControlStream_deliversTheWritabilityEvent() {
        connect();

        assertDelivers(dialerSide.stream(StreamType.CONTROL).unwrap(), settledAtDialer, "dialer CONTROL (Hello response)");
    }

    @Test
    void dialerDataLane_deliversTheWritabilityEvent() {
        connect();

        assertDelivers(dialerSide.stream(StreamType.CONSENSUS).unwrap(), settledAtDialer, "dialer's CONSENSUS data lane");
    }

    @Test
    void dialerLazilyOpenedLane_deliversTheWritabilityEvent() {
        connect();
        var opened = reopen(dialerSide, StreamType.METRICS);

        assertDelivers(opened, settledAtDialer, "dialer's lazily opened METRICS lane");
    }

    @Test
    void dialerPeerOpenedLane_deliversTheWritabilityEvent() {
        connect();
        var opened = reopen(acceptorSide.get(), StreamType.FORWARD);

        awaitTrue(() -> dialerSide.stream(StreamType.FORWARD)
                                  .filter(stream -> stream.streamId() == opened.streamId())
                                  .isPresent(),
                  AWAIT,
                  "the dialer attached the acceptor-opened FORWARD stream");
        assertDelivers(dialerSide.stream(StreamType.FORWARD).unwrap(),
                       settledAtDialer,
                       "dialer's router-attached FORWARD lane (opened by the acceptor)");
    }

    /// The network builds its server and client with its own closer: the event on a lane stream of either end runs it.
    @Test
    void network_handsItsCloserToTheServerAndTheClient() {
        var dialerAt = new NodeId("aaa-wire");
        var acceptorAt = new NodeId("zzz-wire");
        var dialing = startedNetwork(new TripwireTopology(dialerAt, false, TimeSpan.timeSpan(30).seconds()),
                                     MessageRouter.mutable(),
                                     networks);
        var accepting = startedNetwork(new TripwireTopology(acceptorAt, false, TimeSpan.timeSpan(30).seconds()),
                                       MessageRouter.mutable(),
                                       networks);
        var eventsAtDialer = ConcurrentHashMap.<QuicStreamChannel>newKeySet();
        var eventsAtAcceptor = ConcurrentHashMap.<QuicStreamChannel>newKeySet();

        dialing.writabilityEventObserverForTests(eventsAtDialer::add);
        accepting.writabilityEventObserverForTests(eventsAtAcceptor::add);
        dialing.dialForTests(infoOf(acceptorAt, accepting), false);
        awaitTrue(() -> connected(dialing, acceptorAt) && connected(accepting, dialerAt), AWAIT, "both ends CONNECTED");

        assertDelivers(laneOf(dialing, acceptorAt, StreamType.CONSENSUS), eventsAtDialer, "network's client lane");
        assertDelivers(laneOf(accepting, dialerAt, StreamType.CONSENSUS), eventsAtAcceptor, "network's server lane");
    }

    private static QuicStreamChannel laneOf(QuicClusterNetwork network, NodeId peer, StreamType lane) {
        return network.activeConnectionForTests(peer)
                      .flatMap(connection -> connection.stream(lane))
                      .fold(() -> fail("no " + lane + " stream to " + peer), stream -> stream);
    }

    /// Fires the event in the stream's own pipeline (a live idle stream is writable) and waits for its settler.
    private static void assertDelivers(QuicStreamChannel stream, Set<QuicStreamChannel> settled, String site) {
        settled.remove(stream);
        stream.pipeline().fireChannelWritabilityChanged();

        awaitTrue(() -> settled.contains(stream), AWAIT, "the writability event of the " + site + " reached the settler");
        assertThat(settled).as("the %s's event reached the settler", site).contains(stream);
    }

    /// Releases `lane` at `side` (its other end is told by the lane-end marker), then lazily re-opens it from `side`:
    /// the stream is the one the lazy-open construction built, since no incumbent remains to keep the lane.
    private static QuicStreamChannel reopen(QuicPeerConnection side, StreamType lane) {
        side.streamEnded(lane, side.stream(lane).unwrap());
        var opened = new CompletableFuture<Option<QuicStreamChannel>>();

        side.openLane(lane, opened::complete);

        return opened.orTimeout(AWAIT.millis(), TimeUnit.MILLISECONDS)
                     .join()
                     .fold(() -> fail("the lazy open of " + lane + " failed"), stream -> stream);
    }

    private void connect() {
        var serverSsl = QuicTlsProvider.serverContext(ClusterTestTls.clusterTls("wire-server"))
                                       .fold(_ -> fail("server ssl"), ssl -> ssl);
        var clientSsl = QuicTlsProvider.clientContext(ClusterTestTls.clusterTls("wire-client"))
                                       .fold(_ -> fail("client ssl"), ssl -> ssl);

        server = QuicClusterServer.quicClusterServer(ACCEPTOR, UNUSED_ADDRESS, Map.of(), codec, codec,
                                                     QuicTransportMetrics.quicTransportMetrics(), serverSsl,
                                                     Option.empty(),
                                                     (connection, _, _) -> acceptorSide.set(connection),
                                                     (_, _) -> {},
                                                     BootTokens.bootTokens(0L),
                                                     (_, stream) -> settledAtAcceptor.add(stream));
        server.start(0).await(AWAIT).onFailure(cause -> fail("server start: " + cause.message()));
        var port = server.boundPort().fold(() -> fail("server not bound"), bound -> bound);

        client = QuicClusterClient.quicClusterClient(DIALER, UNUSED_ADDRESS, Map.of(), codec, codec,
                                                     QuicTransportMetrics.quicTransportMetrics(), clientSsl,
                                                     Option.empty(),
                                                     (_, _) -> {},
                                                     BootTokens.bootTokens(0L),
                                                     (_, stream) -> settledAtDialer.add(stream));
        dialerSide = client.connect(ACCEPTOR, new InetSocketAddress("127.0.0.1", port))
                           .await(AWAIT)
                           .fold(cause -> fail("dial: " + cause.message()), connection -> connection);
        awaitTrue(() -> acceptorSide.get() != null && everyLanePresent(acceptorSide.get()),
                  AWAIT,
                  "the acceptor registered every lane the dialer opened");
    }

    private static boolean everyLanePresent(QuicPeerConnection connection) {
        return Arrays.stream(StreamType.values()).allMatch(lane -> connection.stream(lane).isPresent());
    }
}
