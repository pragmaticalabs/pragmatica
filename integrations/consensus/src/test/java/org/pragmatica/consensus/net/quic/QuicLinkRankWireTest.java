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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.consensus.ConsensusCodecs;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NetCodecs;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.StreamType;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #2138 — the link generation travels as 8 trailing bytes of the dialer's CONTROL preamble. The preamble reader of
/// every earlier node reads one byte and ignores the rest, so the tail needs no `Hello` field and no wire tag. Pins
/// the bytes of both shapes, and — over real QUIC — that the acceptor ranks a link exactly as its dialer does and
/// leaves a legacy-shaped dial (the two-argument `connect`, the pre-change encoder's bytes) unranked.
@Timeout(60)
class QuicLinkRankWireTest {
    private static final NodeId SERVER_NODE = NodeId.randomNodeId();
    private static final NodeId CLIENT_NODE = NodeId.randomNodeId();
    private static final NodeAddress SERVER_ADDRESS = new NodeAddress("127.0.0.1", 9300);
    private static final NodeAddress CLIENT_ADDRESS = new NodeAddress("127.0.0.1", 9301);
    private static final TimeSpan AWAIT_TIMEOUT = TimeSpan.timeSpan(10).seconds();
    /// The CONTROL preamble the pre-#2138 dialer wrote: `new byte[]{(byte) StreamType.CONTROL.streamIndex()}`.
    private static final byte[] PRE_CHANGE_PREAMBLE = {6};

    private final List<QuicPeerConnection> accepted = new CopyOnWriteArrayList<>();
    private SliceCodec codec;
    private QuicClusterServer server;
    private QuicClusterClient client;

    @BeforeEach
    void setUp() {
        codec = SliceCodec.sliceCodec(FrameworkCodecs.frameworkCodecs(), combinedCodecs());
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close().await(AWAIT_TIMEOUT);
        }
        if (server != null) {
            server.stop().await(AWAIT_TIMEOUT);
        }
    }

    @Test
    void controlPreamble_withoutAGeneration_isByteIdenticalToTheEncoderBeforeTheChange() {
        assertThat(StreamType.CONTROL.streamIndex()).isEqualTo(PRE_CHANGE_PREAMBLE[0]);
        assertThat(PeerOpenedLaneRouter.controlPreamble(0L)).isEqualTo(PRE_CHANGE_PREAMBLE);
        assertThat(PeerOpenedLaneRouter.controlPreamble(-1L)).isEqualTo(PRE_CHANGE_PREAMBLE);
        assertThat(PeerOpenedLaneRouter.controlPreamble(LinkRank.MAX_GENERATION + 1)).isEqualTo(PRE_CHANGE_PREAMBLE);
    }

    @Test
    void controlPreamble_withAGeneration_isTheLaneByteThenEightBigEndianBytes() {
        assertThat(PeerOpenedLaneRouter.controlPreamble(0x0102030405060708L))
                .containsExactly(6, 1, 2, 3, 4, 5, 6, 7, 8);
        assertThat(PeerOpenedLaneRouter.controlPreamble(1L)).containsExactly(6, 0, 0, 0, 0, 0, 0, 0, 1);
    }

    /// The reader every earlier node has: [PeerOpenedLaneRouter#preambleLane] consumes one byte and checks nothing
    /// after it, so the 9-byte preamble is read as CONTROL.
    @Test
    void preambleLane_oldReader_acceptsTheNineBytePreambleAsControl() {
        var buf = Unpooled.wrappedBuffer(PeerOpenedLaneRouter.controlPreamble(42L));

        assertThat(PeerOpenedLaneRouter.preambleLane(buf).or((StreamType) null)).isEqualTo(StreamType.CONTROL);
    }

    @Test
    void preambleGeneration_roundTripsTheTailAfterTheLaneByte() {
        var buf = Unpooled.wrappedBuffer(PeerOpenedLaneRouter.controlPreamble(123_456_789L));

        PeerOpenedLaneRouter.preambleLane(buf);

        assertThat(PeerOpenedLaneRouter.preambleGeneration(buf)).isEqualTo(123_456_789L);
    }

    @Test
    void preambleGeneration_shortOrOutOfRangeTail_meansNoRank() {
        assertThat(generationAfterLaneByte(PRE_CHANGE_PREAMBLE)).as("bare lane byte").isZero();
        assertThat(generationAfterLaneByte(new byte[]{6, 0, 0, 0, 0, 0, 0, 1})).as("7-byte tail").isZero();
        assertThat(generationAfterLaneByte(new byte[]{6, 0, 0, 0, 0, 0, 0, 0, 0})).as("zero").isZero();
        assertThat(generationAfterLaneByte(new byte[]{6, -1, -1, -1, -1, -1, -1, -1, -1})).as("negative").isZero();
        assertThat(generationAfterLaneByte(new byte[]{6, 0x7F, -1, -1, -1, -1, -1, -1, -1})).as("Long.MAX_VALUE").isZero();
    }

    @Test
    void preambleGeneration_longerTail_readsTheFirstEightBytes() {
        assertThat(generationAfterLaneByte(new byte[]{6, 0, 0, 0, 0, 0, 0, 0, 9, 99, 99})).isEqualTo(9L);
    }

    private static long generationAfterLaneByte(byte[] frame) {
        ByteBuf buf = Unpooled.wrappedBuffer(frame);

        PeerOpenedLaneRouter.preambleLane(buf);

        return PeerOpenedLaneRouter.preambleGeneration(buf);
    }

    @Test
    void connect_withAGeneration_theAcceptorRanksTheLinkAsItsDialerDoes() {
        var port = startServer();

        var dialerSide = connect(port, 7L);

        assertThat(accepted).hasSize(1);
        assertThat(accepted.getFirst().linkRank().or((LinkRank) null)).isEqualTo(new LinkRank(7L, CLIENT_NODE));
        assertThat(dialerSide.linkRank().or((LinkRank) null)).as("both ends hold the same rank")
                                                              .isEqualTo(new LinkRank(7L, CLIENT_NODE));
    }

    @Test
    void connect_legacyShapedDial_connectsAndTheLinkIsUnranked() {
        var port = startServer();

        var dialerSide = connectLegacy(port);

        assertThat(dialerSide.isActive()).isTrue();
        assertThat(accepted).hasSize(1);
        assertThat(accepted.getFirst().initiator().or((NodeId) null)).isEqualTo(CLIENT_NODE);
        assertThat(accepted.getFirst().linkRank().isEmpty()).as("no generation announced: legacy rule").isTrue();
        assertThat(dialerSide.linkRank().isEmpty()).isTrue();
    }

    private int startServer() {
        var ssl = QuicTlsProvider.serverContext(ClusterTestTls.clusterTls("test-server"))
                                 .fold(cause -> fail("server ssl: " + cause.message()), context -> context);

        server = QuicClusterServer.quicClusterServer(SERVER_NODE,
                                                     SERVER_ADDRESS,
                                                     Map.of(),
                                                     codec,
                                                     codec,
                                                     QuicTransportMetrics.quicTransportMetrics(),
                                                     ssl,
                                                     Option.empty(),
                                                     (conn, _, _) -> accepted.add(conn),
                                                     (_, _) -> {});
        server.start(0).await(AWAIT_TIMEOUT).onFailure(cause -> fail("server start: " + cause.message()));

        return server.boundPort().fold(() -> fail("server not bound"), port -> port);
    }

    private QuicPeerConnection connect(int port, long generation) {
        return newClient().connect(SERVER_NODE, new InetSocketAddress("127.0.0.1", port), generation)
                          .await(AWAIT_TIMEOUT)
                          .fold(cause -> fail("connect: " + cause.message()), conn -> conn);
    }

    private QuicPeerConnection connectLegacy(int port) {
        return newClient().connect(SERVER_NODE, new InetSocketAddress("127.0.0.1", port))
                          .await(AWAIT_TIMEOUT)
                          .fold(cause -> fail("connect: " + cause.message()), conn -> conn);
    }

    private QuicClusterClient newClient() {
        var ssl = QuicTlsProvider.clientContext(ClusterTestTls.clusterTls("test-client"))
                                 .fold(cause -> fail("client ssl: " + cause.message()), context -> context);

        client = QuicClusterClient.quicClusterClient(CLIENT_NODE,
                                                     CLIENT_ADDRESS,
                                                     Map.of(),
                                                     codec,
                                                     codec,
                                                     QuicTransportMetrics.quicTransportMetrics(),
                                                     ssl,
                                                     Option.empty(),
                                                     (_, _) -> {});

        return client;
    }

    private static List<SliceCodec.TypeCodec<?>> combinedCodecs() {
        var all = new ArrayList<SliceCodec.TypeCodec<?>>();

        all.addAll(ConsensusCodecs.CODECS);
        all.addAll(NetCodecs.CODECS);

        return all;
    }
}
