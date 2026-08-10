// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.dht;

import java.nio.charset.StandardCharsets;

import org.pragmatica.aether.slice.fence.OwnershipDomain;
import org.pragmatica.aether.slice.fence.OwnershipDomain.StreamPartition;
import org.pragmatica.lang.Option;


/// Deterministic mapping between a durable-entity key and its per-`(keyspace, partition)` ownership
/// arc — the shared seam that makes the per-partition entity fence (#345 plan Phase 2b-ii) agree on
/// the SAME arc at both the stamp site (the entity's `putVersioned`) and the check site (the
/// [PartitionOwnerEpochGate] inside the storage engine).
///
/// ## Why one shared helper
/// The coarse 2b-i fence stamped every entity write at the single `"core"` ownership arc, so a
/// deposed PARTITION owner was missed on a same-generation reshuffle (no governor change). The
/// per-partition fence keys the high-water by the key's own `(keyspace, partition)` arc instead.
/// For the stamp and the check to fence the SAME arc with no re-derivation drift, the partition is
/// computed ONCE and embedded in the DHT key bytes; the gate parses it straight back out. Both sides
/// therefore route to the IDENTICAL [OwnershipDomain.StreamPartition] without re-hashing.
///
/// ## Key format
/// `entity:<keyspace> + "/" + partition + "/" + key` (UTF-8). The leading `entity:<keyspace>/partition/`
/// prefix is the arc coordinate; everything after the second `/` is the application key (which may
/// itself contain `/`). [#dhtKey] builds it; [#arcOf] parses the prefix back to the arc.
///
/// ## Why the arc name is namespaced (#345 I1)
/// Entity arcs reuse the stream-partition ownership record family
/// ([org.pragmatica.aether.slice.kvstore.AetherKey.StreamPartitionOwnershipKey]), which keys on a BARE
/// name — so an entity keyspace `orders` and a stream `orders` would share ownership records and
/// high-water arcs. That collision is not merely untidy: the entity's partition count comes from its own
/// `DurableEntityConfig` while the stream's comes from its `StreamConfig`, so the write fence and the
/// ownership arcs would silently key DIFFERENT partitions and still look like a working feature. The
/// [#ARC_PREFIX] makes the two namespaces disjoint by construction. It is applied HERE, once, rather
/// than at each call site, so the stamp site, the check site
/// ([PartitionOwnerEpochGate], which parses the arc back out of the key bytes) and the ownership writer
/// cannot drift apart. When #349 / plan Phase 3 turns an entity keyspace into a real stream, that stream
/// adopts the SAME prefix — making the change a rename of nothing rather than a record migration.
///
/// ## Partitioning
/// The partition is `floorMod(stableHash64(arcName + "/" + key), partitionCount)` — FNV-1a 64-bit
/// over UTF-8, the SAME stable-hash family the stream replica placement uses
/// (`ReplicaPlacement.stableHash64`). It is reimplemented inline here rather than depending on the
/// BSL-1.1 `aether-stream` module (which `aether-dht` does not and should not depend on); FNV-1a is
/// fully deterministic across JVMs with no external library — the same precedent `ReplicaPlacement`
/// itself follows. Identity hashes are deliberately NOT used (not stable across JVMs).
public final class EntityPartitionArc {
    /// Namespace prefix separating entity ownership arcs from stream ones inside the shared
    /// `StreamPartitionOwnershipKey` family. See the type comment for why this is mandatory.
    public static final String ARC_PREFIX = "entity:";
    private static final long FNV_64_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_64_PRIME = 0x100000001b3L;
    private static final String SEP = "/";

    private final String arcName;
    private final int partitionCount;

    private EntityPartitionArc(String arcName, int partitionCount) {
        this.arcName = arcName;
        this.partitionCount = partitionCount;
    }

    /// Arc mapper for the entity family `keyspace` spread across `partitionCount` partitions. `keyspace`
    /// is the RAW name the author wrote in `resources.toml`; the [#ARC_PREFIX] is applied here.
    public static EntityPartitionArc entityPartitionArc(String keyspace, int partitionCount) {
        return new EntityPartitionArc(arcName(keyspace), partitionCount);
    }

    /// The namespaced ownership-arc name for a raw entity `keyspace` — what the ownership writer must
    /// register, and what [#arcOf] reports. Exposed so the leader-side driver names the IDENTICAL arc
    /// this mapper fences against, without duplicating the prefix.
    public static String arcName(String keyspace) {
        return ARC_PREFIX + keyspace;
    }

    /// This mapper's namespaced ownership-arc name.
    public String arcName() {
        return arcName;
    }

    /// The number of ownership arcs this keyspace spreads over.
    public int partitionCount() {
        return partitionCount;
    }

    /// The partition index for `key` — `floorMod(stableHash64(arcName/key), partitionCount)`.
    public int partitionOf(String key) {
        return Math.floorMod(stableHash64(arcName + SEP + key), partitionCount);
    }

    /// The DHT key bytes `entity:<keyspace>/partition/key`, with the arc coordinate as a parseable prefix.
    public byte[] dhtKey(String key) {
        return (arcName + SEP + partitionOf(key) + SEP + key).getBytes(StandardCharsets.UTF_8);
    }

    /// The `(entity:<keyspace>, partition)` ownership arc this entity `key` belongs to.
    public StreamPartition arcOf(String key) {
        return OwnershipDomain.streamPartition(arcName, partitionOf(key));
    }

    /// Parse the `(entity:<keyspace>, partition)` ownership arc embedded in DHT key `bytes` (the inverse
    /// of [#dhtKey]). [Option#none] when the bytes do not carry the `entity:<keyspace>/partition/...`
    /// prefix this mapper produces — the storage engine then treats the write as unfenced (the floor arc),
    /// exactly as a non-entity DHT key would be. Requiring [#ARC_PREFIX] is what keeps that decision
    /// precise: without it any key that merely happened to contain `name/digits/` would be fenced against
    /// an arc nothing owns.
    public static Option<StreamPartition> arcOf(byte[] bytes) {
        return parseArc(new String(bytes, StandardCharsets.UTF_8));
    }

    private static Option<StreamPartition> parseArc(String dhtKey) {
        if (!dhtKey.startsWith(ARC_PREFIX)) {
            return Option.none();
        }

        var firstSep = dhtKey.indexOf(SEP);

        if (firstSep <= 0) {
            return Option.none();
        }

        var secondSep = dhtKey.indexOf(SEP, firstSep + 1);

        if (secondSep <= firstSep + 1) {
            return Option.none();
        }

        return parsePartition(dhtKey.substring(firstSep + 1, secondSep)).map(partition -> OwnershipDomain.streamPartition(dhtKey.substring(0,
                                                                                                                                           firstSep),
                                                                                                                          partition));
    }

    private static Option<Integer> parsePartition(String text) {
        for (var index = 0; index < text.length(); index++) {
            if (!Character.isDigit(text.charAt(index))) {
                return Option.none();
            }
        }

        return text.isEmpty()
               ? Option.none()
               : Option.some(Integer.parseInt(text));
    }

    /// FNV-1a 64-bit over the UTF-8 bytes of `value`. Deterministic across JVMs/nodes — matches the
    /// `ReplicaPlacement.stableHash64` precedent so entity and stream placement share one hash family.
    static long stableHash64(String value) {
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        var hash = FNV_64_OFFSET_BASIS;

        for (var b : bytes) {
            hash ^= (b & 0xff);
            hash *= FNV_64_PRIME;
        }

        return hash;
    }
}
