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
/// never the reverse. So this source exposes the epoch only as primitive `long`s, in one [OwnerEpoch]. The
/// aether-level wiring binds the real source to the committed `DhtPartitionOwnershipValue.ownerEpoch`
/// for the node's ownership arc; non-cluster paths and tests use [#zero] (the unfenced floor,
/// `Epoch.ZERO`).
///
/// Supplying the epoch ambiently (rather than as a `put(key, value, epoch)` parameter) keeps the
/// public `DHTClient` write API unchanged: the stamp is a per-node property of the owner, not of the
/// individual call site.
public interface OwnerEpochSource {
    /// This node's current ownership epoch, read ONCE, for stamping one outgoing put (#3720). The three components come from a
    /// single read of the ownership record, so a record applied while a put is being stamped can never yield a mixed epoch that
    /// no owner ever held.
    OwnerEpoch currentEpoch();

    /// One owner epoch as the DHT wire carries it: the cluster `incarnation` (#1529), the `rabiaTerm` and the `localCounter`.
    /// Primitives only: the BSL-1.1 `Epoch` type never enters this module.
    record OwnerEpoch(long incarnation, long rabiaTerm, long localCounter) {
        /// The unfenced floor (`Epoch.ZERO` -> `0:0:0`): what a node stamps while the ownership record is not applied.
        public static final OwnerEpoch FLOOR = new OwnerEpoch(0L, 0L, 0L);

        public static OwnerEpoch ownerEpoch(long incarnation, long rabiaTerm, long localCounter) {
            return new OwnerEpoch(incarnation, rabiaTerm, localCounter);
        }

        /// `true` iff this is the floor: no ownership record was applied when the epoch was read.
        public boolean isFloor() {
            return equals(FLOOR);
        }
    }

    /// The unfenced floor source (`Epoch.ZERO` → `0:0`) for non-cluster DHT paths and tests. A put
    /// stamped at the floor is never rejected by a fresh high-water and never advances it.
    static OwnerEpochSource zero() {
        return ZeroOwnerEpochSource.INSTANCE;
    }
}
