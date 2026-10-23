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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.handler.codec.http3.Http3ErrorCode;
import io.netty.handler.codec.http3.Http3Exception;
import io.netty.handler.codec.quic.DefaultQuicStreamFrame;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// #2386: [Http3FrameBoundaryGuard] follows RFC 9000 §16 varints (1, 2, 4 and 8 bytes) and frame payloads across reads,
/// and fails a stream whose input ends inside a frame. The real-client cases are in [Http3FramingViolationTest]; these
/// pin the varint lengths and the splits a short request never produces.
class Http3FrameBoundaryGuardTest {
    @Test
    void wholeFrames_endAtABoundary_andPassThroughUnchanged() {
        var channel = new EmbeddedChannel(new Http3FrameBoundaryGuard());

        channel.writeInbound(bytes(0x01, 0x02, 0x00, 0x00, 0x00, 0x03, 'a', 'b', 'c'));

        assertThat(guard(channel).atFrameBoundary()).isTrue();

        ByteBuf passed = channel.readInbound();

        assertThat(passed.readableBytes()).as("the guard never consumes").isEqualTo(9);
        passed.release();
    }

    @Test
    void emptyFrame_isABoundary() {
        assertThat(boundaryAfter(0x00, 0x00)).isTrue();
    }

    /// `0x40 0x05`: a two-byte length of 5.
    @Test
    void twoByteLength_isFollowed() {
        assertThat(boundaryAfter(0x00, 0x40, 0x05, 1, 2, 3, 4)).isFalse();
        assertThat(boundaryAfter(0x00, 0x40, 0x05, 1, 2, 3, 4, 5)).isTrue();
    }

    /// `0x41 0x00`: a two-byte length of 256, so the high byte counts.
    @Test
    void twoByteLength_withAHighByte_isFollowed() {
        var header = new int[] {0x00, 0x41, 0x00};
        var almost = new int[header.length + 255];
        var whole = new int[header.length + 256];

        System.arraycopy(header, 0, almost, 0, header.length);
        System.arraycopy(header, 0, whole, 0, header.length);

        assertThat(boundaryAfter(almost)).isFalse();
        assertThat(boundaryAfter(whole)).isTrue();
    }

    /// `0x80 0x00 0x00 0x02`: a four-byte length of 2.
    @Test
    void fourByteLength_isFollowed() {
        assertThat(boundaryAfter(0x00, 0x80, 0x00, 0x00, 0x02, 1)).isFalse();
        assertThat(boundaryAfter(0x00, 0x80, 0x00, 0x00, 0x02, 1, 2)).isTrue();
    }

    /// `0xC0 ... 0x01`: an eight-byte length of 1, after an eight-byte reserved frame type.
    @Test
    void eightByteTypeAndLength_areFollowed() {
        assertThat(boundaryAfter(0xC0, 0, 0, 0, 0, 0, 0, 0x21, 0xC0, 0, 0, 0, 0, 0, 0, 0x01)).isFalse();
        assertThat(boundaryAfter(0xC0, 0, 0, 0, 0, 0, 0, 0x21, 0xC0, 0, 0, 0, 0, 0, 0, 0x01, 9)).isTrue();
    }

    /// Netty's frame codec also reads QUIC stream frames; the guard follows their content the same way.
    @Test
    void quicStreamFrames_areFollowed() {
        var channel = new EmbeddedChannel(new Http3FrameBoundaryGuard());

        channel.writeInbound(new DefaultQuicStreamFrame(bytes(0x00, 0x02, 'a'), false));

        assertThat(guard(channel).atFrameBoundary()).isFalse();

        channel.writeInbound(new DefaultQuicStreamFrame(bytes('b'), true));

        assertThat(guard(channel).atFrameBoundary()).isTrue();
        channel.finishAndReleaseAll();
    }

    @Test
    void endingInsideAVarint_isNotABoundary() {
        assertThat(boundaryAfter(0x00, 0x40)).isFalse();
        assertThat(boundaryAfter(0x80, 0x00)).isFalse();
    }

    @Test
    void endingBetweenTypeAndLength_isNotABoundary() {
        assertThat(boundaryAfter(0x00)).isFalse();
    }

    /// A frame header and payload split over several reads, one byte at a time.
    @Test
    void framesSplitAcrossReads_areFollowed() {
        var channel = new EmbeddedChannel(new Http3FrameBoundaryGuard());

        for (var value : new int[] {0x00, 0x40, 0x03, 'a', 'b'}) {
            channel.writeInbound(bytes(value));
            assertThat(guard(channel).atFrameBoundary()).isFalse();
        }
        channel.writeInbound(bytes('c'));

        assertThat(guard(channel).atFrameBoundary()).isTrue();
        channel.finishAndReleaseAll();
    }

    @Test
    void inputEndingInsideAFrame_raisesAFrameError() {
        var channel = new EmbeddedChannel(new Http3FrameBoundaryGuard());

        channel.writeInbound(bytes(0x00, 0x0A, 'h', 'e', 'l', 'l', 'o'));
        channel.pipeline().fireUserEventTriggered(ChannelInputShutdownEvent.INSTANCE);

        assertThatThrownBy(channel::checkException).isInstanceOfSatisfying(Http3Exception.class,
                                                                          e -> assertThat(e.errorCode())
                                                                          .isEqualTo(Http3ErrorCode.H3_FRAME_ERROR));
        channel.finishAndReleaseAll();
    }

    @Test
    void inputEndingAtABoundary_raisesNothing() {
        var channel = new EmbeddedChannel(new Http3FrameBoundaryGuard());

        channel.writeInbound(bytes(0x00, 0x05, 'h', 'e', 'l', 'l', 'o'));
        channel.pipeline().fireUserEventTriggered(ChannelInputShutdownEvent.INSTANCE);

        channel.checkException();
        channel.finishAndReleaseAll();
    }

    private static boolean boundaryAfter(int... values) {
        var channel = new EmbeddedChannel(new Http3FrameBoundaryGuard());

        channel.writeInbound(bytes(values));

        var boundary = guard(channel).atFrameBoundary();

        channel.finishAndReleaseAll();

        return boundary;
    }

    private static Http3FrameBoundaryGuard guard(EmbeddedChannel channel) {
        return channel.pipeline().get(Http3FrameBoundaryGuard.class);
    }

    private static ByteBuf bytes(int... values) {
        var raw = new byte[values.length];

        for (int i = 0; i < values.length; i++) {
            raw[i] = (byte) values[i];
        }

        return Unpooled.wrappedBuffer(raw);
    }
}
