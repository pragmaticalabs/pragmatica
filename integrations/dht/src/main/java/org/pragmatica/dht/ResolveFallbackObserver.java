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


/// Operator-visible sink for the resolve-time alternate-target fallback (issue #428, C2). When an
/// R-set quorum read MISSES, `DistributedDHTClient` probes a bounded set of ring members OUTSIDE the
/// R-set for a stranded copy; the outcome — a fallback HIT that was read-repaired, or an all-MISS
/// after the bounded probe — is reported here. Per the P3/P4 house style an all-miss is a loud,
/// never-silent signal. The aether layer wires this to a cluster event so it reaches `/api/events`;
/// this Apache-2.0 module stays free of any `ClusterEvent` dependency by accepting only primitives
/// and `String` (same seam as `DeparturePushObserver`). Two distinct outcomes mean this cannot be a
/// `@FunctionalInterface` — a plain interface with a [#noop] default is used instead.
public interface ResolveFallbackObserver {
    /// Report that a stranded copy was found beyond the R-set and read-repaired back onto it.
    ///
    /// @param keyHex hex-encoded key that resolved via the fallback probe
    /// @param probed number of non-R-set ring members probed (bounded)
    @Contract
    void onResolvedViaFallback(String keyHex, int probed);

    /// Report that neither the R-set nor the bounded fallback probe holds the key (all-miss).
    ///
    /// @param keyHex hex-encoded key that stayed unresolved after the fallback probe
    /// @param probed number of non-R-set ring members probed (bounded)
    @Contract
    void onUnresolvedAfterFallback(String keyHex, int probed);

    /// No-op observer for non-cluster DHT paths and tests.
    static ResolveFallbackObserver noop() {
        return new ResolveFallbackObserver() {
            @Override
            public void onResolvedViaFallback(String keyHex, int probed) {}

            @Override
            public void onUnresolvedAfterFallback(String keyHex, int probed) {}
        };
    }
}
