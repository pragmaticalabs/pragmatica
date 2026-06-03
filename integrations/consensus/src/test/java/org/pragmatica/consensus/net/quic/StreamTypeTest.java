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

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.messaging.StreamType;

import static org.assertj.core.api.Assertions.assertThat;

class StreamTypeTest {

    @Nested
    class StreamIndices {
        @Test
        void consensus_hasIndex0() {
            assertThat(StreamType.CONSENSUS.streamIndex()).isEqualTo(0);
        }

        @Test
        void kv_hasIndex1() {
            assertThat(StreamType.KV.streamIndex()).isEqualTo(1);
        }

        @Test
        void metrics_hasIndex2() {
            assertThat(StreamType.METRICS.streamIndex()).isEqualTo(2);
        }

        @Test
        void invoke_hasIndex3() {
            assertThat(StreamType.INVOKE.streamIndex()).isEqualTo(3);
        }

        @Test
        void forward_hasIndex4() {
            assertThat(StreamType.FORWARD.streamIndex()).isEqualTo(4);
        }

        @Test
        void dht_hasIndex5() {
            assertThat(StreamType.DHT.streamIndex()).isEqualTo(5);
        }

        @Test
        void control_hasIndex6() {
            assertThat(StreamType.CONTROL.streamIndex()).isEqualTo(6);
        }
    }

    @Nested
    class FromIndex {
        @Test
        void fromIndex_validIndex_returnsStreamType() {
            assertThat(StreamType.fromIndex(0).or(StreamType.CONTROL)).isEqualTo(StreamType.CONSENSUS);
            assertThat(StreamType.fromIndex(1).or(StreamType.CONTROL)).isEqualTo(StreamType.KV);
            assertThat(StreamType.fromIndex(2).or(StreamType.CONTROL)).isEqualTo(StreamType.METRICS);
            assertThat(StreamType.fromIndex(3).or(StreamType.CONTROL)).isEqualTo(StreamType.INVOKE);
            assertThat(StreamType.fromIndex(4).or(StreamType.CONTROL)).isEqualTo(StreamType.FORWARD);
            assertThat(StreamType.fromIndex(5).or(StreamType.CONTROL)).isEqualTo(StreamType.DHT);
            assertThat(StreamType.fromIndex(6).or(StreamType.CONSENSUS)).isEqualTo(StreamType.CONTROL);
        }

        @Test
        void fromIndex_negativeIndex_returnsEmpty() {
            assertThat(StreamType.fromIndex(-1).isEmpty()).isTrue();
        }

        @Test
        void fromIndex_outOfRange_returnsEmpty() {
            assertThat(StreamType.fromIndex(7).isEmpty()).isTrue();
            assertThat(StreamType.fromIndex(100).isEmpty()).isTrue();
        }
    }
}
