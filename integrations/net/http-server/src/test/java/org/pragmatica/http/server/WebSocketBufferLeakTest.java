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
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.netty.buffer.ByteBufAllocator;
import io.netty.util.ResourceLeakDetector;
import io.netty.util.ResourceLeakDetectorFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.http.websocket.WebSocketHandler;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.http.websocket.WebSocketEndpoint.webSocketEndpoint;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #2184: every request the WebSocket upgrade router consumes instead of forwarding (an unknown path, an
/// unsupported version) is released. Netty's leak detector runs at PARANOID, tracking every buffer, and reports
/// through a counting factory installed before any buffer class initialises. That is why this class needs a JVM
/// of its own: the http-server surefire configuration does not reuse forks. Reports arrive when a leaked buffer
/// has been collected and a later allocation polls the queue, hence the GC-and-allocate loop at the end.
/// Instrument control: dropping the release in `refuse` makes this red.
@Timeout(120)
class WebSocketBufferLeakTest {
    private static final int ROUNDS = 20;
    private static final AtomicInteger LEAKS = new AtomicInteger();
    private static final AtomicInteger DETECTORS = new AtomicInteger();

    static {
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID);
        ResourceLeakDetectorFactory.setResourceLeakDetectorFactory(new CountingFactory());
    }

    @Test
    void everyConsumedUpgrade_isReleased_atParanoid() throws Exception {
        WebSocketHandler echo = (session, message) -> session.send("echo");
        var config = HttpServerConfig.httpServerConfig("ws-leak", 0)
                                     .withWebSocket(webSocketEndpoint("/ws/a", () -> echo));
        var server = HttpServer.httpServer(config, (_, writer) -> writer.okText("http"))
                               .await(timeSpan(10).seconds())
                               .unwrap();
        var client = HttpClient.newBuilder()
                               .connectTimeout(Duration.ofSeconds(5))
                               .build();

        try {
            for (int round = 0; round < ROUNDS; round++) {
                exchange(server.port(), "/ws/unknown", "13");
                exchange(server.port(), "/ws/a", "99");
                client.newWebSocketBuilder()
                      .buildAsync(URI.create("ws://127.0.0.1:" + server.port() + "/ws/a"), new WebSocket.Listener() {})
                      .get(5, TimeUnit.SECONDS)
                      .sendClose(WebSocket.NORMAL_CLOSURE, "")
                      .get(5, TimeUnit.SECONDS);
            }
        } finally {
            client.shutdownNow();
            server.stop()
                  .await(timeSpan(10).seconds());
        }

        collectAndPoll();

        assertThat(DETECTORS.get()).as("control: the counting factory built the detectors").isPositive();
        assertThat(ResourceLeakDetector.getLevel()).as("control: every buffer tracked")
                                                   .isEqualTo(ResourceLeakDetector.Level.PARANOID);
        assertThat(LEAKS.get()).as("buffer leaks reported").isZero();
    }

    /// Sends one upgrade and reads the response head. The connection is closed by the server (404) or by us (426).
    private static void exchange(int port, String path, String version) throws IOException {
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5_000);
            socket.getOutputStream()
                  .write(("GET " + path + " HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\n"
                          + "Connection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                          + "Sec-WebSocket-Version: " + version + "\r\n\r\n").getBytes(US_ASCII));
            assertThat(socket.getInputStream().read()).as("a response to %s", path).isPositive();
        }
    }

    private static void collectAndPoll() throws InterruptedException {
        for (int i = 0; i < 20; i++) {
            System.gc();
            Thread.sleep(100);
            for (int j = 0; j < 200; j++) {
                ByteBufAllocator.DEFAULT.directBuffer(64).release();
                ByteBufAllocator.DEFAULT.heapBuffer(64).release();
            }
        }
    }

    private static final class CountingFactory extends ResourceLeakDetectorFactory {
        @Override
        @SuppressWarnings("deprecation")
        public <T> ResourceLeakDetector<T> newResourceLeakDetector(Class<T> resource, int samplingInterval, long maxActive) {
            DETECTORS.incrementAndGet();

            return new CountingDetector<>(resource, samplingInterval, maxActive);
        }
    }

    private static final class CountingDetector<T> extends ResourceLeakDetector<T> {
        @SuppressWarnings("deprecation")
        CountingDetector(Class<T> resource, int samplingInterval, long maxActive) {
            super(resource, samplingInterval, maxActive);
        }

        @Override
        protected void reportTracedLeak(String type, String records) {
            LEAKS.incrementAndGet();
            System.out.println("LEAK traced " + type + " " + records);
        }

        @Override
        protected void reportUntracedLeak(String type) {
            LEAKS.incrementAndGet();
            System.out.println("LEAK untraced " + type);
        }
    }
}
