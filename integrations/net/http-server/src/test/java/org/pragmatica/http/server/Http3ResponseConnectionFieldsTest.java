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

import java.util.List;
import java.util.Locale;

import io.netty.handler.codec.http3.Http3;
import io.netty.handler.codec.quic.QuicStreamChannel;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.net.tcp.QuicSslContextFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.http.server.Http3TestClient.postHeaders;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #2386: RFC 9114 §4.2 — an HTTP/3 message carries no connection-specific field, and a pseudo-header field only as the
/// protocol defines it. The response writer drops such fields when the application sets them, matching the name
/// case-insensitively, and keeps every other header. Before, it wrote them as given: a client refused the response for
/// `connection` or an unknown pseudo-header, received the others, and an application `:status` replaced the real one.
///
/// The handler sets the field named by the request's `x-set` header to `v`, beside an ordinary `X-Ordinary: kept`.
class Http3ResponseConnectionFieldsTest {
    /// Each in a case other than lowercase, as an application may write it.
    private static final List<String> CONNECTION_SPECIFIC = List.of("Connection",
                                                                    "KEEP-ALIVE",
                                                                    "Proxy-Connection",
                                                                    "Transfer-Encoding",
                                                                    "UPGRADE",
                                                                    "TE");

    private Http3Server server;
    private int port;

    @BeforeEach
    void start() {
        server = Http3Server.create(HttpServerConfig.httpServerConfig("h3-connection-fields", 0),
                                    QuicSslContextFactory.createSelfSignedServer(Http3.supportedApplicationProtocols())
                                                         .unwrap(),
                                    (request, writer) -> writer.header(request.headers().get("x-set").or("x-none"), "v")
                                                               .header("X-Ordinary", "kept")
                                                               .ok("{}"))
                            .await(timeSpan(10).seconds())
                            .unwrap();
        port = Http3TestClient.boundPort(server);
    }

    @AfterEach
    void stop() {
        server.stop().await(timeSpan(10).seconds());
    }

    /// The control: an ordinary field the handler names reaches the client, so an absent field below was dropped.
    @Test
    void ordinaryField_reachesTheClient() throws Exception {
        var answer = exchange("X-Custom");

        assertThat(answer.status()).isEqualTo("200");
        assertThat(answer.headers().get("x-custom")).hasToString("v");
        assertThat(answer.headers().get("x-ordinary")).hasToString("kept");
    }

    @Test
    void connectionSpecificFields_neverReachTheWire_andTheResponseArrives() throws Exception {
        assertDropped(CONNECTION_SPECIFIC);
    }

    /// Under `tr-TR` a default-locale lower case turns `KEEP-ALIVE` into `keep-alıve`, which no filter would match:
    /// names are folded with `Locale.ROOT`.
    @Test
    void connectionSpecificFields_neverReachTheWire_underATurkishDefaultLocale() throws Exception {
        var saved = Locale.getDefault();

        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertDropped(CONNECTION_SPECIFIC);
        } finally {
            Locale.setDefault(saved);
        }
    }

    /// A pseudo-header the protocol does not define makes a client refuse the whole response.
    @Test
    void unknownPseudoHeader_neverReachesTheWire_andTheResponseArrives() throws Exception {
        assertDropped(List.of(":x-custom"));
    }

    /// The status is the writer's to set: an application `:status` no longer replaces it.
    @Test
    void applicationStatusPseudoHeader_isDropped_andTheRealStatusStands() throws Exception {
        var answer = exchange(":status");

        assertThat(answer.status()).isEqualTo("200");
        assertThat(answer.headers().getAll(":status")).hasSize(1);
        assertThat(answer.headers().get("x-ordinary")).hasToString("kept");
    }

    private void assertDropped(List<String> names) throws Exception {
        var softly = new SoftAssertions();

        for (var name : names) {
            var answer = exchange(name);

            softly.assertThat(answer.status()).as("%s: the response arrives", name).isEqualTo("200");
            softly.assertThat(answer.headers().contains(name.toLowerCase(Locale.ROOT)))
                  .as("%s: never on the wire", name)
                  .isFalse();
            softly.assertThat(answer.headers().get("x-ordinary"))
                  .as("%s: an ordinary header still is", name)
                  .hasToString("kept");
        }
        softly.assertAll();
    }

    private Http3TestClient.Answer exchange(String name) throws Exception {
        return Http3TestClient.exchange(port, stream -> {
            var headers = postHeaders(port);

            headers.headers().add("x-set", name);
            stream.writeAndFlush(headers).addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
        });
    }
}
