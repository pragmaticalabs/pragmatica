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

import java.util.ArrayList;
import java.util.List;

import org.pragmatica.net.tcp.QuicSslContextFactory;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.http3.DefaultHttp3DataFrame;
import io.netty.handler.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.handler.codec.http3.Http3;
import io.netty.handler.codec.http3.Http3DataFrame;
import io.netty.handler.codec.http3.Http3HeadersFrame;
import io.netty.handler.codec.http3.Http3RequestStreamInboundHandler;
import io.netty.handler.codec.quic.DefaultQuicStreamFrame;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.assertj.core.api.Assertions.assertThat;


/// #2746: the quiche defect behind the stall, pinned on the wire with a hand-driven client.
///
/// A POST sends 512 KiB, then its 6 KiB tail. The client's datagrams are held from the tail on: the tail's datagrams
/// (phase 1), the stream's `FIN` when it travels alone (phase 2), and four packets of a second stream (phase 3). The
/// release then drops what the case names and sends the rest, so the server acknowledges the phase-3 packets and the
/// client detects every loss in ONE batch.
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
        var holder = new Holder();
        var answer = exchange(Ending.FIN_ONLY_AFTER_DATA, Loss.DATA_AND_FIN_EXCEPT_LAST_DATA, holder);

        assertThat(holder.log()).as("the FIN-only frame was dropped").contains("2x");
        assertThat(answer.status()).as("TRIPWIRE #2746: quiche now retransmits a FIN-only frame lost together with data; "
                                      + "the workaround (FIN on the last data) can be reconsidered. " + holder.log())
                  .isEqualTo("closed");
    }

    @Test
    void finOnLastDataLostWithData_completes() throws Exception {
        assertCompletes(Ending.FIN_ON_LAST_DATA, Loss.DATA_AND_FIN_EXCEPT_LAST_DATA);
    }

    @Test
    void finBearingDatagramLostWithData_completes() throws Exception {
        assertCompletes(Ending.FIN_ON_LAST_DATA, Loss.DATA_AND_FIN_EXCEPT_BEFORE_LAST);
    }

    private void assertCompletes(Ending ending, Loss loss) throws Exception {
        var holder = new Holder();
        var answer = exchange(ending, loss, holder);

        assertThat(answer.status()).as(holder.log() + "; " + answer.diagnostics()).isEqualTo("200");
        assertThat(answer.body()).isEqualTo("handled " + (HEAD + TAIL));
    }

    /// How the request stream ends.
    private enum Ending {
        /// The last DATA frame, then `shutdownOutput()`: a FIN-only STREAM frame.
        FIN_ONLY_AFTER_DATA,
        /// The last DATA frame encoded by hand and written as one QUIC STREAM frame with `FIN`.
        FIN_ON_LAST_DATA
    }

    /// What the release drops.
    private enum Loss {
        NONE,
        FIN_ONLY,
        DATA_AND_FIN_EXCEPT_LAST_DATA,
        DATA_AND_FIN_EXCEPT_BEFORE_LAST
    }

    private Http3TestClient.Answer exchange(Ending ending, Loss loss, Holder holder) throws Exception {
        return Http3TestClient.exchange(port, stream -> writeRequest(stream, ending, loss, holder));
    }

    private void writeRequest(QuicStreamChannel stream, Ending ending, Loss loss, Holder holder) throws Exception {
        stream.parent().parent().pipeline().addFirst(holder);
        stream.write(headers("/x", HEAD + TAIL));
        for (int sent = 0; sent < HEAD; sent += 16384) {
            stream.writeAndFlush(new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(new byte[16384]))).sync();
        }

        Thread.sleep(100);
        holder.phase = 1;
        if (ending == Ending.FIN_ON_LAST_DATA) {
            stream.eventLoop().submit(() -> writeTailWithFin(stream)).sync();
            Thread.sleep(3);
            holder.phase = 2;
        } else {
            stream.writeAndFlush(new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(new byte[TAIL]))).sync();
            Thread.sleep(3);
            holder.phase = 2;
            stream.shutdownOutput().sync();
        }

        Thread.sleep(3);
        holder.phase = 3;
        var other = Http3.newRequestStream((QuicChannel) stream.parent(), new Ignore()).sync().getNow();

        other.write(headers("/y", 4 * 1100));
        for (int i = 0; i < 4; i++) {
            other.writeAndFlush(new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(new byte[1100]))).sync();
        }

        Thread.sleep(3);
        holder.release(loss);
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

    /// Holds every outbound datagram while `phase > 0`, tagged with the phase it was written in.
    private static final class Holder extends ChannelOutboundHandlerAdapter {
        private record Held(ChannelHandlerContext ctx, DatagramPacket packet, ChannelPromise promise, int phase) {}

        private final List<Held> held = new ArrayList<>();
        private final StringBuffer log = new StringBuffer();
        volatile int phase;

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            if (phase > 0 && msg instanceof DatagramPacket packet) {
                held.add(new Held(ctx, packet, promise, phase));
            } else {
                super.write(ctx, msg, promise);
            }
        }

        String log() {
            return "held: " + log.toString()
                                 .trim();
        }

        /// On the event loop: stops holding, drops the datagrams `loss` names and sends the rest, in order.
        void release(Loss loss) throws Exception {
            held.getFirst().ctx().executor().submit(() -> releaseHeld(loss)).sync();
        }

        private void releaseHeld(Loss loss) {
            phase = 0;
            var lastPhase1 = -1;

            for (int i = 0; i < held.size(); i++) {
                if (held.get(i).phase() == 1) {
                    lastPhase1 = i;
                }
            }

            var keep = loss == Loss.DATA_AND_FIN_EXCEPT_BEFORE_LAST
                       ? lastPhase1 - 1
                       : lastPhase1;

            for (int i = 0; i < held.size(); i++) {
                var h = held.get(i);
                var drop = isDropped(loss, h.phase(), i == keep);

                log.append(h.phase()).append(drop
                                             ? "x"
                                             : "+").append(h.packet().content().readableBytes()).append(' ');
                if (drop) {
                    ReferenceCountUtil.release(h.packet());
                    h.promise().setSuccess();
                } else {
                    h.ctx().write(h.packet(), h.promise());
                }
            }

            held.getFirst().ctx().flush();
            held.clear();
        }

        private static boolean isDropped(Loss loss, int phase, boolean kept) {
            return switch (loss) {
                case NONE -> false;
                case FIN_ONLY -> phase == 2;
                case DATA_AND_FIN_EXCEPT_LAST_DATA, DATA_AND_FIN_EXCEPT_BEFORE_LAST -> (phase == 1 && !kept) || phase == 2;
            };
        }
    }

    /// The second stream only has to carry packets; its answer is ignored.
    private static final class Ignore extends Http3RequestStreamInboundHandler {
        @Override
        protected void channelRead(ChannelHandlerContext ctx, Http3HeadersFrame frame) {}

        @Override
        protected void channelRead(ChannelHandlerContext ctx, Http3DataFrame frame) {
            ReferenceCountUtil.release(frame);
        }

        @Override
        protected void channelInputClosed(ChannelHandlerContext ctx) {}
    }
}
