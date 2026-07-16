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

import org.pragmatica.lang.Contract;

import java.util.List;

/// Operator-visible sink for an incomplete graceful-departure push (issue #427, D4). When a
/// draining node cannot confirm — within its bounded budget — that every locally-held chunk reached
/// a surviving replica, it notifies this observer with the count of at-risk keys and a bounded
/// sample. The aether layer wires it to a `DeparturePushIncomplete` cluster event so the signal
/// reaches `/api/events`; this Apache-2.0 module stays free of any `ClusterEvent` dependency by
/// accepting only primitives (same seam as `DrainProcedure`'s `Consumer<DrainReason>`).
@FunctionalInterface
public interface DeparturePushObserver {
    /// Report that `keysAtRisk` chunks were still unacknowledged when the push budget expired.
    ///
    /// @param keysAtRisk total number of chunks not confirmed on a surviving replica
    /// @param sampleKeys bounded first-N sample of the at-risk keys (hex-encoded) for triage
    @Contract
    void onIncomplete(int keysAtRisk, List<String> sampleKeys);

    /// No-op observer for non-cluster DHT paths and tests.
    static DeparturePushObserver noop() {
        return (keysAtRisk, sampleKeys) -> {};
    }
}
