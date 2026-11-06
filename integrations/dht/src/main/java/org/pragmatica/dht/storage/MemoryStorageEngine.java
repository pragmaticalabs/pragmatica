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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.dht.ConsistentHashRing;
import org.pragmatica.dht.DHTError;
import org.pragmatica.dht.DHTMessage;
import org.pragmatica.dht.Partition;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Contract;
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
    /// so it supersedes every older copy and loses to every newer write. `valueCrc` is the value's CRC32, computed
    /// once when the entry is created, so a repair digest reads stamps and never values (A6).
    private record VersionedEntry(byte[] value,
                                  long version,
                                  long epochIncarnation,
                                  long epochTerm,
                                  long epochCounter,
                                  boolean tombstone,
                                  long valueCrc) {
        boolean live() {
            return ! tombstone;
        }
    }

    private static VersionedEntry versionedEntry(byte[] value,
                                                 long version,
                                                 long epochIncarnation,
                                                 long epochTerm,
                                                 long epochCounter,
                                                 boolean tombstone) {
        return new VersionedEntry(value,
                                  version,
                                  epochIncarnation,
                                  epochTerm,
                                  epochCounter,
                                  tombstone,
                                  KeyStamp.crcOf(value));
    }

    private final ConcurrentHashMap<ByteArrayKey, VersionedEntry> data = new ConcurrentHashMap<>();
    private final OwnerEpochGate epochGate;
    private final DhtPrefixAccounting accounting;

    private MemoryStorageEngine(OwnerEpochGate epochGate, DhtPrefixAccounting accounting) {
        this.epochGate = epochGate;
        this.accounting = accounting;
    }

    /// Fence-free engine ([OwnerEpochGate#noOp]) for non-cluster DHT paths and tests.
    public static MemoryStorageEngine memoryStorageEngine() {
        return new MemoryStorageEngine(OwnerEpochGate.noOp(), DhtPrefixAccounting.dhtPrefixAccounting());
    }

    /// Engine whose versioned writes are owner-epoch-fenced by `epochGate` (#345 piece 1c). The
    /// aether-level wiring supplies a high-water-backed gate; every replica enforces monotonicity
    /// at its own commit point.
    public static MemoryStorageEngine memoryStorageEngine(OwnerEpochGate epochGate) {
        return new MemoryStorageEngine(epochGate, DhtPrefixAccounting.dhtPrefixAccounting());
    }

    /// An engine that accounts its bytes per prefix of `accounting` and refuses client puts above its cap (#2258).
    public static MemoryStorageEngine memoryStorageEngine(OwnerEpochGate epochGate, DhtPrefixAccounting accounting) {
        return new MemoryStorageEngine(epochGate, accounting);
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
        var bkey = new ByteArrayKey(key);
        var incoming = versionedEntry(value.clone(), 0L, 0L, 0L, 0L, false);
        var refused = new AtomicLong();

        data.compute(bkey,
                     (_, existing) -> accepted(bkey, existing, incoming, true, refused)
                                      ? incoming
                                      : existing);

        return concluded(refused,
                         Promise.success(Unit.unit()));
    }

    @Override
    public Promise<Boolean> putVersioned(byte[] key,
                                         byte[] value,
                                         long version,
                                         long epochIncarnation,
                                         long epochTerm,
                                         long epochCounter) {
        return putVersioned(key, value, version, epochIncarnation, epochTerm, epochCounter, false);
    }

    @Override
    public Promise<Boolean> putVersioned(byte[] key,
                                         byte[] value,
                                         long version,
                                         long epochIncarnation,
                                         long epochTerm,
                                         long epochCounter,
                                         boolean repair) {
        if (epochGate.isStale(key, epochIncarnation, epochTerm, epochCounter)) {
            return DHTError.staleEpochWrite(epochIncarnation, epochTerm, epochCounter).promise();
        }

        var refused = new AtomicLong();
        var written = storeVersioned(key,
                                     versionedEntry(value.clone(),
                                                    version,
                                                    epochIncarnation,
                                                    epochTerm,
                                                    epochCounter,
                                                    false),
                                     true,
                                     true,
                                     !repair,
                                     refused,
                                     new AtomicBoolean(),
                                     new AtomicReference<>());

        return concluded(refused, Promise.success(written));
    }

    @Override
    public Promise<Displaced> putVersionedDisplacing(byte[] key,
                                                     byte[] value,
                                                     long version,
                                                     long epochIncarnation,
                                                     long epochTerm,
                                                     long epochCounter) {
        return putVersionedDisplacing(key, value, version, epochIncarnation, epochTerm, epochCounter, false);
    }

    @Override
    public Promise<Displaced> putVersionedDisplacing(byte[] key,
                                                     byte[] value,
                                                     long version,
                                                     long epochIncarnation,
                                                     long epochTerm,
                                                     long epochCounter,
                                                     boolean repair) {
        return writeDisplacing(key,
                               versionedEntry(value.clone(), version, epochIncarnation, epochTerm, epochCounter, false),
                               !repair);
    }

    @Override
    public Promise<Displaced> removeVersionedDisplacing(byte[] key,
                                                        long version,
                                                        long epochIncarnation,
                                                        long epochTerm,
                                                        long epochCounter) {
        return writeDisplacing(key,
                               versionedEntry(NO_VALUE, version, epochIncarnation, epochTerm, epochCounter, true),
                               false);
    }

    private Promise<Displaced> writeDisplacing(byte[] key, VersionedEntry incoming, boolean client) {
        if (epochGate.isStale(key, incoming.epochIncarnation(), incoming.epochTerm(), incoming.epochCounter())) {
            return DHTError.staleEpochWrite(incoming.epochIncarnation(),
                                            incoming.epochTerm(),
                                            incoming.epochCounter())
                           .promise();
        }

        var displaced = new AtomicReference<VersionedEntry>();
        var refused = new AtomicLong();
        var written = storeVersioned(key, incoming, true, true, client, refused, new AtomicBoolean(), displaced);

        return concluded(refused,
                         Promise.success(new Displaced(written,
                                                       written
                                                       ? Option.option(displaced.get()).map(entry -> toKeyValue(new ByteArrayKey(key),
                                                                                                                entry))
                                                       : Option.none())));
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
                                     versionedEntry(NO_VALUE, version, epochIncarnation, epochTerm, epochCounter, true),
                                     true,
                                     true,
                                     false,
                                     new AtomicLong(),
                                     supersededLive,
                                     new AtomicReference<>());

        return settled(written && supersededLive.get());
    }

    @Override
    public Promise<Boolean> putReplica(byte[] key,
                                       byte[] value,
                                       long version,
                                       long epochIncarnation,
                                       long epochTerm,
                                       long epochCounter) {
        return settled(storeVersioned(key,
                                      versionedEntry(value.clone(),
                                                     version,
                                                     epochIncarnation,
                                                     epochTerm,
                                                     epochCounter,
                                                     false),
                                      false,
                                      true,
                                      false,
                                      new AtomicLong(),
                                      new AtomicBoolean(),
                                      new AtomicReference<>()));
    }

    @Override
    public Promise<Boolean> putReplica(DHTMessage.KeyValue entry, boolean createIfAbsent) {
        return settled(storeVersioned(entry.key(),
                                      versionedEntry(entry.tombstone()
                                                     ? NO_VALUE
                                                     : entry.value().clone(),
                                                     entry.version(),
                                                     entry.epochIncarnation(),
                                                     entry.epochTerm(),
                                                     entry.epochCounter(),
                                                     entry.tombstone()),
                                      false,
                                      createIfAbsent,
                                      false,
                                      new AtomicLong(),
                                      new AtomicBoolean(),
                                      new AtomicReference<>()));
    }

    @Override
    public Promise<Integer> dropPartition(ConsistentHashRing<?> ring, Partition partition) {
        var dropped = new AtomicInteger();

        data.keySet()
            .stream()
            .filter(key -> inPartition(ring, key, partition))
            .toList()
            .forEach(key -> data.computeIfPresent(key,
                                                  (_, existing) -> dropped(key, existing, dropped)));

        return settled(dropped.get());
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

        return settled(collected.get());
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
        data.computeIfPresent(key,
                              (_, existing) -> existing.equals(tombstone)
                                               ? dropped(key, existing, collected)
                                               : existing);
    }

    /// The map step that removes `existing`: its bytes leave the counters in the same step.
    @NullReturn
    private VersionedEntry dropped(ByteArrayKey key, VersionedEntry existing, AtomicInteger count) {
        count.incrementAndGet();

        return changed(key, existing, null);
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
        var bkey = new ByteArrayKey(key);

        data.computeIfPresent(bkey,
                              (_, existing) -> changed(bkey,
                                                       existing,
                                                       keepUnlessExactly(existing,
                                                                         version,
                                                                         epochIncarnation,
                                                                         epochTerm,
                                                                         epochCounter,
                                                                         removed)));

        return settled(removed.get());
    }

    @Override
    public Promise<Boolean> restoreIfExactly(byte[] key,
                                             long version,
                                             long epochIncarnation,
                                             long epochTerm,
                                             long epochCounter,
                                             Option<DHTMessage.KeyValue> prior) {
        var restored = new AtomicBoolean(false);
        var bkey = new ByteArrayKey(key);

        data.computeIfPresent(bkey,
                              (_, existing) -> changed(bkey,
                                                       existing,
                                                       restoreUnlessSuperseded(existing,
                                                                               version,
                                                                               epochIncarnation,
                                                                               epochTerm,
                                                                               epochCounter,
                                                                               prior,
                                                                               restored)));

        return settled(restored.get());
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
                          entry -> versionedEntry(entry.tombstone()
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
    /// `client` marks a client write, which the cap of the bounded prefix may refuse: the entry then stays as it was, nothing
    /// is written (`false` is answered) and the refused bytes are left in `refused`. The counters move in the same
    /// per-key step that changes the entry.
    private boolean storeVersioned(byte[] key,
                                   VersionedEntry incoming,
                                   boolean advanceHighWater,
                                   boolean createIfAbsent,
                                   boolean client,
                                   AtomicLong refused,
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

                         return keptOrAccepted(bkey, existing, next, client, refused, written);
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
    /// The step's decision `next`, kept only if its bytes were admitted: a client write over the cap leaves `existing`.
    @NullReturn
    private VersionedEntry keptOrAccepted(ByteArrayKey key,
                                          VersionedEntry existing,
                                          VersionedEntry next,
                                          boolean client,
                                          AtomicLong refused,
                                          AtomicBoolean written) {
        if (next == existing || accepted(key, existing, next, client, refused)) {
            return next;
        }

        written.set(false);

        return existing;
    }

    /// Moves the counters from `before` to `after`; a client write the cap refuses moves nothing and leaves its size.
    private boolean accepted(ByteArrayKey key,
                             VersionedEntry before,
                             VersionedEntry after,
                             boolean client,
                             AtomicLong refused) {
        var liveDelta = liveOf(Option.option(after)) - liveOf(Option.option(before));
        var admitted = accounting.apply(key.data(),
                                        liveDelta,
                                        tombstoneOf(key, Option.option(after)) - tombstoneOf(key, Option.option(before)),
                                        client);

        if (!admitted) {
            refused.set(liveDelta);
        }

        return admitted;
    }

    /// The map step that replaces `before` with `after` where no cap applies: the counters follow the entry.
    @NullReturn
    private VersionedEntry changed(ByteArrayKey key, VersionedEntry before, VersionedEntry after) {
        if (after != before) {
            accounting.apply(key.data(),
                             liveOf(Option.option(after)) - liveOf(Option.option(before)),
                             tombstoneOf(key, Option.option(after)) - tombstoneOf(key, Option.option(before)),
                             false);
        }

        return after;
    }

    private static long liveOf(Option<VersionedEntry> entry) {
        return entry.filter(VersionedEntry::live)
                    .map(live -> (long) live.value().length)
                    .or(0L);
    }

    private static long tombstoneOf(ByteArrayKey key, Option<VersionedEntry> entry) {
        return entry.filter(VersionedEntry::tombstone)
                    .map(_ -> (long) key.data().length + DhtPrefixAccounting.TOMBSTONE_OVERHEAD_BYTES)
                    .or(0L);
    }

    /// A write is over: the transitions its change made are reported and, if the cap refused it, so is the refusal.
    private <T> Promise<T> concluded(AtomicLong refused, Promise<T> accepted) {
        var refusedBytes = refused.get();

        if (refusedBytes > 0) {
            accounting.refused(refusedBytes);
        }
        // a refusal moved no counter: only a write that did can end an episode, so a refusal never closes the one it opens
        if (refusedBytes <= 0) {
            accounting.settle();
        }

        return refusedBytes > 0
               ? DHTError.replicaFull(usedBytes(DhtPrefixAccounting.STREAM_SEGMENTS),
                                      maxBytes(DhtPrefixAccounting.STREAM_SEGMENTS)).promise()
               : accepted;
    }

    private <T> Promise<T> settled(T value) {
        accounting.settle();

        return Promise.success(value);
    }

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
        var bkey = new ByteArrayKey(key);
        var removed = new AtomicBoolean();

        data.computeIfPresent(bkey,
                              (_, existing) -> {
                                  removed.set(true);

                                  return changed(bkey, existing, null);
                              });

        return settled(removed.get());
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
        return emptied();
    }

    @Override
    public Promise<Unit> shutdown() {
        return emptied();
    }

    /// Every entry removed in a per-key step of its own, so the counters leave with the entries.
    private Promise<Unit> emptied() {
        data.keySet().forEach(key -> data.computeIfPresent(key, (_, existing) -> changed(key, existing, null)));

        return settled(Unit.unit());
    }

    @Override
    public long usedBytes(String prefix) {
        return accounting.usedBytes(prefix);
    }

    @Override
    public long tombstoneBytes(String prefix) {
        return accounting.tombstoneBytes(prefix);
    }

    @Override
    public long maxBytes(String prefix) {
        return accounting.maxBytes(prefix);
    }

    @Override
    public List<String> accountedPrefixes() {
        return accounting.prefixes();
    }

    @Contract
    @Override
    public void capacityListener(DhtCapacityListener listener) {
        accounting.listener(listener);
    }

    @Override
    public long refusedPuts() {
        return accounting.refusedPuts();
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

    /// Stamps from the index beside the values: no value is read or copied (A6).
    @Override
    public Promise<List<KeyStamp>> stampsForPartition(ConsistentHashRing<?> ring, Partition partition) {
        return Promise.success(data.entrySet()
                                   .stream()
                                   .filter(e -> inPartition(ring,
                                                            e.getKey(),
                                                            partition))
                                   .map(MemoryStorageEngine::toKeyStamp)
                                   .toList());
    }

    /// Stamps from the index beside the values: no value is read or copied (A6).
    @Override
    public Promise<List<KeyStamp>> stamps() {
        return Promise.success(data.entrySet().stream().map(MemoryStorageEngine::toKeyStamp).toList());
    }

    private static KeyStamp toKeyStamp(Map.Entry<ByteArrayKey, VersionedEntry> e) {
        var entry = e.getValue();

        return new KeyStamp(e.getKey().data(),
                            entry.version(),
                            entry.epochIncarnation(),
                            entry.epochTerm(),
                            entry.epochCounter(),
                            entry.tombstone(),
                            entry.valueCrc(),
                            entry.value().length);
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
