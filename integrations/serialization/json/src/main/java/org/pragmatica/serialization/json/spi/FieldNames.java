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
package org.pragmatica.serialization.json.spi;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;


/// Pre-encoded field names of one record type, shared by its reader and writer.
///
/// Each name is kept in two punctuated forms: `"name":` for the first field and `,"name":` for every
/// later one. The writer emits them as single copies; the reader compares the input against them
/// directly, which is the expected-order fast path. When the input deviates, the reader falls back to
/// [#lookup], a hash probe that always confirms the bytes, so an unknown key can never bind to a
/// declared field through a hash collision.
public final class FieldNames {
    private final String[] names;
    private final byte[][] raw;
    private final byte[][] first;
    private final byte[][] next;
    private final byte[][] quoted;
    private final int[] slots;
    private final int mask;
    // Perfect hash over (length, one byte from the front, one from the back); null if none was found.
    private int[] perfect;
    private int perfectMask;
    private int pickFront;
    private int pickBack;
    private int multiplier;

    private FieldNames(String[] names) {
        this.names = names;
        this.raw = new byte[names.length][];
        this.first = new byte[names.length][];
        this.next = new byte[names.length][];
        this.quoted = new byte[names.length][];
        var capacity = Integer.highestOneBit(Math.max(4, names.length * 4) - 1) << 1;

        this.slots = new int[capacity];
        this.mask = capacity - 1;
        for (int i = 0; i < names.length; i++) {
            raw[i] = names[i].getBytes(StandardCharsets.UTF_8);
            first[i] = ("\"" + names[i] + "\":").getBytes(StandardCharsets.UTF_8);
            next[i] = (",\"" + names[i] + "\":").getBytes(StandardCharsets.UTF_8);
            quoted[i] = ("\"" + names[i] + "\"").getBytes(StandardCharsets.UTF_8);
            insert(i);
        }

        findPerfectHash();
    }

    private static final int[] MULTIPLIERS = {31, 131, 1031, 0x27D4EB2D};

    private void findPerfectHash() {
        for (int size = Integer.highestOneBit(Math.max(2, raw.length * 2) - 1) << 1; size <= 512; size <<= 1) {
            for (int front = 0; front < 4; front++) {
                for (int back = 0; back < 4; back++) {
                    for (var mult : MULTIPLIERS) {
                        if (tryPerfect(size, front, back, mult)) {
                            return;
                        }
                    }
                }
            }
        }
    }

    private boolean tryPerfect(int size, int front, int back, int mult) {
        var table = new int[size];

        for (int i = 0; i < raw.length; i++) {
            var slot = perfectSlot(raw[i], 0, raw[i].length, front, back, mult, size - 1);

            if (table[slot] != 0) {
                return false;
            }

            table[slot] = i + 1;
        }

        perfect = table;
        perfectMask = size - 1;
        pickFront = front;
        pickBack = back;
        multiplier = mult;

        return true;
    }

    private static int perfectSlot(byte[] buf, int start, int len, int front, int back, int mult, int mask) {
        var a = buf[start + Math.min(front, len - 1)] & 0xFF;
        var b = buf[start + Math.max(len - 1 - back, 0)] & 0xFF;
        var h = (len * 0x9E3779B1) ^ (a * mult) ^ (b * 0x85EBCA6B);

        return (h ^ (h >>> 15)) & mask;
    }

    boolean isPerfect() {
        return perfect != null;
    }

    /// Names must be plain JSON-safe identifiers; the generator guarantees this, so no escaping is done.
    public static FieldNames fieldNames(String... names) {
        return new FieldNames(names.clone());
    }

    public int size() {
        return names.length;
    }

    public String name(int index) {
        return names[index];
    }

    public byte[] first(int index) {
        return first[index];
    }

    public byte[] next(int index) {
        return next[index];
    }

    /// `"name"` as a string value, used to write enum constants.
    public byte[] quoted(int index) {
        return quoted[index];
    }

    /// Index of the name stored in `buf[start, start + len)`, or [JsonReader#UNKNOWN_FIELD].
    int lookup(byte[] buf, int start, int len) {
        if (perfect != null) {
            if (len == 0) {
                return JsonReader.UNKNOWN_FIELD;
            }

            var entry = perfect[perfectSlot(buf, start, len, pickFront, pickBack, multiplier, perfectMask)];

            if (entry == 0) {
                return JsonReader.UNKNOWN_FIELD;
            }

            var candidate = raw[entry - 1];

            return candidate.length == len && Arrays.equals(candidate, 0, len, buf, start, start + len)
                   ? entry - 1
                   : JsonReader.UNKNOWN_FIELD;
        }

        for (int slot = hash(buf, start, len) & mask;; slot = (slot + 1) & mask) {
            var entry = slots[slot];

            if (entry == 0) {
                return JsonReader.UNKNOWN_FIELD;
            }

            var candidate = raw[entry - 1];

            if (candidate.length == len && Arrays.equals(candidate, 0, len, buf, start, start + len)) {
                return entry - 1;
            }
        }
    }

    private void insert(int index) {
        var bytes = raw[index];
        var slot = hash(bytes, 0, bytes.length) & mask;

        while (slots[slot] != 0) {
            slot = (slot + 1) & mask;
        }

        slots[slot] = index + 1;
    }

    private static int hash(byte[] buf, int start, int len) {
        var h = 0x811C9DC5;

        for (int i = start, limit = start + len; i < limit; i++) {
            h = (h ^ buf[i]) * 0x01000193;
        }

        return h ^ (h >>> 16);
    }
}
