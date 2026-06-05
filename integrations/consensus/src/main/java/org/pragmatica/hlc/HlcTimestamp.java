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

import org.pragmatica.consensus.NodeId;
import org.pragmatica.serialization.Codec;

/// Hybrid Logical Clock timestamp combining physical time (epoch milliseconds) and a logical counter
/// into a single packed long, paired with a node identifier for total ordering.
///
/// Packing layout: upper 48 bits = milliseconds since epoch (valid past year 6000),
/// lower 16 bits = logical counter.
@Codec
public record HlcTimestamp(long packed, NodeId nodeId) implements Comparable<HlcTimestamp> {
    public static final HlcTimestamp ZERO = new HlcTimestamp(0L, new NodeId(""));
    public static final HlcTimestamp MIN = ZERO;

    private static final int COUNTER_BITS = 16;
    private static final long COUNTER_MASK = 0xFFFFL;

    /// Packs physical milliseconds and logical counter into a single long.
    public static long pack(long physicalMillis, int counter) {
        return (physicalMillis << COUNTER_BITS) | (counter & COUNTER_MASK);
    }

    /// Extracts the physical milliseconds component from the packed value.
    public long physicalMillis() {
        return packed >>> COUNTER_BITS;
    }

    /// Extracts the logical counter component from the packed value.
    public int counter() {
        return (int) (packed & COUNTER_MASK);
    }

    /// Compares two packed values without considering node identity.
    /// Useful for DHT operations where only temporal ordering matters.
    public static int compareTime(long packed1, long packed2) {
        return Long.compare(packed1, packed2);
    }

    @Override
    public int compareTo(HlcTimestamp other) {
        var timeCompare = Long.compare(packed, other.packed);
        return timeCompare != 0 ? timeCompare : nodeId.compareTo(other.nodeId);
    }

    @Override
    public String toString() {
        return physicalMillis() + "/" + counter() + "@" + nodeId.id();
    }
}
