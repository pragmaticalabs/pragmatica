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

import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http3.DefaultHttp3DataFrame;
import io.netty.handler.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.handler.codec.http3.Http3;
import io.netty.handler.codec.quic.QuicStreamChannel;
import org.pragmatica.http.HttpRequest;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.net.tcp.QuicSslContextFactory;

import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Streams a body of the given size through a real server, over HTTP/1.1 (`h1`) or HTTP/3 (`h3`), into a sink that only
/// hashes it, and prints what it saw. Run by [StreamingIngestRun] in a child JVM with a heap (and direct memory) far
/// smaller than the body, so a server that gathers the body, or keeps reading while the sink is busy, dies here with an
/// `OutOfMemoryError` instead of in the test's own JVM. The client is in the same JVM, so it streams too.
///
/// Exits 0 only when the sink completed with every byte; 3 when it did not complete in time, 4 when it was aborted or
/// failed. The bytes are non-repeating (see [RequestGateStreamWireTest#piece]).
public final class StreamingIngestProbe {
    private StreamingIngestProbe() {}

    /// Hashes every chunk; resolves each accept at once.
    private static final class HashingSink implements BodySink {
        private final MessageDigest digest = RecordingSink.sha256();
        private final AtomicLong bytes = new AtomicLong();
        private final CompletableFuture<String> ended = new CompletableFuture<>();

        @Override
        public Promise<Unit> accept(ByteBuffer chunk) {
            bytes.addAndGet(chunk.remaining());
            digest.update(chunk);

            return Promise.success(unit());
        }

        @Override
        public void complete(HttpRequest request, ResponseWriter response) {
            ended.complete("complete");
            response.okText("received " + bytes.get());
        }

        @Override
        public void fail(Cause cause, ResponseWriter response) {
            ended.complete("fail:" + cause.message());
            response.error(org.pragmatica.http.HttpStatus.SERVICE_UNAVAILABLE, "failed");
        }

        @Override
        public void abort(Cause cause) {
            ended.complete("abort:" + cause.message());
        }
    }

    public static void main(String[] args) throws Exception {
        var protocol = args[0];
        var size = Long.parseLong(args[1]);
        var sink = new HashingSink();
        var classifier = (RequestClassifier) _ -> new Disposition.Stream(size, Option.none(), sink);
        var started = System.nanoTime();
        var sent = "h3".equals(protocol)
                   ? overHttp3(classifier, size)
                   : overHttp1(classifier, size);
        var end = sink.ended.get(1, TimeUnit.MINUTES);

        System.out.println("MAX_HEAP_MB=" + Runtime.getRuntime().maxMemory() / (1024 * 1024));
        System.out.println("END=" + end);
        System.out.println("SENT_SHA=" + sent);
        System.out.println("SINK_SHA=" + RecordingSink.hex(sink.digest.digest()));
        System.out.println("SINK_BYTES=" + sink.bytes.get());
        System.out.println("MILLIS=" + (System.nanoTime() - started) / 1_000_000);
        System.out.println("DONE");
        System.exit("complete".equals(end)
                    ? 0
                    : 4);
    }

    private static String overHttp1(RequestClassifier classifier, long size) throws Exception {
        int port;

        try (var probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }

        var config = HttpServerConfig.httpServerConfig("probe-h1", port).withClassifier(classifier);
        var server = NettyHttpServer.create(config, (_, writer) -> writer.okText("aggregated")).await(timeSpan(10).seconds()).unwrap();

        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(120_000);
            var sent = RequestGateStreamWireTest.sendGenerated(socket,
                                                               "POST /up HTTP/1.1\r\nHost: x\r\nContent-Length: " + size + "\r\n\r\n",
                                                               size);

            System.out.println("STATUS=" + RequestGateStreamWireTest.readResponse(socket.getInputStream()).lines().findFirst().orElse(""));

            return sent;
        } finally {
            server.stop().await(timeSpan(10).seconds());
        }
    }

    private static String overHttp3(RequestClassifier classifier, long size) throws Exception {
        var config = HttpServerConfig.httpServerConfig("probe-h3", 0).withClassifier(classifier);
        var server = Http3Server.create(config,
                                        QuicSslContextFactory.createSelfSignedServer(Http3.supportedApplicationProtocols()).unwrap(),
                                        (_, writer) -> writer.okText("aggregated"))
                                .await(timeSpan(10).seconds())
                                .unwrap();
        var port = Http3TestClient.boundPort(server);
        var sent = new CompletableFuture<String>();

        try {
            var answer = Http3TestClient.exchange(port, stream -> sent.complete(send(stream, port, size)));

            System.out.println("STATUS=" + answer.status());

            return sent.getNow("<not sent>");
        } finally {
            server.stop().await(timeSpan(10).seconds());
        }
    }

    /// Sends the generated bytes in 64 KiB frames, waiting for each write, and ends the stream; returns their SHA-256.
    private static String send(QuicStreamChannel stream, int port, long size) throws Exception {
        var digest = RecordingSink.sha256();
        var headers = new DefaultHttp3HeadersFrame();

        headers.headers().method("POST").path("/up").authority("127.0.0.1:" + port).scheme("https");
        headers.headers().add("content-length", String.valueOf(size));
        stream.write(headers);
        for (long index = 0, done = 0; done < size; index++) {
            var piece = RequestGateStreamWireTest.piece(index, (int) Math.min(1024 * 1024, size - done));

            digest.update(piece);
            for (int at = 0; at < piece.length; at += 64 * 1024) {
                var frame = Unpooled.wrappedBuffer(piece, at, Math.min(64 * 1024, piece.length - at));
                var data = new DefaultHttp3DataFrame(frame);
                var written = done + at + frame.readableBytes() >= size
                              ? Http3TestClient.finishWith(stream, data)
                              : stream.writeAndFlush(data);

                if (!written.await(30, TimeUnit.SECONDS) || !written.isSuccess()) {
                    throw new IllegalStateException("frame write failed at " + (done + at), written.cause());
                }
            }

            done += piece.length;
        }

        return RecordingSink.hex(digest.digest());
    }
}
