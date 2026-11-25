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
package org.pragmatica.http.server;

import java.io.IOException;
import java.net.ConnectException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.PortUnreachableException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.Set;
import java.util.function.BiConsumer;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.handler.codec.http3.DefaultHttp3DataFrame;
import io.netty.handler.codec.http3.Http3;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicStreamChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.http.HttpRequest;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.net.tcp.QuicSslContextFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #2180: the optional bind host of [HttpServerConfig] narrows the listening socket of both servers to one local
/// address; none keeps the wildcard (every interface), as before.
///
/// The refusal is observed on the wire, against a second local address of the machine: a TCP connect to it is
/// refused (HTTP/1.1), and a datagram sent to it from a connected socket is answered with ICMP port-unreachable,
/// which `java.net` reports as [PortUnreachableException] on the next receive (HTTP/3). Both are paired with a control
/// on the wildcard-bound default, so a test that cannot tell "refused" from "unreachable for another reason" fails.
/// Ports come from the OS (port 0), so the tests do not collide with other modules running concurrently (#939).
class HttpServerBindHostTest {
    private static final BiConsumer<HttpRequest, ResponseWriter> ANSWER_OK = (_, writer) -> writer.ok("{}");
    private static final String UNRESOLVABLE = "no-such-host.invalid";
    private static final int CONNECT_TIMEOUT_MS = 5_000;

    private HttpServer h1;
    private Http3Server h3;

    @AfterEach
    void stopServers() {
        if (h1 != null) {
            h1.stop().await(timeSpan(10).seconds());
        }
        if (h3 != null) {
            h3.stop().await(timeSpan(10).seconds());
        }
    }

    /// A local address of this machine that is not loopback: where a loopback-bound listener must NOT answer.
    private static InetAddress nonLoopbackAddress() throws IOException {
        return Collections.list(NetworkInterface.getNetworkInterfaces())
                          .stream()
                          .filter(HttpServerBindHostTest::isUp)
                          .flatMap(nic -> Collections.list(nic.getInetAddresses())
                                                     .stream())
                          .filter(Inet4Address.class::isInstance)
                          .filter(address -> !address.isLoopbackAddress() && !address.isLinkLocalAddress())
                          .findFirst()
                          .orElseThrow(() -> new IllegalStateException("no non-loopback IPv4 address"));
    }

    private static boolean isUp(NetworkInterface nic) {
        try {
            return nic.isUp();
        } catch (IOException _) {
            return false;
        }
    }

    private static boolean hasNonLoopbackAddress() {
        try {
            return nonLoopbackAddress() != null;
        } catch (IOException | IllegalStateException _) {
            return false;
        }
    }

    private static Channel channelOf(Option<Channel> serverChannel) {
        return serverChannel.unwrap();
    }

    private static InetAddress boundAddress(Channel channel) {
        return ((InetSocketAddress) channel.localAddress()).getAddress();
    }

    private static boolean connects(InetAddress address, int port) throws IOException {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(address, port), CONNECT_TIMEOUT_MS);

            return true;
        } catch (ConnectException _) {
            return false;
        }
    }

    @Nested
    class OverHttp1 {
        private HttpServer start(HttpServerConfig config) {
            h1 = HttpServer.httpServer(config, ANSWER_OK)
                           .await(timeSpan(10).seconds())
                           .unwrap();

            return h1;
        }

        /// RED at base (the option did not exist; a bind host would have been ignored): a listener bound to
        /// 127.0.0.1 answers on loopback and refuses the machine's other address.
        @Test
        void loopbackBound_acceptsLoopback_refusesTheNonLoopbackAddress() throws IOException {
            assumeThat(hasNonLoopbackAddress()).as("this machine has a non-loopback IPv4 address").isTrue();

            var server = start(HttpServerConfig.httpServerConfig("bind-h1-loopback", 0).withBindHost("127.0.0.1"));

            assertThat(connects(InetAddress.getLoopbackAddress(), server.port())).as("loopback connects").isTrue();
            assertThat(connects(nonLoopbackAddress(), server.port())).as("the non-loopback address is refused")
                                                                     .isFalse();
        }

        /// The control for the test above, and the "default unchanged" guarantee: without a bind host the same
        /// address that was refused is served, and the listener is on the wildcard address.
        @Test
        void noBindHost_listensOnEveryInterface() throws IOException {
            assumeThat(hasNonLoopbackAddress()).as("this machine has a non-loopback IPv4 address").isTrue();

            var server = start(HttpServerConfig.httpServerConfig("bind-h1-default", 0));

            assertThat(boundAddress(channelOf(((NettyHttpServer) server).serverChannel())).isAnyLocalAddress())
                .as("the default is the wildcard address")
                .isTrue();
            assertThat(connects(nonLoopbackAddress(), server.port())).as("the non-loopback address is served")
                                                                     .isTrue();
        }

        /// A host NAME resolves, and the socket binds what it resolved to.
        @Test
        void hostName_isResolved_andBound() {
            var server = start(HttpServerConfig.httpServerConfig("bind-h1-name", 0).withBindHost("localhost"));

            assertThat(boundAddress(channelOf(((NettyHttpServer) server).serverChannel())).isLoopbackAddress())
                .as("localhost resolved to a loopback address, which is what was bound")
                .isTrue();
        }

        /// RED at base: an unresolvable host fails the start with the typed cause naming it, and the groups the
        /// failed create owned are terminated when it reports (the #1612 discipline).
        @Test
        void unresolvableHost_failsTheStart_typed_andReleasesTheGroups() {
            var boss = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
            var worker = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
            var outcome = NettyHttpServer.createOwning(HttpServerConfig.httpServerConfig("bind-h1-unresolvable", 0)
                                                                       .withBindHost(UNRESOLVABLE),
                                                       ANSWER_OK,
                                                       boss,
                                                       worker)
                                         .await(timeSpan(10).seconds());

            assertThat(outcome.isFailure()).as("the start fails").isTrue();
            outcome.onFailure(cause -> assertBindHostUnresolved(cause, UNRESOLVABLE));
            assertThat(Set.<EventLoopGroup> of(boss, worker)).allMatch(EventLoopGroup::isTerminated);
        }

        /// A blank host would resolve to loopback; it is refused instead, so a stray empty setting never reads as
        /// "loopback only" nor widens to everything.
        @Test
        void blankHost_failsTheStart_typed() {
            var outcome = HttpServer.httpServer(HttpServerConfig.httpServerConfig("bind-h1-blank", 0).withBindHost("  "),
                                                ANSWER_OK)
                                    .await(timeSpan(10).seconds());

            assertThat(outcome.isFailure()).as("the start fails").isTrue();
            outcome.onFailure(cause -> assertBindHostUnresolved(cause, "  "));
        }
    }

    @Nested
    class OverHttp3 {
        private Http3Server start(HttpServerConfig config) {
            h3 = Http3Server.create(config,
                                    QuicSslContextFactory.createSelfSignedServer(Http3.supportedApplicationProtocols())
                                                         .unwrap(),
                                    ANSWER_OK)
                            .await(timeSpan(10).seconds())
                            .unwrap();

            return h3;
        }

        /// RED at base: a datagram to the non-loopback address of a loopback-bound listener is answered with ICMP
        /// port-unreachable, while the listener serves a real request on loopback.
        @Test
        void loopbackBound_serves127_refusesTheNonLoopbackAddress() throws Exception {
            assumeThat(hasNonLoopbackAddress()).as("this machine has a non-loopback IPv4 address").isTrue();

            var server = start(HttpServerConfig.httpServerConfig("bind-h3-loopback", 0).withBindHost("127.0.0.1"));

            assertThat(boundAddress(channelOf(server.serverChannel())).isLoopbackAddress()).as("bound to loopback")
                                                                                          .isTrue();
            assertThat(Http3TestClient.exchange(server.port(), stream -> smallBody(stream, server.port())).status())
                .as("the loopback listener serves a request")
                .isEqualTo("200");
            assertThat(datagramRefused(nonLoopbackAddress(), server.port())).as("the non-loopback address is refused")
                                                                          .isTrue();
        }

        /// The control for the test above and the "default unchanged" guarantee: a wildcard-bound listener receives
        /// the datagram on the same address, so no ICMP refusal comes back.
        @Test
        void noBindHost_listensOnEveryInterface() throws Exception {
            assumeThat(hasNonLoopbackAddress()).as("this machine has a non-loopback IPv4 address").isTrue();

            var server = start(HttpServerConfig.httpServerConfig("bind-h3-default", 0));

            assertThat(boundAddress(channelOf(server.serverChannel())).isAnyLocalAddress()).as("wildcard address")
                                                                                          .isTrue();
            assertThat(datagramRefused(nonLoopbackAddress(), server.port())).as("the non-loopback address is not refused")
                                                                          .isFalse();
        }

        /// RED at base: the unresolvable host fails the start typed and the owned group is terminated by then.
        @Test
        void unresolvableHost_failsTheStart_typed_andReleasesTheGroup() {
            var group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
            var outcome = Http3Server.bind(HttpServerConfig.httpServerConfig("bind-h3-unresolvable", 0)
                                                           .withBindHost(UNRESOLVABLE),
                                           quicSsl(),
                                           ANSWER_OK,
                                           group,
                                           true)
                                     .mapToUnit()
                                     .await(timeSpan(10).seconds());

            assertThat(outcome.isFailure()).as("the start fails").isTrue();
            outcome.onFailure(cause -> assertBindHostUnresolved(cause, UNRESOLVABLE));
            assertThat(group.isTerminated()).as("the owned group is terminated when the failure is reported").isTrue();
        }

        private static QuicSslContext quicSsl() {
            return QuicSslContextFactory.createSelfSignedServer(Http3.supportedApplicationProtocols())
                                        .unwrap();
        }

        private static void smallBody(QuicStreamChannel stream, int port) {
            stream.write(Http3TestClient.postHeaders(port));
            Http3TestClient.finishWith(stream, new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(new byte[16])));
        }

        /// Whether a datagram sent from a connected socket to `address:port` is refused with ICMP port-unreachable;
        /// a silent timeout means something received it (or nothing answered either way).
        private static boolean datagramRefused(InetAddress address, int port) throws IOException {
            try (var socket = new DatagramSocket()) {
                socket.connect(address, port);
                socket.setSoTimeout(2_000);
                socket.send(new DatagramPacket(new byte[]{1}, 1));
                socket.receive(new DatagramPacket(new byte[16], 16));

                return false;
            } catch (PortUnreachableException _) {
                return true;
            } catch (SocketTimeoutException _) {
                return false;
            }
        }
    }

    private static void assertBindHostUnresolved(Cause cause, String host) {
        assertThat(cause).isInstanceOfSatisfying(HttpServerError.BindHostUnresolved.class,
                                                 failure -> assertThat(failure.host()).isEqualTo(host));
        assertThat(cause.message()).contains("'" + host + "'");
    }
}
