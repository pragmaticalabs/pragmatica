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

import java.util.concurrent.TimeUnit;
import java.util.function.BiPredicate;

import org.pragmatica.net.tcp.QuicSslContextFactory;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.handler.codec.http3.DefaultHttp3DataFrame;
import io.netty.handler.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.handler.codec.http3.Http3;
import io.netty.handler.codec.quic.DefaultQuicStreamFrame;
import io.netty.handler.codec.quic.QuicStreamChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.assertj.core.api.Assertions.assertThat;


/// #2746: the quiche defect behind the stall, pinned on the wire with a hand-driven client.
///
/// A POST sends 512 KiB, then its 6 KiB tail. The client's datagrams are held from the tail on ([TailLossInjector]):
/// the burst is the tail's datagrams and the stream's `FIN` when it travels alone, written in one task; the datagrams
/// that follow are the client's PTO probes. The release then drops what the case names of the burst and sends the
/// rest, so the server acknowledges the probes and the client detects every loss in ONE batch.
///
/// - The tail's data lost, all but its last datagram, together with the FIN-only frame that followed it: quiche
///   (netty-codec-native-quic 4.2.18, unchanged in 4.2.20) retransmits the data but never the `FIN`. The server holds
///   every byte, never sees the end of the request, never dispatches it, and the connection idles out (5 s).
/// - The same losses with the `FIN` carried by the tail's last DATA frame: the exchange completes. This is what the
///   server's response writer and the http-client's request writer now do ([QuicStreamFinisher]).
/// - Controls: nothing dropped, and the FIN-only frame lost alone, both complete; so the stall comes from the combined
///   loss, not from the harness.
///
/// [#finOnlyFrameLostWithData_stalls] is a TRIPWIRE on the upstream defect: when it fails, quiche retransmits a lost
/// FIN-only frame and the #2746 workaround can be reconsidered.
class Http3FinLossTest {
    private static final int HEAD = 512 * 1024;
    private static final int TAIL = 6 * 1024;
    private static final int PROBES = 4;

    private final TailLossInjector injector = new TailLossInjector();
    private Http3Server server;
    private int port;

    @BeforeEach
    void start() {
        server = Http3Server.create(HttpServerConfig.httpServerConfig("h3-fin-loss-wire", 0).withClassifier(_ -> Disposition.aggregate(100L * 1024 * 1024)),
                                    QuicSslContextFactory.createSelfSignedServer(Http3.supportedApplicationProtocols()).unwrap(),
                                    (request, writer) -> writer.okText("handled " + request.body().length))
                            .await(timeSpan(10).seconds())
                            .unwrap();
        port = Http3TestClient.boundPort(server);
    }

    @AfterEach
    void stop() {
        server.stop().await(timeSpan(10).seconds());
    }

    @Test
    void nothingDropped_completes() throws Exception {
        assertCompletes(Ending.FIN_ONLY_AFTER_DATA, Loss.NONE);
    }

    @Test
    void finOnlyFrameLostAlone_completes() throws Exception {
        assertCompletes(Ending.FIN_ONLY_AFTER_DATA, Loss.FIN_ONLY);
    }

    @Test
    void finOnlyFrameLostWithData_stalls() throws Exception {
        var answer = exchange(Ending.FIN_ONLY_AFTER_DATA, Loss.DATA_AND_FIN_EXCEPT_LAST_DATA);

        assertPatternApplied();
        assertThat(injector.log()).as("the FIN-only frame was the burst's last datagram, and was dropped").contains("bx46");
        assertThat(answer.status()).as("TRIPWIRE #2746: quiche now retransmits a FIN-only frame lost together with data; "
                                      + "the workaround (FIN on the last data) can be reconsidered. " + injector.log())
                  .isEqualTo("closed");
    }

    @Test
    void finOnLastDataLostWithData_completes() throws Exception {
        assertCompletes(Ending.FIN_ON_LAST_DATA, Loss.DATA_EXCEPT_FIN_BEARING);
    }

    @Test
    void finBearingDatagramLostWithData_completes() throws Exception {
        assertCompletes(Ending.FIN_ON_LAST_DATA, Loss.DATA_AND_FIN_EXCEPT_BEFORE_LAST);
    }

    private void assertCompletes(Ending ending, Loss loss) throws Exception {
        var answer = exchange(ending, loss);

        assertPatternApplied();
        assertThat(answer.status()).as(injector.log() + "; " + answer.diagnostics()).isEqualTo("200");
        assertThat(answer.body()).isEqualTo("handled " + (HEAD + TAIL));
    }

    /// The burst held several datagrams and the endpoint's probes followed it: the loss the case names was applied.
    private void assertPatternApplied() {
        assertThat(injector.burst()).as("the pattern was applied: " + injector.log()).isGreaterThanOrEqualTo(2);
        assertThat(injector.later()).as("probes followed the burst: " + injector.log()).isGreaterThanOrEqualTo(1);
    }

    /// How the request stream ends.
    private enum Ending {
        /// The last DATA frame, then `shutdownOutput()`: a FIN-only STREAM frame.
        FIN_ONLY_AFTER_DATA,
        /// The last DATA frame encoded by hand and written as one QUIC STREAM frame with `FIN`.
        FIN_ON_LAST_DATA
    }

    /// What the release drops, of the burst: the tail's datagrams, the last of which carries or follows the `FIN`.
    private enum Loss {
        NONE(TailLossInjector.NONE),
        FIN_ONLY(TailLossInjector.LAST_ONLY),
        DATA_AND_FIN_EXCEPT_LAST_DATA(TailLossInjector.ALL_BUT_SECOND_LAST),
        DATA_EXCEPT_FIN_BEARING(TailLossInjector.ALL_BUT_LAST),
        DATA_AND_FIN_EXCEPT_BEFORE_LAST(TailLossInjector.ALL_BUT_SECOND_LAST);

        private final BiPredicate<Integer, Integer> drop;

        Loss(BiPredicate<Integer, Integer> drop) {
            this.drop = drop;
        }
    }

    private Http3TestClient.Answer exchange(Ending ending, Loss loss) throws Exception {
        return Http3TestClient.exchange(port, stream -> writeRequest(stream, ending, loss));
    }

    /// The tail and the `FIN` are written in ONE task on the client's event loop, between [TailLossInjector#arm] and
    /// [TailLossInjector#endBurst]: the burst is exactly their datagrams, by position, whatever the load (#2866: the
    /// pattern used to be tagged by sleeps between the writes, and under load it missed the FIN-only frame). The
    /// release comes once the client has sent four PTO probes while nothing reached the server (at most 10 s).
    private void writeRequest(QuicStreamChannel stream, Ending ending, Loss loss) throws Exception {
        var udp = stream.parent().parent();

        udp.pipeline().addFirst(injector);
        stream.write(headers("/x", HEAD + TAIL));
        for (int sent = 0; sent < HEAD; sent += 16384) {
            stream.writeAndFlush(new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(new byte[16384]))).sync();
        }

        awaitQuiet(udp);
        stream.eventLoop().submit(() -> writeTail(stream, ending)).sync();
        awaitProbes(udp);
        injector.release(udp, loss.drop);
    }

    /// Returns once the client has sent nothing for 200 ms, as seen from its event loop: the head is acknowledged and
    /// the congestion window is open, so the tail's datagrams are produced by the task that writes it, not later when
    /// an acknowledgement arrives.
    private void awaitQuiet(Channel udp) throws Exception {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        var seen = writtenOnLoop(udp);

        while (System.nanoTime() < deadline) {
            Thread.sleep(200);
            var now = writtenOnLoop(udp);

            if (now == seen) {
                return;
            }

            seen = now;
        }

        throw new AssertionError("the client kept sending for 10 s");
    }

    /// Returns once the injector holds four datagrams past the burst: the PTO probes, enough for the FIN-only frame to count as lost by packet threshold (a deadline of 10 s only bounds a hang).
    private void awaitProbes(Channel udp) throws Exception {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

        while (System.nanoTime() < deadline && udp.eventLoop().submit(injector::heldAfterBurst).syncUninterruptibly().getNow() < PROBES) {
            Thread.sleep(20);
        }
    }

    private long writtenOnLoop(Channel udp) {
        return udp.eventLoop().submit(injector::written).syncUninterruptibly().getNow();
    }

    private void writeTail(QuicStreamChannel stream, Ending ending) {
        injector.arm();
        if (ending == Ending.FIN_ON_LAST_DATA) {
            writeTailWithFin(stream);
        } else {
            stream.writeAndFlush(new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(new byte[TAIL])));
            stream.shutdownOutput();
        }

        injector.endBurst();
    }

    /// A DATA frame (type 0x00, two-byte varint length) written past the frame codec as a STREAM frame with `FIN`.
    private static void writeTailWithFin(QuicStreamChannel stream) {
        var frame = Unpooled.buffer();

        frame.writeByte(0x00);
        frame.writeByte(0x40 | (TAIL >> 8));
        frame.writeByte(TAIL & 0xff);
        frame.writeBytes(new byte[TAIL]);
        stream.pipeline().firstContext().writeAndFlush(new DefaultQuicStreamFrame(frame, true));
    }

    private DefaultHttp3HeadersFrame headers(String path, int length) {
        var frame = new DefaultHttp3HeadersFrame();

        frame.headers()
             .method("POST")
             .path(path)
             .authority("127.0.0.1:" + port)
             .scheme("https")
             .add("content-length",
                  String.valueOf(length));

        return frame;
    }
}
