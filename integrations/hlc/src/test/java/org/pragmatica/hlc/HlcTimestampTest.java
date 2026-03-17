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

package org.pragmatica.hlc;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.hlc.HlcTimestamp.compareTime;
import static org.pragmatica.hlc.HlcTimestamp.pack;

class HlcTimestampTest {

    @Test
    void pack_roundtrip_preservesBits() {
        var micros = 1_700_000_000_000L;
        var counter = 42;
        var packed = pack(micros, counter);
        var ts = new HlcTimestamp(packed, "node-1");

        assertThat(ts.physicalMicros()).isEqualTo(micros);
        assertThat(ts.counter()).isEqualTo(counter);
    }

    @Test
    void compareTo_differentPhysical_orderedByTime() {
        var earlier = new HlcTimestamp(pack(1000L, 0), "node-b");
        var later = new HlcTimestamp(pack(2000L, 0), "node-a");

        assertThat(earlier).isLessThan(later);
        assertThat(later).isGreaterThan(earlier);
    }

    @Test
    void compareTo_sameTime_orderedByNodeId() {
        var packed = pack(1000L, 5);
        var nodeA = new HlcTimestamp(packed, "node-a");
        var nodeB = new HlcTimestamp(packed, "node-b");

        assertThat(nodeA).isLessThan(nodeB);
        assertThat(nodeB).isGreaterThan(nodeA);
    }

    @Test
    void compareTime_packedOnly_ignoresNodeId() {
        var packed1 = pack(1000L, 1);
        var packed2 = pack(1000L, 2);

        assertThat(compareTime(packed1, packed2)).isNegative();
        assertThat(compareTime(packed2, packed1)).isPositive();
        assertThat(compareTime(packed1, packed1)).isZero();
    }

    @Test
    void toString_formatsCorrectly() {
        var ts = new HlcTimestamp(pack(12345L, 7), "my-node");

        assertThat(ts.toString()).isEqualTo("12345/7@my-node");
    }

    @Test
    void zero_isSmallestTimestamp() {
        var nonZero = new HlcTimestamp(pack(1L, 0), "a");

        assertThat(HlcTimestamp.ZERO).isLessThan(nonZero);
        assertThat(HlcTimestamp.ZERO.physicalMicros()).isZero();
        assertThat(HlcTimestamp.ZERO.counter()).isZero();
        assertThat(HlcTimestamp.ZERO.nodeId()).isEmpty();
    }
}
