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

package org.pragmatica.consensus.net.quic;

import org.junit.jupiter.api.Test;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import static org.assertj.core.api.Assertions.assertThat;

/// Round-trips [KeepAliveMessage] Ping/Pong through the slice codec. The wire payload is a
/// single signed long (`seq`) — sender is carried by the connection, not the wire.
class KeepAliveMessageCodecTest {
    private final SliceCodec codec = SliceCodec.sliceCodec(FrameworkCodecs.frameworkCodecs(), QuicCodecs.CODECS);

    @Test
    void encodeDecode_ping_roundTripsSeq() {
        var ping = new KeepAliveMessage.Ping(42L);
        Object decoded = codec.decode(codec.encode(ping));
        assertThat(decoded).isEqualTo(ping);
    }

    @Test
    void encodeDecode_pong_roundTripsSeq() {
        var pong = new KeepAliveMessage.Pong(7L);
        Object decoded = codec.decode(codec.encode(pong));
        assertThat(decoded).isEqualTo(pong);
    }

    @Test
    void encodeDecode_pingAndPong_areDistinctTypes() {
        var ping = new KeepAliveMessage.Ping(5L);
        var pong = new KeepAliveMessage.Pong(5L);
        Object decodedPing = codec.decode(codec.encode(ping));
        Object decodedPong = codec.decode(codec.encode(pong));
        assertThat(decodedPing).isInstanceOf(KeepAliveMessage.Ping.class);
        assertThat(decodedPong).isInstanceOf(KeepAliveMessage.Pong.class);
    }
}
