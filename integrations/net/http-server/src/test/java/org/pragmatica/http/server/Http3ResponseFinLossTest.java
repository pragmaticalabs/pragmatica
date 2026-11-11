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

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.pragmatica.http.CommonContentType;
import org.pragmatica.http.HttpRequest;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.net.tcp.QuicSslContextFactory;

import io.netty.channel.Channel;
import io.netty.handler.codec.http3.Http3;
import io.netty.handler.codec.quic.QuicStreamChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.pragmatica.http.server.Http3TestClient.postHeaders;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.assertj.core.api.Assertions.assertThat;


/// #2746: a response survives the loss of its tail. The server's response writer used to end the stream with
/// `shutdownOutput()` after the last write, a FIN-only STREAM frame that quiche never retransmits when its loss is
/// detected together with the loss of earlier data on the stream ([TailLossInjector]); the client then held the whole
/// body, never saw the end of the stream, and the exchange stalled until the server's 5 s idle timeout. The writer now
/// sends `FIN` with the response's last bytes ([QuicStreamFinisher]).
///
/// The handler answers 100 ms after dispatch, in a task of its own on the server's event loop, outside the QUIC
/// channel's read handling, as an application handler's answer runs: each stream write is then packetised as soon as it
/// is made. The same task arms the injector, writes the answer and ends the injector's burst, so the burst is exactly
/// the answer's datagrams under any load (#2862). The injector releases them once the endpoint has sent four PTO probes (#2866), at the earliest 600 ms later.
class Http3ResponseFinLossTest {
    /// Any count of at least 1 gives the loss pattern: quiche needs one acknowledged packet numbered above the burst's
    /// to detect the burst's loss (#2873: PROBES=1 stayed green 12/12). Four is margin, so that the acknowledgements
    /// cover the burst under load; the count is not pinned by a test, [TailLossInjectorTest] pins that the wait honours it.
    private static final int PROBES = 4;
    private static final int BODY_BYTES = 6 * 1024;
    private static final String PAD = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".repeat(80);

    private final TailLossInjector injector = new TailLossInjector();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private Http3Server server;
    private Channel udp;
    private int port;

    @AfterEach
    void stop() {
        scheduler.shutdownNow();
        server.stop().await(timeSpan(10).seconds());
    }

    /// The control: holding the datagrams and sending all of them late does not stall the exchange, so a stall below
    /// comes from the drops.
    @Test
    void heldButNotDropped_bodyArrives() throws Exception {
        start(false, false);
        var answer = exchange();

        assertEndedCleanly(answer);
        assertThat(answer.body()).hasSize(BODY_BYTES);
        assertThat(injector.burst()).as(injector.log()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void tailLost_bodyStillEnds() throws Exception {
        start(true, false);
        var answer = exchange();

        assertThat(injector.burst()).as("the pattern was applied: " + injector.log()).isGreaterThanOrEqualTo(2);
        assertThat(injector.later()).as("probes followed the burst: " + injector.log()).isGreaterThanOrEqualTo(1);
        assertEndedCleanly(answer);
        assertThat(answer.body()).hasSize(BODY_BYTES);
    }

    /// An empty body still ends the stream: the `FIN` rides on the HEADERS frame's bytes. A FIN-only frame written after
    /// them, empty buffer or `shutdownOutput()` alike, is the frame quiche loses. The header section is padded over
    /// several datagrams so that its tail can be lost the same way. (Until #2746 the writer ended an empty body with an
    /// empty buffer the HTTP/3 frame codec refuses, so the stream was reset with `H3_INTERNAL_ERROR` instead of ended.)
    @Test
    void emptyBodyWithHeadersTailLost_responseStillEnds() throws Exception {
        start(true, true);
        var answer = exchange();

        assertThat(injector.burst()).as("the pattern was applied: " + injector.log()).isGreaterThanOrEqualTo(2);
        assertThat(injector.later()).as("probes followed the burst: " + injector.log()).isGreaterThanOrEqualTo(1);
        assertEndedCleanly(answer);
        assertThat(answer.body()).isEmpty();
        assertThat(answer.headers().get("x-pad")).hasToString(PAD);
    }

    /// The status and the body alone do not tell: a stalled stream still delivers both, then ends only when the
    /// connection idles out. The stream must end with its `FIN` (`input-closed`), not with the connection or a reset.
    private void assertEndedCleanly(Http3TestClient.Answer answer) {
        assertThat(answer.status()).as(injector.log() + "; " + answer.diagnostics()).isEqualTo("200");
        assertThat(answer.diagnostics()).as(injector.log()).contains("finish:input-closed");
    }

    private void start(boolean drop, boolean emptyBody) {
        server = Http3Server.create(HttpServerConfig.httpServerConfig("h3-fin-loss", 0),
                                    QuicSslContextFactory.createSelfSignedServer(Http3.supportedApplicationProtocols()).unwrap(),
                                    (request, writer) -> answerLater(request, writer, drop, emptyBody))
                            .await(timeSpan(10).seconds())
                            .unwrap();
        udp = server.serverChannel().unwrap();
        udp.pipeline().addFirst(injector);
        port = Http3TestClient.boundPort(server);
    }

    private Http3TestClient.Answer exchange() throws Exception {
        return Http3TestClient.exchange(port,
                                        stream -> stream.writeAndFlush(postHeaders(port))
                                                        .addListener(QuicStreamChannel.SHUTDOWN_OUTPUT));
    }

    private void answerLater(HttpRequest request, ResponseWriter writer, boolean drop, boolean emptyBody) {
        udp.eventLoop().schedule(() -> answer(writer, emptyBody), 100, TimeUnit.MILLISECONDS);
        scheduler.schedule(() -> release(drop), 700, TimeUnit.MILLISECONDS);
    }

    private void answer(ResponseWriter writer, boolean emptyBody) {
        injector.arm();
        if (emptyBody) {
            writer.header("x-pad", PAD).write(HttpStatus.OK, new byte[0], CommonContentType.TEXT_PLAIN);
        } else {
            writer.write(HttpStatus.OK, new byte[BODY_BYTES], CommonContentType.TEXT_PLAIN);
        }

        injector.endBurst();
    }

    private void release(boolean drop) {
        injector.awaitProbes(udp, PROBES);
        injector.release(udp, drop);
    }
}
