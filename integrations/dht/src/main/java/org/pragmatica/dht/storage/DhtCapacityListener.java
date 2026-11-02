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

import org.pragmatica.lang.Contract;


/// Where a node's DHT engine reports the transitions of its bounded prefix (#2258, spec 3.2 items 7 and 8). The engine reports
/// each transition once, when it happens, never per put: an episode of near-full or of refusal is one `nearFull` or `full` and
/// the call that closes it. The listener is the only seam between the engine and the operator-warning types, which this module
/// does not depend on.
public interface DhtCapacityListener {
    /// Live plus tombstone bytes of `prefix` first reached the warning ratio of its cap.
    @Contract
    void nearFull(String prefix, long liveBytes, long tombstoneBytes, long maxBytes);

    /// Live plus tombstone bytes of `prefix` fell below the clearing ratio of its cap.
    @Contract
    void nearFullCleared(String prefix, long liveBytes, long tombstoneBytes, long maxBytes);

    /// The first client put of an episode was refused for capacity: `refusedBytes` is its size.
    @Contract
    void full(String prefix, long usedBytes, long maxBytes, long refusedBytes);

    /// Live bytes of `prefix` fell below the warning ratio of its cap, which ends the refusal episode.
    @Contract
    void fullCleared(String prefix, long usedBytes, long maxBytes);

    /// The listener of an engine nobody listens to.
    DhtCapacityListener NONE = new DhtCapacityListener() {
        @Override
        @Contract
        public void nearFull(String prefix, long liveBytes, long tombstoneBytes, long maxBytes) {}

        @Override
        @Contract
        public void nearFullCleared(String prefix, long liveBytes, long tombstoneBytes, long maxBytes) {}

        @Override
        @Contract
        public void full(String prefix, long usedBytes, long maxBytes, long refusedBytes) {}

        @Override
        @Contract
        public void fullCleared(String prefix, long usedBytes, long maxBytes) {}
    };
}
