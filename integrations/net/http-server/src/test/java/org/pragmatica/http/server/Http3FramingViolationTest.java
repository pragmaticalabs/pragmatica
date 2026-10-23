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

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http3.DefaultHttp3DataFrame;
import io.netty.handler.codec.http3.DefaultHttp3Headers;
import io.netty.handler.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.handler.codec.http3.Http3;
import io.netty.handler.codec.quic.DefaultQuicStreamFrame;
import io.netty.handler.codec.quic.QuicStreamChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.http.HttpRequest;
import org.pragmatica.net.tcp.QuicSslContextFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.http.server.Http3TestClient.postHeaders;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #2386: a request stream that breaks RFC 9114 framing is rejected whole; the handler never sees its valid prefix.
///
/// Netty drops the offending frame and raises the error, but the stream's `FIN` can still reach the request handler
/// afterwards: when the bad bytes and the `FIN` arrive in one read, the input-shutdown event follows the error on the
/// same stream, and the handler used to dispatch what it had buffered. Each broken stream here ends with its last
/// bytes and the `FIN` in one QUIC STREAM frame, which is what makes that ordering repeatable. Netty also checks
/// `content-length` against the body only when the input ends, and never checks that the last frame was complete.
class Http3FramingViolationTest {
    private final Recorder recorder = new Recorder();
    private Http3Server server;
    private int port;

    @BeforeEach
    void start() {
        server = Http3Server.create(HttpServerConfig.httpServerConfig("h3-framing", 0),
                                    QuicSslContextFactory.createSelfSignedServer(Http3.supportedApplicationProtocols())
                                                         .unwrap(),
                                    recorder)
                            .await(timeSpan(10).seconds())
                            .unwrap();
        port = Http3TestClient.boundPort(server);
    }

    @AfterEach
    void stop() {
        server.stop().await(timeSpan(10).seconds());
    }

    /// The control: the same harness dispatches a well-framed request with a body and a trailer section, so an empty
    /// recorder below means the stream was refused, not that nothing could have been dispatched.
    @Test
    void wellFramedRequest_isDispatched() throws Exception {
        var answer = Http3TestClient.exchange(port, stream -> {
            stream.write(postHeaders(port));
            stream.write(body("hello"));
            stream.writeAndFlush(new DefaultHttp3HeadersFrame(field("x-checksum", "abc")))
                  .addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
        });

        assertThat(answer.status()).isEqualTo("200");
        assertThat(recorder.requests()).hasSize(1);
    }

    /// RFC 9114 §4.1: nothing may follow the trailer section; a second HEADERS frame is H3_FRAME_UNEXPECTED.
    @Test
    void secondTrailerSection_isNeverDispatched() throws Exception {
        assertNeverDispatched(stream -> {
            stream.write(postHeaders(port));
            stream.write(body("hello"));
            stream.write(new DefaultHttp3HeadersFrame(field("x-one", "1")));
            finishRaw(stream, 0x01, 0x02, 0x00, 0x00);
        });
    }

    /// RFC 9114 §4.1: a DATA frame after the trailer section is H3_FRAME_UNEXPECTED.
    @Test
    void dataAfterTrailerSection_isNeverDispatched() throws Exception {
        assertNeverDispatched(stream -> {
            stream.write(postHeaders(port));
            stream.write(body("hello"));
            stream.write(new DefaultHttp3HeadersFrame(field("x-one", "1")));
            finishRaw(stream, 0x00, 0x03, 'b', 'a', 'd');
        });
    }

    /// RFC 9114 §4.1.2: a body shorter than its `content-length` is malformed. Netty finds it only when the input
    /// ends, after the input-shutdown event the handler used to dispatch on.
    @Test
    void bodyShorterThanContentLength_isNeverDispatched() throws Exception {
        assertNeverDispatched(stream -> {
            var headers = postHeaders(port);

            headers.headers().setInt("content-length", 10);
            stream.write(headers);
            stream.writeAndFlush(body("hello")).addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
        });
    }

    /// RFC 9114 §4.1.2: a body longer than its `content-length` is malformed.
    @Test
    void bodyLongerThanContentLength_isNeverDispatched() throws Exception {
        assertNeverDispatched(stream -> {
            var headers = postHeaders(port);

            headers.headers().setInt("content-length", 3);
            stream.write(headers);
            stream.write(body("hello"));
            finishRaw(stream);
        });
    }

    /// RFC 9114 §4.3: a trailer section may not carry pseudo-header fields.
    @Test
    void pseudoHeaderInTrailerSection_isNeverDispatched() throws Exception {
        assertNeverDispatched(stream -> {
            stream.write(postHeaders(port));
            stream.write(body("hello"));

            var trailers = new DefaultHttp3Headers();

            trailers.path("/elsewhere");
            stream.write(new DefaultHttp3HeadersFrame(trailers));
            finishRaw(stream);
        });
    }

    /// RFC 9114 §7.1: a stream that ends inside a frame (here a DATA frame declaring 10 bytes and carrying 5) is
    /// H3_FRAME_ERROR. Netty passes the 5 bytes on and drops the rest of the frame without a word.
    @Test
    void streamEndingInsideADataFrame_isNeverDispatched() throws Exception {
        assertNeverDispatched(stream -> {
            stream.write(postHeaders(port));
            finishRaw(stream, 0x00, 0x0A, 'h', 'e', 'l', 'l', 'o');
        });
    }

    /// RFC 9114 §7.1: the same for a trailer section cut short (a HEADERS frame declaring 5 bytes and carrying 1).
    @Test
    void streamEndingInsideATrailerSection_isNeverDispatched() throws Exception {
        assertNeverDispatched(stream -> {
            stream.write(postHeaders(port));
            stream.write(body("hello"));
            finishRaw(stream, 0x01, 0x05, 0x00);
        });
    }

    /// RFC 9114 §7.1: the same for a stream that ends inside a frame header (a DATA type with no length).
    @Test
    void streamEndingInsideAFrameHeader_isNeverDispatched() throws Exception {
        assertNeverDispatched(stream -> {
            stream.write(postHeaders(port));
            stream.write(body("hello"));
            finishRaw(stream, 0x00);
        });
    }

    /// The client's answer is read only after the server has handled the `FIN` (it answers or closes in response to
    /// it), so a dispatch would already be recorded.
    private void assertNeverDispatched(Http3TestClient.RequestWriter writer) throws Exception {
        var answer = Http3TestClient.exchange(port, writer);

        assertThat(recorder.requests()).as("a request stream that breaks HTTP/3 framing must never be dispatched")
                                       .isEmpty();
        assertThat(answer.status()).isNotEqualTo("200");
    }

    /// Writes `bytes` below the client's frame codec, so they reach the wire as given, in one STREAM frame with the
    /// `FIN`; the frames written before it are flushed together with it.
    private static void finishRaw(QuicStreamChannel stream, int... bytes) {
        var raw = new byte[bytes.length];

        for (int i = 0; i < bytes.length; i++) {
            raw[i] = (byte) bytes[i];
        }
        stream.pipeline()
              .firstContext()
              .writeAndFlush(new DefaultQuicStreamFrame(Unpooled.wrappedBuffer(raw), true));
    }

    private static DefaultHttp3DataFrame body(String text) {
        return new DefaultHttp3DataFrame(Unpooled.copiedBuffer(text, StandardCharsets.UTF_8));
    }

    private static DefaultHttp3Headers field(String name, String value) {
        var headers = new DefaultHttp3Headers();

        headers.add(name, value);

        return headers;
    }

    /// Keeps every request the server dispatched and answers 200.
    private static final class Recorder implements BiConsumer<HttpRequest, ResponseWriter> {
        private final List<HttpRequest> requests = new CopyOnWriteArrayList<>();

        @Override
        public void accept(HttpRequest request, ResponseWriter writer) {
            requests.add(request);
            writer.ok("{}");
        }

        List<HttpRequest> requests() {
            return List.copyOf(requests);
        }
    }
}
