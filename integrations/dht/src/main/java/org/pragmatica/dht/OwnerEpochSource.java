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

package org.pragmatica.dht;

/// Ambient source of the writer's CURRENT owner epoch, used to stamp each DHT put with a fencing
/// token (#345 piece 1c). The owner stamps every data-plane write with its believed-current
/// ownership epoch; the replicas it sends to enforce monotonicity against their per-partition
/// high-water (`OwnerEpochGate`), so a deposed owner is rejected everywhere.
///
/// The epoch is minted CP-side as `org.pragmatica.aether.slice.generation.Epoch(rabiaTerm,
/// localCounter)` in the BSL-1.1 `aether/slice` module, which depends on this Apache-2.0 module —
/// never the reverse. So this source exposes the epoch only as its two primitive `long`s. The
/// aether-level wiring binds the real source to the committed `DhtPartitionOwnershipValue.ownerEpoch`
/// for the node's ownership arc; non-cluster paths and tests use [#zero] (the unfenced floor,
/// `Epoch.ZERO`).
///
/// Supplying the epoch ambiently (rather than as a `put(key, value, epoch)` parameter) keeps the
/// public `DHTClient` write API unchanged: the stamp is a per-node property of the owner, not of the
/// individual call site.
public interface OwnerEpochSource {
    /// This node's current ownership-epoch `rabiaTerm` for stamping outgoing puts.
    long currentEpochTerm();

    /// This node's current ownership-epoch `localCounter` for stamping outgoing puts.
    long currentEpochCounter();

    /// The unfenced floor source (`Epoch.ZERO` → `0:0`) for non-cluster DHT paths and tests. A put
    /// stamped at the floor is never rejected by a fresh high-water and never advances it.
    static OwnerEpochSource zero() {
        return ZeroOwnerEpochSource.INSTANCE;
    }
}
