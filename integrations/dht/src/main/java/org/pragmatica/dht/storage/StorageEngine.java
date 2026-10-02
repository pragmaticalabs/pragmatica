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

import org.pragmatica.dht.ConsistentHashRing;
import org.pragmatica.dht.DHTMessage;
import org.pragmatica.dht.Partition;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// Storage engine interface for DHT data storage.
/// Implementations may use in-memory storage, off-heap memory, or persistent storage.
public interface StorageEngine {
    /// Get a value by key.
    ///
    /// @param key the key to look up
    /// @return the value if present, or empty option
    Promise<Option<byte[]>> get(byte[] key);

    /// Store a value.
    ///
    /// @param key   the key
    /// @param value the value to store
    /// @return promise that completes when value is stored
    Promise<Unit> put(byte[] key, byte[] value);

    /// Remove a value.
    ///
    /// @param key the key to remove
    /// @return true if value was present and removed, false if not found
    Promise<Boolean> remove(byte[] key);

    /// Check if a key exists.
    ///
    /// @param key the key to check
    /// @return true if key exists
    Promise<Boolean> exists(byte[] key);

    /// Store a value only if the given version is newer than the current stored version.
    /// Returns true if written (version is newer), false if superseded (stale version).
    ///
    /// Convenience overload at the unfenced epoch floor (`Epoch.ZERO` → `0:0`): used by migration,
    /// anti-entropy and non-cluster paths where there is no owner-epoch fence to apply.
    default Promise<Boolean> putVersioned(byte[] key, byte[] value, long version) {
        return putVersioned(key, value, version, 0L, 0L, 0L);
    }

    /// Store a value subject to the owner-epoch fence then the within-epoch HLC-version LWW
    /// (#345 piece 1c). The presented owner epoch is carried as its two primitive `long`s
    /// (`epochIncarnation`, `epochTerm`, `epochCounter`) so this Apache-2.0 module stays independent of the BSL-1.1
    /// `Epoch` type that mints it.
    ///
    /// Decision order, applied identically on every replica:
    ///
    ///   - first write for the key → accept;
    ///   - presented epoch STRICTLY older than the partition high-water → reject with a
    ///     [org.pragmatica.dht.DHTError.StaleEpochWrite] failure (a deposed owner; spec §8: the
    ///     reject is RETURNED, not silent);
    ///   - same-or-newer epoch → existing HLC-version LWW: a `version` not newer than the stored
    ///     one is superseded, reported as a successful `false` written-flag (behavior unchanged).
    ///
    /// @return `true` if written, `false` if superseded within the epoch, or a failed promise if
    ///         rejected by the epoch fence.
    default Promise<Boolean> putVersioned(byte[] key,
                                          byte[] value,
                                          long version,
                                          long epochIncarnation,
                                          long epochTerm,
                                          long epochCounter) {
        return put(key, value).map(_ -> true);
    }

    /// Store a COPY of an entry another replica already accepted — anti-entropy repair, migration and
    /// the departure push (issue #1818). Identical to [#putVersioned(byte[], byte[], long, long, long, long)]
    /// except that the owner-epoch high-water is NOT consulted: the fence rejects a deposed owner's NEW
    /// write, and a copy is not a new write. Applying it here would refuse every key written before the
    /// latest ownership-epoch advance to any node that does not yet hold it, so such keys could never be
    /// re-replicated and die with their last holder. The per-key ordering still applies, so a copy
    /// never overwrites a stored entry of a newer epoch or a newer version. A copy never advances the
    /// high-water either: its authority is committed ownership state, which reaches every node on its
    /// own, and a copy's epoch is evidence of nothing the node has not been told directly.
    ///
    /// @return `true` if written, `false` if the stored entry is newer, or a failed promise if the
    ///         engine could not store it.
    default Promise<Boolean> putReplica(byte[] key,
                                        byte[] value,
                                        long version,
                                        long epochIncarnation,
                                        long epochTerm,
                                        long epochCounter) {
        return putVersioned(key, value, version, epochIncarnation, epochTerm, epochCounter);
    }

    /// Remove `key` only while its stored entry is exactly the given version and owner epoch — a writer rolling
    /// back its OWN accept after the put lost its quorum to owner-epoch fences (#1818, the owner's fence
    /// ruling). An entry since superseded, or never stored, is left alone. An engine without the capability
    /// removes nothing, which leaves the accept in place (the residual #1777 track 3 closes).
    ///
    /// @return `true` if the exact entry was removed.
    default Promise<Boolean> removeIfExactly(byte[] key,
                                             long version,
                                             long epochIncarnation,
                                             long epochTerm,
                                             long epochCounter) {
        return Promise.success(false);
    }

    /// Whether an entry stamped with this owner epoch is older than this store's high-water — a copy applied
    /// with it is one the fence would have refused as a fresh write (#1818, visibility). An engine without a
    /// fence has no high-water: `false`.
    default boolean belowHighWater(byte[] key, long epochIncarnation, long epochTerm, long epochCounter) {
        return false;
    }

    /// Get approximate number of entries.
    long size();
    /// Clear all entries.
    Promise<Unit> clear();
    /// Shutdown the storage engine and release resources.
    Promise<Unit> shutdown();
    /// Get all keys in storage.
    Promise<List<byte[]>> keys();
    /// Get all entries as key-value pairs.
    Promise<List<DHTMessage.KeyValue>> entries();

    /// Get entries belonging to a specific partition.
    Promise<List<DHTMessage.KeyValue>> entriesForPartition(ConsistentHashRing<?> ring, Partition partition);
}
