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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.ConsensusCodecs;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import static org.assertj.core.api.Assertions.assertThat;

/// #2258 spec 3.2 item 7 on the wire (NT-133): `PutRequest.repair` and `PutResponse.capacityExceeded`, `usedBytes`,
/// `maxBytes` survive encode and decode. The records are wire records (flag day: a node of the previous build cannot read
/// them, and the other way round).
class DHTMessageCapacityCodecTest {
    private static SliceCodec codec() {
        var codecs = new ArrayList<SliceCodec.TypeCodec<?>>();

        codecs.addAll(ConsensusCodecs.CODECS);
        codecs.addAll(DhtCodecs.CODECS);

        return SliceCodec.sliceCodec(FrameworkCodecs.frameworkCodecs(), codecs);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void putRequest_roundTrip_preservesTheRepairMark() {
        var codec = codec();
        var buf = Unpooled.buffer();

        try {
            codec.write(buf, new DHTMessage.PutRequest("req-1", new NodeId("n1"), bytes("k"), bytes("v"), 42L, 0L, 7L, 3L, 9L, true));
            codec.write(buf, new DHTMessage.PutRequest("req-2", new NodeId("n1"), bytes("k"), bytes("v"), 42L, 0L, 7L, 3L, 9L, false));
            DHTMessage.PutRequest repair = codec.read(buf);
            DHTMessage.PutRequest client = codec.read(buf);

            assertThat(repair.repair()).isTrue();
            assertThat(client.repair()).isFalse();
            assertThat(repair.replicationVersion()).as("the fields before it are unchanged").isEqualTo(9L);
            assertThat(repair.key()).isEqualTo(bytes("k"));
        } finally {
            buf.release();
        }
    }

    @Test
    void putResponse_roundTrip_preservesTheCapacityRefusal() {
        var codec = codec();
        var buf = Unpooled.buffer();

        try {
            codec.write(buf, DHTMessage.PutResponse.capacityRefusal("req-3", new NodeId("n2"), 134_217_000L, 134_217_728L));
            codec.write(buf, new DHTMessage.PutResponse("req-4", new NodeId("n2"), true, false, false, false, false));
            DHTMessage.PutResponse refused = codec.read(buf);
            DHTMessage.PutResponse accepted = codec.read(buf);

            assertThat(refused.capacityExceeded()).isTrue();
            assertThat(refused.success()).isFalse();
            assertThat(refused.usedBytes()).isEqualTo(134_217_000L);
            assertThat(refused.maxBytes()).isEqualTo(134_217_728L);
            assertThat(accepted.capacityExceeded()).isFalse();
            assertThat(accepted.usedBytes()).isZero();
            assertThat(accepted.maxBytes()).isZero();
            assertThat(accepted.success()).isTrue();
        } finally {
            buf.release();
        }
    }
}
