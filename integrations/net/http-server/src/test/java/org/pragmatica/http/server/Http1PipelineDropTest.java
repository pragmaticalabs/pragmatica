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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Test;
import org.pragmatica.http.HttpRequest;

import static org.assertj.core.api.Assertions.assertThat;

/// #2433: the drops behind an ended exchange, pinned below the socket where the wire table cannot see them: a dropped
/// message is released (no leak), and a decoder failure ends the exchange even when the decoder does not discard the rest of
/// the input itself.
class Http1PipelineDropTest {
    /// A request dropped behind a refused WebSocket upgrade is released, not passed on and not left holding its buffer.
    @Test
    void routerDrop_behindARefusedUpgrade_releasesTheDroppedRequest() {
        var channel = new EmbeddedChannel(new NettyHttpServer.WebSocketUpgradeRouter(Map.of(), 1024));
        var upgrade = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/nowhere");

        upgrade.headers().set(HttpHeaderNames.HOST, "localhost");
        upgrade.headers().set(HttpHeaderNames.UPGRADE, "websocket");
        upgrade.headers().set(HttpHeaderNames.CONNECTION, "Upgrade");
        upgrade.headers().set(HttpHeaderNames.SEC_WEBSOCKET_KEY, "dGhlIHNhbXBsZSBub25jZQ==");
        upgrade.headers().set(HttpHeaderNames.SEC_WEBSOCKET_VERSION, "13");

        var behind = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1,
                                                HttpMethod.GET,
                                                "/second",
                                                Unpooled.copiedBuffer("body", StandardCharsets.UTF_8));

        // one write: the refusal closes the channel, and what was decoded in the same read is delivered after it
        channel.writeInbound(upgrade, behind);

        assertThat(behind.refCnt()).as("the dropped request is released").isZero();
        assertThat((Object) channel.readInbound()).as("nothing is passed on behind the refusal").isNull();
        channel.finishAndReleaseAll();
    }

    /// A request that failed to decode ends the exchange: a valid request handed to the handler after it is not dispatched.
    @Test
    void undecodableRequest_endsTheExchange_evenWhenAValidOneFollows() {
        var seen = new ArrayList<String>();
        var channel = new EmbeddedChannel(new NettyHttpServer.HttpRequestHandler((request, writer) -> record(seen, request, writer),
                                                                                 Map.of()));
        var undecodable = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/bad-request");

        undecodable.setDecoderResult(DecoderResult.failure(new IllegalArgumentException("unreadable request line")));

        var valid = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/second");

        valid.headers().set(HttpHeaderNames.HOST, "localhost");
        channel.writeInbound(undecodable, valid);

        assertThat(seen).as("no request is dispatched after the undecodable one").isEmpty();
        channel.finishAndReleaseAll();
    }

    private static void record(List<String> seen, HttpRequest request, ResponseWriter writer) {
        seen.add(request.path());
        writer.ok("{}");
    }
}
