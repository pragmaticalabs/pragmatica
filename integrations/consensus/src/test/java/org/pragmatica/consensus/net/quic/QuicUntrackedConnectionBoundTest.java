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
import java.util.concurrent.TimeUnit;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.quic.InsecureQuicTokenHandler;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicClientCodecBuilder;
import io.netty.handler.codec.quic.QuicServerCodecBuilder;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.codec.quic.QuicStreamType;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.consensus.ConsensusCodecs;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NetCodecs;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import static org.assertj.core.api.Assertions.assertThat;

/// #1933 item 2: QUIC connections the cluster network does not track have no liveness bound, because the QUIC idle timeout is
/// disabled (cluster connections are persistent). The Hello timeout used to close only the STREAM it ran on, so a connection that
/// never completed its Hello (never opened a stream, or opened one and said nothing) stayed open on the acceptor for the life
/// of the process, and a dialer whose acceptor never answered its Hello kept an active connection after the dial failed.
/// The bound is the Hello timeout (15 s in production, shortened here through a test seam): a connection that has not completed
/// its Hello by then is closed, from either side. A connection that DID complete its Hello is untouched (no global idle timeout).
class QuicUntrackedConnectionBoundTest {
    private static final NodeId SERVER_NODE = NodeId.randomNodeId();
    private static final NodeId CLIENT_NODE = NodeId.randomNodeId();
    private static final TimeSpan AWAIT = TimeSpan.timeSpan(10).seconds();
    /// The shortened Hello bound used by every test here, and how long a test waits for the close after it.
    private static final long HELLO_BOUND_MS = 400;
    private static final long CLOSE_WAIT_MS = 5_000;

    private final List<AutoCloseable> cleanup = new ArrayList<>();
    private SliceCodec codec;
    private EventLoopGroup rawLoop;

    @BeforeEach
    void setUp() {
        codec = SliceCodec.sliceCodec(FrameworkCodecs.frameworkCodecs(), combinedCodecs());
        rawLoop = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
    }

    @AfterEach
    void tearDown() throws Exception {
        for (var closeable : cleanup.reversed()) {
            closeable.close();
        }

        rawLoop.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
    }

    /// Acceptor side, no stream at all: nothing but the connection-level bound can reap it.
    @Test
    @Timeout(60)
    void acceptor_closesAConnectionThatNeverOpensAStream() throws Exception {
        var port = startServer();
        var connection = rawClientConnection(port, false);

        assertThat(connection.closeFuture().await(CLOSE_WAIT_MS, TimeUnit.MILLISECONDS))
            .as("a connection that never sends a Hello must be CLOSED within the Hello bound, not left open forever")
            .isTrue();
    }

    /// Acceptor side, a stream that never carries the preamble/Hello: the stream-level timeout used to close the stream
    /// and leave the connection open.
    @Test
    @Timeout(60)
    void acceptor_closesTheConnectionNotJustTheStream_whenItsStreamNeverSendsHello() throws Exception {
        var port = startServer();
        var connection = rawClientConnection(port, true);

        assertThat(connection.closeFuture().await(CLOSE_WAIT_MS, TimeUnit.MILLISECONDS))
            .as("closing only the silent stream leaves the connection open: the connection must be closed too")
            .isTrue();
    }

    /// Control (no global idle timeout): a connection that completed its Hello is never touched by the bound, however long it idles.
    @Test
    @Timeout(60)
    void acceptor_leavesAHelloedConnectionAlone() throws Exception {
        var port = startServer();
        var client = QuicClusterClient.quicClusterClient(CLIENT_NODE,
                                                         new NodeAddress("127.0.0.1", 9001),
                                                         Map.of(),
                                                         codec,
                                                         codec,
                                                         QuicTransportMetrics.quicTransportMetrics(),
                                                         clientSsl(),
                                                         Option.empty(),
                                                         (_, _) -> {});

        cleanup.add(() -> client.close().await(AWAIT));
        var connection = client.connect(SERVER_NODE, new InetSocketAddress("127.0.0.1", port)).await(AWAIT).unwrap();

        Thread.sleep(HELLO_BOUND_MS * 4);

        assertThat(connection.isActive()).as("a Hello'd connection idles past the Hello bound untouched").isTrue();
    }

    /// Dialer side: the acceptor accepts the connection and the Hello stream but never answers. The dial fails on the Hello
    /// timeout; the connection it opened must be closed too, not left "active" forever.
    @Test
    @Timeout(60)
    void dialer_closesItsConnection_whenTheAcceptorNeverAnswersHello() throws Exception {
        var acceptedConnections = new CopyOnWriteArrayList<QuicChannel>();
        var port = startMuteAcceptor(acceptedConnections);
        var client = QuicClusterClient.quicClusterClient(CLIENT_NODE,
                                                         new NodeAddress("127.0.0.1", 9001),
                                                         Map.of(),
                                                         codec,
                                                         codec,
                                                         QuicTransportMetrics.quicTransportMetrics(),
                                                         clientSsl(),
                                                         Option.empty(),
                                                         (_, _) -> {});

        ((QuicClusterClientInstance) client).helloTimeoutForTest(HELLO_BOUND_MS);
        cleanup.add(() -> client.close().await(AWAIT));

        var dial = client.connect(SERVER_NODE, new InetSocketAddress("127.0.0.1", port)).await(AWAIT);

        assertThat(dial.isFailure()).as("the dial fails on the Hello timeout").isTrue();
        awaitCount(acceptedConnections, 1);
        assertThat(acceptedConnections.getFirst().closeFuture().await(CLOSE_WAIT_MS, TimeUnit.MILLISECONDS))
            .as("the dialer's connection to the mute acceptor must be CLOSED after the failed dial")
            .isTrue();
    }

    // --- fixtures ---

    private int startServer() {
        var serverSsl = QuicTlsProvider.serverContext(ClusterTestTls.clusterTls("test-server")).unwrap();
        var server = QuicClusterServer.quicClusterServer(SERVER_NODE,
                                                         new NodeAddress("127.0.0.1", 9000),
                                                         Map.of(),
                                                         codec,
                                                         codec,
                                                         QuicTransportMetrics.quicTransportMetrics(),
                                                         serverSsl,
                                                         Option.empty(),
                                                         (_, _, _) -> {},
                                                         (_, _) -> {});

        ((QuicClusterServerInstance) server).helloTimeoutForTest(HELLO_BOUND_MS);
        server.start(0).await(AWAIT).onFailure(cause -> org.junit.jupiter.api.Assertions.fail("server start: " + cause.message()));
        cleanup.add(() -> server.stop().await(AWAIT));

        return server.boundPort().unwrap();
    }

    private QuicSslContext clientSsl() {
        return QuicTlsProvider.clientContext(ClusterTestTls.clusterTls("test-client")).unwrap();
    }

    /// A bare QUIC client: connects (TLS complete) and then does nothing at all, or opens a stream and writes nothing.
    private QuicChannel rawClientConnection(int port, boolean openSilentStream) throws Exception {
        var datagram = datagramWith(new QuicClientCodecBuilder().sslContext(clientSsl())
                                                                .maxIdleTimeout(0, TimeUnit.MILLISECONDS)
                                                                .initialMaxData(1_000_000)
                                                                .initialMaxStreamDataBidirectionalLocal(1_000_000)
                                                                .initialMaxStreamDataBidirectionalRemote(1_000_000)
                                                                .initialMaxStreamsBidirectional(8)
                                                                .build());
        var connection = QuicChannel.newBootstrap(datagram)
                                    .handler(new ChannelInboundHandlerAdapter())
                                    .streamHandler(new ChannelInboundHandlerAdapter())
                                    .remoteAddress(new InetSocketAddress("127.0.0.1", port))
                                    .connect()
                                    .get(10, TimeUnit.SECONDS);

        cleanup.add(connection::close);
        if (openSilentStream) {
            connection.createStream(QuicStreamType.BIDIRECTIONAL, new ChannelInboundHandlerAdapter()).get(10, TimeUnit.SECONDS);
        }

        return connection;
    }

    /// A bare QUIC acceptor that completes the TLS handshake, accepts the dialer's stream and reads it, but never answers.
    private int startMuteAcceptor(List<QuicChannel> accepted) throws Exception {
        var serverCodec = new QuicServerCodecBuilder().sslContext(QuicTlsProvider.serverContext(ClusterTestTls.clusterTls("test-server")).unwrap())
                                                      .maxIdleTimeout(0, TimeUnit.MILLISECONDS)
                                                      .initialMaxData(1_000_000)
                                                      .initialMaxStreamDataBidirectionalLocal(1_000_000)
                                                      .initialMaxStreamDataBidirectionalRemote(1_000_000)
                                                      .initialMaxStreamsBidirectional(8)
                                                      .tokenHandler(InsecureQuicTokenHandler.INSTANCE)
                                                      .handler(new ChannelInitializer<QuicChannel>() {
                                                          @Override
                                                          protected void initChannel(QuicChannel ch) {
                                                              accepted.add(ch);
                                                          }
                                                      })
                                                      .streamHandler(new ChannelInitializer<QuicStreamChannel>() {
                                                          @Override
                                                          protected void initChannel(QuicStreamChannel ch) {
                                                              ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                                                                  @Override
                                                                  public void channelRead(ChannelHandlerContext ctx, Object msg) {
                                                                      io.netty.util.ReferenceCountUtil.release(msg);
                                                                  }
                                                              });
                                                          }
                                                      })
                                                      .build();
        var channel = new Bootstrap().group(rawLoop)
                                     .channel(NioDatagramChannel.class)
                                     .handler(serverCodec)
                                     .bind(new InetSocketAddress("127.0.0.1", 0))
                                     .sync()
                                     .channel();

        cleanup.add(channel::close);

        return ((InetSocketAddress) channel.localAddress()).getPort();
    }

    private Channel datagramWith(io.netty.channel.ChannelHandler quicCodec) throws Exception {
        var channel = new Bootstrap().group(rawLoop)
                                     .channel(NioDatagramChannel.class)
                                     .handler(quicCodec)
                                     .bind(new InetSocketAddress("127.0.0.1", 0))
                                     .sync()
                                     .channel();

        cleanup.add(channel::close);

        return channel;
    }

    private static void awaitCount(List<?> list, int count) throws InterruptedException {
        var deadline = System.currentTimeMillis() + 5_000;

        while (list.size() < count && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
    }

    private static List<SliceCodec.TypeCodec<?>> combinedCodecs() {
        var all = new ArrayList<SliceCodec.TypeCodec<?>>();

        all.addAll(ConsensusCodecs.CODECS);
        all.addAll(NetCodecs.CODECS);

        return all;
    }
}
