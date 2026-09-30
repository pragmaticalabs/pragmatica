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

/// What an all-miss DHT resolve actually established, so a "not found" downstream can be read as "lost" or
/// "unreachable" instead of a bare absence. `DHTClient.get` answers `Option`, so four different situations
/// arrive as an empty: the quorum resolving on its first `quorum` empty replies without waiting for the rest of
/// the R-set; a fallback probe that timed out or was refused (degraded to empty); an R-set member filtered out as
/// not live; and a ring larger than the bounded probe reaches. This record carries the counts that separate them.
/// Attribution only — it changes no resolution or quorum rule.
///
/// @param keyHex        hex-encoded key
/// @param rSetSize      R-set members the ring assigns to the key
/// @param rSetLive      of those, the members the read actually targeted (the rest were filtered as not live)
/// @param rSetAnswered  R-set replies received when this was reported; replies after the quorum resolved count,
///                      so it can exceed the quorum but never proves a straggler was heard
/// @param probed        non-R-set ring members probed by the bounded fallback
/// @param probesFailed  probes that timed out or were refused (each read as empty)
/// @param unprobed      non-R-set ring members the probe bound left unread
/// @param elapsedMillis time from the R-set read starting to this report
public record ResolveMiss(String keyHex,
                          int rSetSize,
                          int rSetLive,
                          int rSetAnswered,
                          int probed,
                          int probesFailed,
                          int unprobed,
                          long elapsedMillis) {
    public static ResolveMiss resolveMiss(String keyHex,
                                          int rSetSize,
                                          int rSetLive,
                                          int rSetAnswered,
                                          int probed,
                                          int probesFailed,
                                          int unprobed,
                                          long elapsedMillis) {
        return new ResolveMiss(keyHex, rSetSize, rSetLive, rSetAnswered, probed, probesFailed, unprobed, elapsedMillis);
    }

    /// True only when nothing could have hidden a copy: every R-set member was targeted and answered, no probe
    /// failed, and the probe covered the rest of the ring. Anything else is "unreachable" — the key may still exist.
    public boolean lost() {
        return probesFailed == 0
               && unprobed == 0
               && rSetLive == rSetSize
               && rSetAnswered >= rSetSize;
    }

    /// How the empty was reached: `fallback-degraded` when a probe failed and was read as empty, otherwise
    /// `quorum-empty` (every probe answered empty). A read that never completed is reported by the artifact
    /// store as `timed-out`; it produces no all-miss at all.
    public String kind() {
        return probesFailed > 0
               ? "fallback-degraded"
               : "quorum-empty";
    }

    /// `lost` or `unreachable`, the verdict operators grep for.
    public String verdict() {
        return lost()
               ? "lost"
               : "unreachable";
    }
}
