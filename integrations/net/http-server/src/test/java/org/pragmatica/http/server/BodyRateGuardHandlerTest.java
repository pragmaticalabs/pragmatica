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

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// F6 on HTTP/1.1, in virtual time: the minimum-rate guard between the codec and the aggregator closes the connection
/// of a body trickling at 1 B/s (which the idle timeout never catches), and lets a 5 MiB upload at 50 KiB/s through
/// whole, under the default limits — the same semantics `Http3RequestHandlerTest` pins for HTTP/3.
class BodyRateGuardHandlerTest {
    private static EmbeddedChannel channel() {
        var channel = new EmbeddedChannel(new BodyRateGuardHandler(BodyRateLimit.DEFAULT));

        channel.freezeTime();
        channel.writeInbound(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/up"));

        return channel;
    }

    private static DefaultHttpContent chunk(int size) {
        return new DefaultHttpContent(Unpooled.buffer(size).writeZero(size));
    }

    @Test
    void trickleOfOneBytePerSecond_closesTheConnection() {
        var channel = channel();

        for (int second = 0; second < 30 && channel.isActive(); second++) {
            channel.writeInbound(chunk(1));
            channel.advanceTimeBy(1, TimeUnit.SECONDS);
            channel.runScheduledPendingTasks();
        }

        assertThat(channel.isActive()).as("a 1 B/s body is far below 1 KiB/s").isFalse();
        channel.finishAndReleaseAll();
    }

    @Test
    void uploadOfFiveMebibytesAtFiftyKibibytesPerSecond_passesThroughWhole() {
        var channel = channel();
        var fiveMiB = 5 * 1024 * 1024;
        var frame = 5 * 1024;
        long passed = 0;

        for (int sent = 0; sent < fiveMiB; sent += frame) {
            channel.writeInbound(chunk(frame));
            channel.advanceTimeBy(100, TimeUnit.MILLISECONDS);
            channel.runScheduledPendingTasks();
        }
        channel.writeInbound(new DefaultLastHttpContent());
        channel.advanceTimeBy(60, TimeUnit.SECONDS);
        channel.runScheduledPendingTasks();

        assertThat(channel.isActive()).as("above the minimum rate, and no check after the last chunk").isTrue();
        Object message;
        while ((message = channel.readInbound()) != null) {
            if (message instanceof DefaultHttpContent content) {
                passed += content.content().readableBytes();
            }
            ReferenceCountUtil.release(message);
        }
        assertThat(passed).isEqualTo(fiveMiB);
        channel.finishAndReleaseAll();
    }
}
