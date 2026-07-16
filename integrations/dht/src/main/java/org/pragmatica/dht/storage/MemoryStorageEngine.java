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

import org.pragmatica.dht.ConsistentHashRing;
import org.pragmatica.dht.DHTError;
import org.pragmatica.dht.DHTMessage;
import org.pragmatica.dht.Partition;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/// In-memory storage engine backed by ConcurrentHashMap.
/// Thread-safe and suitable for development and testing.
/// Data is not persisted across restarts.
public final class MemoryStorageEngine implements StorageEngine {
    private record VersionedEntry(byte[] value, long version, long epochTerm, long epochCounter) {}

    private final ConcurrentHashMap<ByteArrayKey, VersionedEntry> data = new ConcurrentHashMap<>();
    private final OwnerEpochGate epochGate;

    private MemoryStorageEngine(OwnerEpochGate epochGate) {
        this.epochGate = epochGate;
    }

    /// Fence-free engine ([OwnerEpochGate#noOp]) for non-cluster DHT paths and tests.
    public static MemoryStorageEngine memoryStorageEngine() {
        return new MemoryStorageEngine(OwnerEpochGate.noOp());
    }

    /// Engine whose versioned writes are owner-epoch-fenced by `epochGate` (#345 piece 1c). The
    /// aether-level wiring supplies a high-water-backed gate; every replica enforces monotonicity
    /// at its own commit point.
    public static MemoryStorageEngine memoryStorageEngine(OwnerEpochGate epochGate) {
        return new MemoryStorageEngine(epochGate);
    }

    @Override
    public Promise<Option<byte[]>> get(byte[] key) {
        return Promise.success(Option.option(data.get(new ByteArrayKey(key)))
                                     .map(entry -> entry.value().clone()));
    }

    @Override
    public Promise<Unit> put(byte[] key, byte[] value) {
        data.put(new ByteArrayKey(key), new VersionedEntry(value.clone(), 0L, 0L, 0L));
        return Promise.success(Unit.unit());
    }

    @Override
    public Promise<Boolean> putVersioned(byte[] key, byte[] value, long version, long epochTerm, long epochCounter) {
        if (epochGate.isStale(key, epochTerm, epochCounter)) {
            return DHTError.staleEpochWrite(epochTerm, epochCounter).promise();
        }
        var bkey = new ByteArrayKey(key);
        var clonedValue = value.clone();
        var written = new AtomicBoolean(true);
        data.compute(bkey, (_, existing) -> computeVersionedEntry(existing, clonedValue, version, epochTerm, epochCounter, written, epochGate.epochOrderingEnabled()));
        if (written.get()) {
            epochGate.advance(key, epochTerm, epochCounter);
        }
        return Promise.success(written.get());
    }

    /// Decide the stored entry under the owner-epoch fence then the within-epoch HLC-version LWW
    /// (#345 piece 1c). The domain high-water gate in [#putVersioned] already rejected a
    /// strictly-older-epoch write before this point; here the entry's OWN persisted epoch provides
    /// within-key ordering (spec §3.2):
    ///
    ///   - first write (no existing) → accept;
    ///   - incoming epoch STRICTLY newer than the stored entry's epoch → accept unconditionally (a
    ///     new owner wins regardless of HLC version — its HLC may legitimately trail the prior
    ///     owner's);
    ///   - incoming epoch STRICTLY older than the stored entry's epoch → drop (a late same-key write
    ///     from a prior epoch);
    ///   - same epoch → existing HLC `version` LWW, byte-for-byte unchanged.
    private static VersionedEntry computeVersionedEntry(VersionedEntry existing,
                                                        byte[] clonedValue,
                                                        long version,
                                                        long epochTerm,
                                                        long epochCounter,
                                                        AtomicBoolean written,
                                                        boolean epochOrdering) {
        if (existing == null) {
            return new VersionedEntry(clonedValue, version, epochTerm, epochCounter);
        }
        if (epochOrdering) {
            var epochOrder = compareEpoch(epochTerm, epochCounter, existing.epochTerm(), existing.epochCounter());
            if (epochOrder > 0) {
                return new VersionedEntry(clonedValue, version, epochTerm, epochCounter);
            }
            if (epochOrder < 0) {
                written.set(false);
                return existing;
            }
        }
        if (existing.version() >= version) {
            written.set(false);
            return existing;
        }
        return new VersionedEntry(clonedValue, version, epochTerm, epochCounter);
    }

    /// Lexicographic `(term, counter)` comparison — identical semantics to `Epoch.compareTo`, which
    /// mints these primitives in the BSL-1.1 module this engine must not depend on.
    private static int compareEpoch(long term1, long counter1, long term2, long counter2) {
        var byTerm = Long.compare(term1, term2);
        return byTerm != 0 ? byTerm : Long.compare(counter1, counter2);
    }

    @Override
    public Promise<Boolean> remove(byte[] key) {
        return Promise.success(data.remove(new ByteArrayKey(key)) != null);
    }

    @Override
    public Promise<Boolean> exists(byte[] key) {
        return Promise.success(data.containsKey(new ByteArrayKey(key)));
    }

    @Override
    public long size() {
        return data.size();
    }

    @Override
    public Promise<Unit> clear() {
        data.clear();
        return Promise.success(Unit.unit());
    }

    @Override
    public Promise<Unit> shutdown() {
        data.clear();
        return Promise.success(Unit.unit());
    }

    @Override
    public Promise<List<byte[]>> keys() {
        return Promise.success(data.keySet()
                                   .stream()
                                   .map(ByteArrayKey::data)
                                   .map(byte[]::clone)
                                   .toList());
    }

    @Override
    public Promise<List<DHTMessage.KeyValue>> entries() {
        return Promise.success(data.entrySet()
                                   .stream()
                                   .map(MemoryStorageEngine::toKeyValue)
                                   .toList());
    }

    @Override
    public Promise<List<DHTMessage.KeyValue>> entriesForPartition(ConsistentHashRing<?> ring, Partition partition) {
        return Promise.success(data.entrySet()
                                   .stream()
                                   .filter(e -> ring.partitionFor(e.getKey()
                                                                   .data())
                                                    .equals(partition))
                                   .map(MemoryStorageEngine::toKeyValue)
                                   .toList());
    }

    private static DHTMessage.KeyValue toKeyValue(Map.Entry<ByteArrayKey, VersionedEntry> e) {
        return new DHTMessage.KeyValue(e.getKey().data(),
                                       e.getValue().value(),
                                       e.getValue().version(),
                                       e.getValue().epochTerm(),
                                       e.getValue().epochCounter());
    }

    /// Wrapper for byte[] to use as HashMap key with proper equals/hashCode.
    /// Clones input array to prevent external mutation from corrupting keys.
    private record ByteArrayKey(byte[] data) {
        ByteArrayKey(byte[] data) {
            this.data = data.clone();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (! (o instanceof ByteArrayKey that)) return false;
            return Arrays.equals(data, that.data);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(data);
        }
    }
}
