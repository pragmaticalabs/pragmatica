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

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.dht.ConsistentHashRing;
import org.pragmatica.dht.DHTError;
import org.pragmatica.dht.DHTMessage;
import org.pragmatica.dht.Partition;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.NullReturn;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// In-memory storage engine backed by ConcurrentHashMap.
/// Thread-safe and suitable for development and testing.
/// Data is not persisted across restarts.
public final class MemoryStorageEngine implements StorageEngine {
    private static final byte[] NO_VALUE = new byte[0];

    /// A stored entry: a live value, or a TOMBSTONE (#1777 track 3) — a removed key, ordered exactly like a value
    /// so it supersedes every older copy and loses to every newer write.
    private record VersionedEntry(byte[] value,
                                  long version,
                                  long epochIncarnation,
                                  long epochTerm,
                                  long epochCounter,
                                  boolean tombstone) {
        boolean live() {
            return ! tombstone;
        }
    }

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
                                     .filter(VersionedEntry::live)
                                     .map(entry -> entry.value()
                                                        .clone()));
    }

    @Override
    public Promise<Option<DHTMessage.KeyValue>> getEntry(byte[] key) {
        var bkey = new ByteArrayKey(key);

        return Promise.success(Option.option(data.get(bkey)).map(entry -> toKeyValue(bkey, entry)));
    }

    @Override
    public Promise<Unit> put(byte[] key, byte[] value) {
        data.put(new ByteArrayKey(key), new VersionedEntry(value.clone(), 0L, 0L, 0L, 0L, false));

        return Promise.success(Unit.unit());
    }

    @Override
    public Promise<Boolean> putVersioned(byte[] key,
                                         byte[] value,
                                         long version,
                                         long epochIncarnation,
                                         long epochTerm,
                                         long epochCounter) {
        if (epochGate.isStale(key, epochIncarnation, epochTerm, epochCounter)) {
            return DHTError.staleEpochWrite(epochIncarnation, epochTerm, epochCounter).promise();
        }

        return Promise.success(storeVersioned(key,
                                              new VersionedEntry(value.clone(),
                                                                 version,
                                                                 epochIncarnation,
                                                                 epochTerm,
                                                                 epochCounter,
                                                                 false),
                                              true,
                                              true,
                                              new AtomicBoolean(),
                                              new AtomicReference<>()));
    }

    @Override
    public Promise<Displaced> putVersionedDisplacing(byte[] key,
                                                     byte[] value,
                                                     long version,
                                                     long epochIncarnation,
                                                     long epochTerm,
                                                     long epochCounter) {
        return writeDisplacing(key,
                               new VersionedEntry(value.clone(), version, epochIncarnation, epochTerm, epochCounter, false));
    }

    @Override
    public Promise<Displaced> removeVersionedDisplacing(byte[] key,
                                                        long version,
                                                        long epochIncarnation,
                                                        long epochTerm,
                                                        long epochCounter) {
        return writeDisplacing(key, new VersionedEntry(NO_VALUE, version, epochIncarnation, epochTerm, epochCounter, true));
    }

    private Promise<Displaced> writeDisplacing(byte[] key, VersionedEntry incoming) {
        if (epochGate.isStale(key, incoming.epochIncarnation(), incoming.epochTerm(), incoming.epochCounter())) {
            return DHTError.staleEpochWrite(incoming.epochIncarnation(), incoming.epochTerm(), incoming.epochCounter()).promise();
        }

        var displaced = new AtomicReference<VersionedEntry>();
        var written = storeVersioned(key, incoming, true, true, new AtomicBoolean(), displaced);

        return Promise.success(new Displaced(written,
                                             written
                                             ? Option.option(displaced.get()).map(entry -> toKeyValue(new ByteArrayKey(key), entry))
                                             : Option.none()));
    }

    @Override
    public Promise<Boolean> removeVersioned(byte[] key,
                                            long version,
                                            long epochIncarnation,
                                            long epochTerm,
                                            long epochCounter) {
        if (epochGate.isStale(key, epochIncarnation, epochTerm, epochCounter)) {
            return DHTError.staleEpochWrite(epochIncarnation, epochTerm, epochCounter).promise();
        }

        var supersededLive = new AtomicBoolean();
        var written = storeVersioned(key,
                                     new VersionedEntry(NO_VALUE,
                                                        version,
                                                        epochIncarnation,
                                                        epochTerm,
                                                        epochCounter,
                                                        true),
                                     true,
                                     true,
                                     supersededLive,
                                     new AtomicReference<>());

        return Promise.success(written && supersededLive.get());
    }

    @Override
    public Promise<Boolean> putReplica(byte[] key,
                                       byte[] value,
                                       long version,
                                       long epochIncarnation,
                                       long epochTerm,
                                       long epochCounter) {
        return Promise.success(storeVersioned(key,
                                              new VersionedEntry(value.clone(),
                                                                 version,
                                                                 epochIncarnation,
                                                                 epochTerm,
                                                                 epochCounter,
                                                                 false),
                                              false,
                                              true,
                                              new AtomicBoolean(),
                                              new AtomicReference<>()));
    }

    @Override
    public Promise<Boolean> putReplica(DHTMessage.KeyValue entry, boolean createIfAbsent) {
        return Promise.success(storeVersioned(entry.key(),
                                              new VersionedEntry(entry.tombstone()
                                                                 ? NO_VALUE
                                                                 : entry.value().clone(),
                                                                 entry.version(),
                                                                 entry.epochIncarnation(),
                                                                 entry.epochTerm(),
                                                                 entry.epochCounter(),
                                                                 entry.tombstone()),
                                              false,
                                              createIfAbsent,
                                              new AtomicBoolean(),
                                              new AtomicReference<>()));
    }

    @Override
    public Promise<Integer> dropPartition(ConsistentHashRing<?> ring, Partition partition) {
        var dropped = new AtomicInteger();

        data.keySet().removeIf(key -> inPartition(ring, key, partition) && dropped.incrementAndGet() > 0);

        return Promise.success(dropped.get());
    }

    @Override
    public Promise<Integer> collectTombstones(ConsistentHashRing<?> ring,
                                              Partition partition,
                                              long expiredAtOrBeforeMillis) {
        var collected = new AtomicInteger();

        data.entrySet()
            .stream()
            .filter(e -> e.getValue()
                          .tombstone() && HlcTimestamp.physicalMillis(e.getValue().version()) <= expiredAtOrBeforeMillis)
            .filter(e -> inPartition(ring,
                                     e.getKey(),
                                     partition))
            .toList()
            .forEach(e -> collectIfStill(e.getKey(),
                                         e.getValue(),
                                         collected));

        return Promise.success(collected.get());
    }

    @Override
    public long tombstoneCount() {
        return data.values()
                   .stream()
                   .filter(VersionedEntry::tombstone)
                   .count();
    }

    /// Remove `key` only while it still maps to `tombstone` — a newer entry stored meanwhile is kept.
    private void collectIfStill(ByteArrayKey key, VersionedEntry tombstone, AtomicInteger collected) {
        if (data.remove(key, tombstone)) {
            collected.incrementAndGet();
        }
    }

    private static boolean inPartition(ConsistentHashRing<?> ring, ByteArrayKey key, Partition partition) {
        return ring.partitionFor(key.data())
                   .equals(partition);
    }

    @Override
    public Promise<Boolean> removeIfExactly(byte[] key,
                                            long version,
                                            long epochIncarnation,
                                            long epochTerm,
                                            long epochCounter) {
        var removed = new AtomicBoolean(false);

        data.computeIfPresent(new ByteArrayKey(key),
                              (_, existing) -> keepUnlessExactly(existing,
                                                                 version,
                                                                 epochIncarnation,
                                                                 epochTerm,
                                                                 epochCounter,
                                                                 removed));

        return Promise.success(removed.get());
    }

    @Override
    public Promise<Boolean> restoreIfExactly(byte[] key,
                                             long version,
                                             long epochIncarnation,
                                             long epochTerm,
                                             long epochCounter,
                                             Option<DHTMessage.KeyValue> prior) {
        var restored = new AtomicBoolean(false);

        data.computeIfPresent(new ByteArrayKey(key),
                              (_, existing) -> restoreUnlessSuperseded(existing,
                                                                       version,
                                                                       epochIncarnation,
                                                                       epochTerm,
                                                                       epochCounter,
                                                                       prior,
                                                                       restored));

        return Promise.success(restored.get());
    }

    /// The `computeIfPresent` remapping for [#restoreIfExactly]: the prior entry — a value or a tombstone — replaces the
    /// exact accept; `null` (no prior) removes it, per the JDK contract. The high-water is not touched: a restore is not a
    /// fresh write.
    @NullReturn
    private static VersionedEntry restoreUnlessSuperseded(VersionedEntry existing,
                                                          long version,
                                                          long epochIncarnation,
                                                          long epochTerm,
                                                          long epochCounter,
                                                          Option<DHTMessage.KeyValue> prior,
                                                          AtomicBoolean restored) {
        if (existing.version() != version || existing.epochIncarnation() != epochIncarnation || existing.epochTerm() != epochTerm || existing.epochCounter() != epochCounter) {
            return existing;
        }

        restored.set(true);

        return prior.fold(() -> null,
                          entry -> new VersionedEntry(entry.tombstone()
                                                      ? NO_VALUE
                                                      : entry.value().clone(),
                                                      entry.version(),
                                                      entry.epochIncarnation(),
                                                      entry.epochTerm(),
                                                      entry.epochCounter(),
                                                      entry.tombstone()));
    }

    @Override
    public boolean belowHighWater(byte[] key, long epochIncarnation, long epochTerm, long epochCounter) {
        return epochGate.isStale(key, epochIncarnation, epochTerm, epochCounter);
    }

    /// The `computeIfPresent` remapping for [#removeIfExactly]: `null` removes the entry, per the JDK contract.
    @NullReturn
    private static VersionedEntry keepUnlessExactly(VersionedEntry existing,
                                                    long version,
                                                    long epochIncarnation,
                                                    long epochTerm,
                                                    long epochCounter,
                                                    AtomicBoolean removed) {
        if (existing.version() != version || existing.epochIncarnation() != epochIncarnation || existing.epochTerm() != epochTerm || existing.epochCounter() != epochCounter) {
            return existing;
        }

        removed.set(true);

        return null;
    }

    /// Store `incoming` under the per-key order. `supersededLive` reports whether the entry it replaced was a live
    /// value. `createIfAbsent = false` never creates an entry for an absent key (an expired tombstone copy).
    private boolean storeVersioned(byte[] key,
                                   VersionedEntry incoming,
                                   boolean advanceHighWater,
                                   boolean createIfAbsent,
                                   AtomicBoolean supersededLive,
                                   AtomicReference<VersionedEntry> displaced) {
        var bkey = new ByteArrayKey(key);
        var written = new AtomicBoolean(true);

        data.compute(bkey,
                     (_, existing) -> {
                         var next = computeVersionedEntry(existing,
                                                          incoming,
                                                          createIfAbsent,
                                                          written,
                                                          supersededLive,
                                                          epochGate.epochOrderingEnabled());

                         // read and write are ONE step under the map's per-key lock: nothing lands in between
                         displaced.set(existing);

                         return next;
                     });
        if (written.get() && advanceHighWater) {
            epochGate.advance(key, incoming.epochIncarnation(), incoming.epochTerm(), incoming.epochCounter());
        }

        return written.get();
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
    ///
    /// A tombstone is an entry like any other here (#1777 track 3): it wins and loses by the same order.
    @NullReturn
    private static VersionedEntry computeVersionedEntry(VersionedEntry existing,
                                                        VersionedEntry incoming,
                                                        boolean createIfAbsent,
                                                        AtomicBoolean written,
                                                        AtomicBoolean supersededLive,
                                                        boolean epochOrdering) {
        if (existing == null) {
            written.set(createIfAbsent);

            return createIfAbsent
                   ? incoming
                   : null;
        }

        if (epochOrdering) {
            var epochOrder = compareEpoch(incoming.epochIncarnation(),
                                          incoming.epochTerm(),
                                          incoming.epochCounter(),
                                          existing.epochIncarnation(),
                                          existing.epochTerm(),
                                          existing.epochCounter());

            if (epochOrder > 0) {
                return replaced(existing, incoming, supersededLive);
            }

            if (epochOrder < 0) {
                written.set(false);

                return existing;
            }
        }

        if (existing.version() >= incoming.version()) {
            written.set(false);

            return existing;
        }

        return replaced(existing, incoming, supersededLive);
    }

    private static VersionedEntry replaced(VersionedEntry existing,
                                           VersionedEntry incoming,
                                           AtomicBoolean supersededLive) {
        supersededLive.set(existing.live());

        return incoming;
    }

    /// Lexicographic `(incarnation, term, counter)` comparison — identical semantics to `Epoch.compareTo`,
    /// which mints these primitives in the BSL-1.1 module this engine must not depend on, so it cannot
    /// delegate; the copy is pinned by `MemoryStorageEngineEpochFenceTest.NewerIncarnation` (#1529).
    private static int compareEpoch(long incarnation1,
                                    long term1,
                                    long counter1,
                                    long incarnation2,
                                    long term2,
                                    long counter2) {
        var byIncarnation = Long.compare(incarnation1, incarnation2);

        if (byIncarnation != 0) {
            return byIncarnation;
        }

        var byTerm = Long.compare(term1, term2);

        return byTerm != 0
               ? byTerm
               : Long.compare(counter1, counter2);
    }

    @Override
    public Promise<Boolean> remove(byte[] key) {
        return Promise.success(data.remove(new ByteArrayKey(key)) != null);
    }

    @Override
    public Promise<Boolean> exists(byte[] key) {
        return Promise.success(Option.option(data.get(new ByteArrayKey(key))).filter(VersionedEntry::live).isPresent());
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

    /// The keys of live entries; a removed key's tombstone is not a key in storage.
    @Override
    public Promise<List<byte[]>> keys() {
        return Promise.success(data.entrySet()
                                   .stream()
                                   .filter(e -> e.getValue()
                                                 .live())
                                   .map(e -> e.getKey()
                                              .data())
                                   .map(byte[]::clone)
                                   .toList());
    }

    @Override
    public Promise<List<DHTMessage.KeyValue>> entries() {
        return Promise.success(data.entrySet().stream().map(MemoryStorageEngine::toKeyValue).toList());
    }

    @Override
    public Promise<List<DHTMessage.KeyValue>> entriesForPartition(ConsistentHashRing<?> ring, Partition partition) {
        return Promise.success(data.entrySet()
                                   .stream()
                                   .filter(e -> inPartition(ring,
                                                            e.getKey(),
                                                            partition))
                                   .map(MemoryStorageEngine::toKeyValue)
                                   .toList());
    }

    private static DHTMessage.KeyValue toKeyValue(Map.Entry<ByteArrayKey, VersionedEntry> e) {
        return toKeyValue(e.getKey(), e.getValue());
    }

    private static DHTMessage.KeyValue toKeyValue(ByteArrayKey key, VersionedEntry entry) {
        return new DHTMessage.KeyValue(key.data(),
                                       entry.value(),
                                       entry.version(),
                                       entry.epochIncarnation(),
                                       entry.epochTerm(),
                                       entry.epochCounter(),
                                       entry.tombstone());
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
