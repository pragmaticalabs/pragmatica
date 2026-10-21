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
package org.pragmatica.dht.storage;

import java.util.List;
import java.util.zip.CRC32;

import org.pragmatica.dht.DHTMessage;


/// The metadata an engine keeps BESIDE a stored value (A6, the repair plane): the entry's key, its stamp (HLC
/// version and owner epoch), whether it is a tombstone, the CRC32 of its value and the value's length. Repair
/// compares and pages partitions by stamps, so a digest round reads no value and a page is sized before any value
/// is read. The value CRC is computed once, when the value is stored.
///
/// @param valueCrc    CRC32 of the stored value (0 for a tombstone, whose value is empty)
/// @param valueLength length of the stored value in bytes (0 for a tombstone)
public record KeyStamp(byte[] key,
                       long version,
                       long epochIncarnation,
                       long epochTerm,
                       long epochCounter,
                       boolean tombstone,
                       long valueCrc,
                       int valueLength) {
    /// Fixed per-entry cost on the wire beyond key and value: the stamp's four longs, the tombstone flag and the
    /// length prefixes of the key and value arrays, rounded up.
    public static final int ENTRY_OVERHEAD_BYTES = 64;

    public KeyStamp {
        key = key.clone();
    }

    /// The stamp of an entry whose value is in hand: the CRC is computed here. Engines that keep a stamp index never
    /// call this on the repair path; it serves the [StorageEngine] defaults for engines without one.
    public static KeyStamp keyStamp(DHTMessage.KeyValue entry) {
        return new KeyStamp(entry.key(),
                            entry.version(),
                            entry.epochIncarnation(),
                            entry.epochTerm(),
                            entry.epochCounter(),
                            entry.tombstone(),
                            crcOf(entry.value()),
                            entry.value().length);
    }

    /// The stamps of entries whose values are in hand.
    public static List<KeyStamp> keyStamps(List<DHTMessage.KeyValue> entries) {
        return entries.stream()
                      .map(KeyStamp::keyStamp)
                      .toList();
    }

    /// CRC32 of a value, the checksum a stamp carries.
    public static long crcOf(byte[] value) {
        var crc = new CRC32();

        crc.update(value);

        return crc.getValue();
    }

    /// The bytes this entry costs in a migration page: key, value and the fixed per-entry overhead.
    public long transferBytes() {
        return (long) key.length + valueLength + ENTRY_OVERHEAD_BYTES;
    }

    /// The store's per-key order against an entry: owner epoch first, then HLC version (#345 piece 1c, #1818) — the
    /// order of [DHTMessage.KeyValue#compareOrder]. A non-negative result means this stamp is at least as new.
    public int compareOrder(DHTMessage.KeyValue other) {
        var byEpoch = compareEpoch(other);

        return byEpoch != 0
               ? byEpoch
               : Long.compare(version, other.version());
    }

    private int compareEpoch(DHTMessage.KeyValue other) {
        var byIncarnation = Long.compare(epochIncarnation, other.epochIncarnation());

        if (byIncarnation != 0) {
            return byIncarnation;
        }

        var byTerm = Long.compare(epochTerm, other.epochTerm());

        return byTerm != 0
               ? byTerm
               : Long.compare(epochCounter, other.epochCounter());
    }
}
