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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.lang.Contract;

/// The per-prefix byte accounting of a DHT engine (#2258, spec 3.2 item 7): two counters for each prefix of a registered
/// table, live value bytes and tombstone bytes, the cap of the bounded prefix, and the transitions of that prefix reported to
/// a [DhtCapacityListener].
///
/// The prefixes are a registered table, not parsed free-form: [#STREAM_SEGMENTS], [#ARTIFACTS], every `<name>-blocks` of a
/// configured instance (added by [#register]), and [#OTHER] for every key that matches none (the cache's `namespace:key`,
/// idempotency and slice keys, binary keys), so the counters and the gauge labels stay bounded by the table. A key belongs to
/// a registered prefix when its bytes start with the prefix followed by `/`.
///
/// A tombstone counts its key length plus [#TOMBSTONE_OVERHEAD_BYTES] `[guess]`, the heap it holds for the tombstone
/// retention. Tombstones are never capped, but they count toward the near-full warning, which is a heap warning.
public final class DhtPrefixAccounting {
    /// The prefix of the streams instance's blocks: the only bounded one.
    public static final String STREAM_SEGMENTS = "stream-segments";
    /// The prefix of the artifact repository's blocks.
    public static final String ARTIFACTS = "artifacts";
    /// The bucket of every key that matches no registered prefix.
    public static final String OTHER = "other";
    /// A prefix with no cap.
    public static final long UNBOUNDED = Long.MAX_VALUE;
    /// What a tombstone holds beyond its key `[guess]`.
    public static final int TOMBSTONE_OVERHEAD_BYTES = 64;
    /// The share of the cap (live plus tombstone bytes) at which near-full is reported: the local-disk warning ratio.
    static final int NEAR_FULL_PERCENT = 85;
    /// The share of the cap below which near-full is cleared `[guess: the hysteresis]`.
    static final int NEAR_FULL_CLEARED_PERCENT = 80;
    /// The share of the cap, in live bytes, below which a refusal episode ends.
    static final int FULL_CLEARED_PERCENT = 85;

    private record Bucket(String name, byte[] marker, AtomicLong live, AtomicLong tombstone) {
        static Bucket bucket(String name) {
            return new Bucket(name, (name + "/").getBytes(StandardCharsets.UTF_8), new AtomicLong(), new AtomicLong());
        }

        boolean holds(byte[] key) {
            if (key.length < marker.length) {
                return false;
            }

            for (var i = 0; i < marker.length; i++) {
                if (key[i] != marker[i]) {
                    return false;
                }
            }

            return true;
        }
    }

    private final long streamSegmentsMaxBytes;
    private final List<Bucket> registered = new CopyOnWriteArrayList<>();
    private final Bucket other = Bucket.bucket(OTHER);
    private final AtomicBoolean nearFull = new AtomicBoolean();
    private final AtomicBoolean full = new AtomicBoolean();
    private final AtomicLong refusedPuts = new AtomicLong();
    private final AtomicReference<DhtCapacityListener> listener = new AtomicReference<>(DhtCapacityListener.NONE);

    private DhtPrefixAccounting(long streamSegmentsMaxBytes) {
        this.streamSegmentsMaxBytes = streamSegmentsMaxBytes;
        registered.add(Bucket.bucket(STREAM_SEGMENTS));
        registered.add(Bucket.bucket(ARTIFACTS));
    }

    /// Accounting with no cap on any prefix.
    public static DhtPrefixAccounting dhtPrefixAccounting() {
        return new DhtPrefixAccounting(UNBOUNDED);
    }

    /// Accounting that caps the live bytes of [#STREAM_SEGMENTS] at `streamSegmentsMaxBytes`.
    public static DhtPrefixAccounting dhtPrefixAccounting(long streamSegmentsMaxBytes) {
        return new DhtPrefixAccounting(streamSegmentsMaxBytes);
    }

    /// Adds `prefix` to the registered table; a prefix already registered stays as it is.
    @Contract
    public void register(String prefix) {
        if (registered.stream().noneMatch(bucket -> bucket.name().equals(prefix))) {
            registered.add(Bucket.bucket(prefix));
        }
    }

    /// Where the transitions of the bounded prefix go from now on.
    @Contract
    public void listener(DhtCapacityListener listener) {
        this.listener.set(listener);
    }

    /// The registered prefixes and [#OTHER]: the labels of the gauges, bounded by the table.
    public List<String> prefixes() {
        var all = new ArrayList<String>();

        registered.forEach(bucket -> all.add(bucket.name()));
        all.add(OTHER);

        return List.copyOf(all);
    }

    /// Live value bytes under `prefix`; 0 for a prefix that is neither registered nor [#OTHER].
    public long usedBytes(String prefix) {
        return find(prefix).map(bucket -> bucket.live().get()).orElse(0L);
    }

    /// Tombstone bytes under `prefix`; 0 for a prefix that is neither registered nor [#OTHER].
    public long tombstoneBytes(String prefix) {
        return find(prefix).map(bucket -> bucket.tombstone().get()).orElse(0L);
    }

    /// The cap on the live bytes of `prefix`; [#UNBOUNDED] for every prefix but [#STREAM_SEGMENTS].
    public long maxBytes(String prefix) {
        return STREAM_SEGMENTS.equals(prefix)
               ? streamSegmentsMaxBytes
               : UNBOUNDED;
    }

    /// Client puts refused for capacity since start.
    public long refusedPuts() {
        return refusedPuts.get();
    }

    private java.util.Optional<Bucket> find(String prefix) {
        return OTHER.equals(prefix)
               ? java.util.Optional.of(other)
               : registered.stream().filter(bucket -> bucket.name().equals(prefix)).findFirst();
    }

    private Bucket bucketOf(byte[] key) {
        for (var bucket : registered) {
            if (bucket.holds(key)) {
                return bucket;
            }
        }

        return other;
    }

    /// Applies one entry's change to the counters of its bucket: `liveDelta` and `tombstoneDelta` are the new entry's bytes
    /// minus the old one's. A client write (`enforceCap`) that would take the live bytes of the capped prefix above its cap
    /// is not applied and answers `false`: the check and the update are one compare-and-set on the counter, so concurrent
    /// puts of different keys overshoot by at most the bytes in flight. Everything else is applied.
    boolean apply(byte[] key, long liveDelta, long tombstoneDelta, boolean enforceCap) {
        var bucket = bucketOf(key);

        if (!enforceCap || liveDelta <= 0 || !STREAM_SEGMENTS.equals(bucket.name()) || streamSegmentsMaxBytes == UNBOUNDED) {
            bucket.live().addAndGet(liveDelta);
            bucket.tombstone().addAndGet(tombstoneDelta);

            return true;
        }

        if (!reserve(bucket.live(), liveDelta)) {
            return false;
        }

        bucket.tombstone().addAndGet(tombstoneDelta);

        return true;
    }

    private boolean reserve(AtomicLong live, long delta) {
        while (true) {
            var used = live.get();

            if (delta > streamSegmentsMaxBytes - used) {
                return false;
            }

            if (live.compareAndSet(used, used + delta)) {
                return true;
            }
        }
    }

    /// A client put of `refusedBytes` more live bytes was refused for the cap: the first of an episode is reported, the rest
    /// only counted.
    @Contract
    void refused(long refusedBytes) {
        refusedPuts.incrementAndGet();
        if (full.compareAndSet(false, true)) {
            listener.get().full(STREAM_SEGMENTS, usedBytes(STREAM_SEGMENTS), streamSegmentsMaxBytes, refusedBytes);
        }
    }

    /// Reports the transitions the counters have made since the last call: near-full when live plus tombstone bytes reach
    /// [#NEAR_FULL_PERCENT] of the cap, its end below [#NEAR_FULL_CLEARED_PERCENT], and the end of a refusal episode when the
    /// live bytes fall below [#FULL_CLEARED_PERCENT]. Called after the change, outside the map's per-key step.
    @Contract
    void settle() {
        if (streamSegmentsMaxBytes == UNBOUNDED) {
            return;
        }

        var bucket = find(STREAM_SEGMENTS).orElseThrow();
        var live = bucket.live().get();
        var tombstones = bucket.tombstone().get();
        var total = live + tombstones;

        if (total >= percentOf(streamSegmentsMaxBytes, NEAR_FULL_PERCENT) && nearFull.compareAndSet(false, true)) {
            listener.get().nearFull(STREAM_SEGMENTS, live, tombstones, streamSegmentsMaxBytes);
        } else if (total < percentOf(streamSegmentsMaxBytes, NEAR_FULL_CLEARED_PERCENT) && nearFull.compareAndSet(true, false)) {
            listener.get().nearFullCleared(STREAM_SEGMENTS, live, tombstones, streamSegmentsMaxBytes);
        }

        if (live < percentOf(streamSegmentsMaxBytes, FULL_CLEARED_PERCENT) && full.compareAndSet(true, false)) {
            listener.get().fullCleared(STREAM_SEGMENTS, live, streamSegmentsMaxBytes);
        }
    }

    /// The smallest byte count that is at least `percent` of `cap`, without overflowing for a cap near [Long#MAX_VALUE].
    private static long percentOf(long cap, int percent) {
        return cap / 100 * percent + Math.ceilDiv(cap % 100 * percent, 100L);
    }
}
