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

/// Dependency-inverted owner-epoch fence for the DHT data-plane write path (#345 piece 1c).
///
/// The fencing token is the owner's monotonic ownership epoch — minted CP-side as
/// `org.pragmatica.aether.slice.generation.Epoch(rabiaTerm, localCounter)`. That `Epoch` type lives
/// in the BSL-1.1 `aether/slice` module, which depends on this Apache-2.0 module — NEVER the reverse.
/// So this SPI carries the epoch only as its two primitive `long`s, and the real high-water-backed
/// implementation is injected from the aether-level wiring (the module that depends on both DHT and
/// `aether/slice`). The default [#noOp] keeps the engine fence-free for non-cluster paths and tests.
///
/// **Comparison semantics (must match `Epoch.compareTo`).** An epoch is stale iff it is STRICTLY
/// older than the partition high-water — compare `epochTerm` first, then `epochCounter`. Equal is NOT
/// stale (a genuinely-current owner re-writes at its own epoch); newer is NOT stale (and advances the
/// high-water). The implementation owns this comparison so the DHT engine needs no epoch type.
///
/// **Keying.** Today every DHT key handled by an aether node belongs to the single `"core"` ownership
/// arc (`DhtPartitionOwnershipKey("core")`), so the engine passes the raw key through and the
/// implementation binds the arc it fences. The key is supplied — rather than nothing — so a future
/// multi-arc implementation can map key → ownership arc without an engine signature change. The ring's
/// internal 1024-partition id is a routing concept and is deliberately NOT used here: it does not key
/// the ownership high-water.
///
/// **Determinism.** [#isStale] reads only the presented epoch and the local (CP-seeded, monotonic)
/// high-water — no wall-clock, no randomness — so every replica accepts or rejects identically.
public interface OwnerEpochGate {
    /// `true` iff the presented owner epoch is STRICTLY older than the high-water for the ownership
    /// arc the `key` belongs to — a deposed owner's write that must be rejected with no mutation.
    boolean isStale(byte[] key, long epochTerm, long epochCounter);

    /// Advance the ownership arc's high-water on an accepted write. Monotonic: older is ignored,
    /// equal is a no-op, newer advances.
    void advance(byte[] key, long epochTerm, long epochCounter);

    /// `true` when this gate enforces per-key epoch ordering inside the storage engine's compute
    /// step (spec §3.2). The no-op gate returns `false` — epoch ordering is bypassed entirely so
    /// writes fall through to pure HLC-version LWW, which is correct for non-cluster paths.
    default boolean epochOrderingEnabled() {
        return true;
    }

    /// Fence-free gate: nothing is ever stale and nothing is recorded. Used by the no-arg engine
    /// factory (non-cluster/worker DHT and unit tests) where there is no ownership plane to fence
    /// against.
    static OwnerEpochGate noOp() {
        return NoOpOwnerEpochGate.INSTANCE;
    }
}
