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
import org.pragmatica.dht.DHTMessage;
import org.pragmatica.dht.Partition;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/// In-memory storage engine backed by ConcurrentHashMap.
/// Thread-safe and suitable for development and testing.
/// Data is not persisted across restarts.
public final class MemoryStorageEngine implements StorageEngine {
    private record VersionedEntry(byte[] value, long version) {}

    private final ConcurrentHashMap<ByteArrayKey, VersionedEntry> data = new ConcurrentHashMap<>();

    private MemoryStorageEngine() {}

    public static MemoryStorageEngine memoryStorageEngine() {
        return new MemoryStorageEngine();
    }

    @Override
    public Promise<Option<byte[]>> get(byte[] key) {
        return Promise.success(Option.option(data.get(new ByteArrayKey(key)))
                                     .map(entry -> entry.value().clone()));
    }

    @Override
    public Promise<Unit> put(byte[] key, byte[] value) {
        data.put(new ByteArrayKey(key), new VersionedEntry(value.clone(), Long.MAX_VALUE));
        return Promise.success(Unit.unit());
    }

    @Override
    public Promise<Boolean> putVersioned(byte[] key, byte[] value, long version) {
        var bkey = new ByteArrayKey(key);
        var clonedValue = value.clone();
        var written = new AtomicBoolean(true);
        data.compute(bkey, (_, existing) -> computeVersionedEntry(existing, clonedValue, version, written));
        return Promise.success(written.get());
    }

    private static VersionedEntry computeVersionedEntry(VersionedEntry existing,
                                                        byte[] clonedValue,
                                                        long version,
                                                        AtomicBoolean written) {
        if (existing != null && existing.version() >= version) {
            written.set(false);
            return existing;
        }
        return new VersionedEntry(clonedValue, version);
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
                                   .map(e -> new DHTMessage.KeyValue(e.getKey()
                                                                      .data(),
                                                                     e.getValue()
                                                                      .value()))
                                   .toList());
    }

    @Override
    public Promise<List<DHTMessage.KeyValue>> entriesForPartition(ConsistentHashRing<?> ring, Partition partition) {
        return Promise.success(data.entrySet()
                                   .stream()
                                   .filter(e -> ring.partitionFor(e.getKey()
                                                                   .data())
                                                    .equals(partition))
                                   .map(e -> new DHTMessage.KeyValue(e.getKey()
                                                                      .data(),
                                                                     e.getValue()
                                                                      .value()))
                                   .toList());
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
