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

import static org.assertj.core.api.Assertions.assertThat;

class StreamTypeTest {

    @Nested
    class StreamIndices {
        @Test
        void consensus_hasIndex0() {
            assertThat(StreamType.CONSENSUS.streamIndex()).isEqualTo(0);
        }

        @Test
        void kvStore_hasIndex1() {
            assertThat(StreamType.KV_STORE.streamIndex()).isEqualTo(1);
        }

        @Test
        void httpForward_hasIndex2() {
            assertThat(StreamType.HTTP_FORWARD.streamIndex()).isEqualTo(2);
        }

        @Test
        void dhtRelay_hasIndex3() {
            assertThat(StreamType.DHT_RELAY.streamIndex()).isEqualTo(3);
        }
    }

    @Nested
    class Lifecycle {
        @Test
        void consensus_isLongLived() {
            assertThat(StreamType.CONSENSUS.longLived()).isTrue();
        }

        @Test
        void kvStore_isLongLived() {
            assertThat(StreamType.KV_STORE.longLived()).isTrue();
        }

        @Test
        void httpForward_isShortLived() {
            assertThat(StreamType.HTTP_FORWARD.longLived()).isFalse();
        }

        @Test
        void dhtRelay_isShortLived() {
            assertThat(StreamType.DHT_RELAY.longLived()).isFalse();
        }
    }

    @Nested
    class FromIndex {
        @Test
        void fromIndex_validIndex_returnsStreamType() {
            assertThat(StreamType.fromIndex(0).or(StreamType.DHT_RELAY)).isEqualTo(StreamType.CONSENSUS);
            assertThat(StreamType.fromIndex(1).or(StreamType.DHT_RELAY)).isEqualTo(StreamType.KV_STORE);
            assertThat(StreamType.fromIndex(2).or(StreamType.DHT_RELAY)).isEqualTo(StreamType.HTTP_FORWARD);
            assertThat(StreamType.fromIndex(3).or(StreamType.CONSENSUS)).isEqualTo(StreamType.DHT_RELAY);
        }

        @Test
        void fromIndex_negativeIndex_returnsEmpty() {
            assertThat(StreamType.fromIndex(-1).isEmpty()).isTrue();
        }

        @Test
        void fromIndex_outOfRange_returnsEmpty() {
            assertThat(StreamType.fromIndex(4).isEmpty()).isTrue();
            assertThat(StreamType.fromIndex(100).isEmpty()).isTrue();
        }
    }
}
