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

package org.pragmatica.dht;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.ConsensusCodecs;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/// Wire round-trip of the owner-epoch primitives added to the DHT put path (#345 piece 1c): the two
/// `long`s (`epochTerm`, `epochCounter`) on `PutRequest` and `KeyValue` must survive encode/decode so
/// every replica receives the fencing token the writer stamped.
class DHTMessageEpochCodecTest {
    private static SliceCodec codec() {
        var codecs = new ArrayList<SliceCodec.TypeCodec<?>>();
        codecs.addAll(ConsensusCodecs.CODECS);
        codecs.addAll(DhtCodecs.CODECS);
        return SliceCodec.sliceCodec(FrameworkCodecs.frameworkCodecs(), codecs);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void putRequest_roundTrip_preservesEpochPrimitives() {
        var codec = codec();
        var original = new DHTMessage.PutRequest("req-1", new NodeId("n1"), bytes("k"), bytes("v"), 4242L, 7L, 3L);
        var buf = Unpooled.buffer();

        try {
            codec.write(buf, original);
            DHTMessage.PutRequest decoded = codec.read(buf);

            assertThat(decoded.version()).isEqualTo(4242L);
            assertThat(decoded.epochTerm()).isEqualTo(7L);
            assertThat(decoded.epochCounter()).isEqualTo(3L);
            assertThat(decoded.key()).isEqualTo(bytes("k"));
            assertThat(decoded.value()).isEqualTo(bytes("v"));
        } finally {
            buf.release();
        }
    }

    @Test
    void keyValue_roundTrip_preservesEpochPrimitives() {
        var codec = codec();
        var original = new DHTMessage.KeyValue(bytes("mk"), bytes("mv"), 99L, 12L, 5L);
        var buf = Unpooled.buffer();

        try {
            codec.write(buf, original);
            DHTMessage.KeyValue decoded = codec.read(buf);

            assertThat(decoded.version()).isEqualTo(99L);
            assertThat(decoded.epochTerm()).isEqualTo(12L);
            assertThat(decoded.epochCounter()).isEqualTo(5L);
            assertThat(decoded.key()).isEqualTo(bytes("mk"));
            assertThat(decoded.value()).isEqualTo(bytes("mv"));
        } finally {
            buf.release();
        }
    }
}
