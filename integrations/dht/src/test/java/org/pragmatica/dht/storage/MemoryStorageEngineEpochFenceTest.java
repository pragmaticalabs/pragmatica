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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.dht.DHTError;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;

/// Owner-epoch fence semantics at the DHT data-plane commit point (#345 piece 1c). The fence wraps
/// the existing HLC-version LWW: a strictly-older owner epoch is rejected outright; same-or-newer
/// epochs fall through to the unchanged within-epoch LWW; an accepted write advances the per-arc
/// high-water.
class MemoryStorageEngineEpochFenceTest {
    private static byte[] key(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] value(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /// In-test high-water-backed gate keyed by the single "core" arc (mirrors the aether wiring's
    /// per-DHT-partition high-water, compared exactly as `Epoch.compareTo`: term then counter).
    private static final class RecordingGate implements OwnerEpochGate {
        private long hwTerm = Long.MIN_VALUE;
        private long hwCounter = Long.MIN_VALUE;
        private int advanceCount;

        private boolean seeded() {
            return hwTerm != Long.MIN_VALUE;
        }

        @Override
        public boolean isStale(byte[] key, long epochTerm, long epochCounter) {
            return seeded() && compare(hwTerm, hwCounter, epochTerm, epochCounter) > 0;
        }

        @Override
        public void advance(byte[] key, long epochTerm, long epochCounter) {
            advanceCount++;
            if (!seeded() || compare(epochTerm, epochCounter, hwTerm, hwCounter) > 0) {
                hwTerm = epochTerm;
                hwCounter = epochCounter;
            }
        }

        private static int compare(long t1, long c1, long t2, long c2) {
            var byTerm = Long.compare(t1, t2);
            return byTerm != 0 ? byTerm : Long.compare(c1, c2);
        }
    }

    @Nested
    class FirstWrite {
        @Test
        void putVersioned_firstWriteNoCommitted_accepted() {
            var engine = memoryStorageEngine(new RecordingGate());

            engine.putVersioned(key("k"), value("v"), 100L, 5L, 0L)
                  .await()
                  .onFailure(c -> fail("Expected accept: " + c.message()))
                  .onSuccess(written -> assertThat(written).isTrue());
        }

        @Test
        void putVersioned_firstWriteSeedsHighWater_laterOlderEpochRejected() {
            var engine = memoryStorageEngine(new RecordingGate());

            engine.putVersioned(key("k"), value("v"), 100L, 5L, 0L).await();

            engine.putVersioned(key("k"), value("stale"), 200L, 4L, 9L)
                  .await()
                  .onSuccess(_ -> fail("Expected stale-epoch reject"))
                  .onFailure(cause -> assertThat(cause).isInstanceOf(DHTError.StaleEpochWrite.class));
        }
    }

    @Nested
    class StaleEpochRejection {
        private MemoryStorageEngine engine;

        @BeforeEach
        void setUp() {
            engine = memoryStorageEngine(new RecordingGate());
            engine.putVersioned(key("k"), value("v0"), 100L, 7L, 3L).await();
        }

        @Test
        void putVersioned_strictlyOlderTerm_rejectedNoWrite() {
            engine.putVersioned(key("k"), value("deposed"), 999L, 6L, 99L)
                  .await()
                  .onSuccess(_ -> fail("Expected reject"))
                  .onFailure(cause -> assertThat(cause).isInstanceOf(DHTError.StaleEpochWrite.class));

            engine.get(key("k"))
                  .await()
                  .onSuccess(opt -> opt.onPresent(v -> assertThat(v).isEqualTo(value("v0")))
                                       .onEmpty(() -> fail("value must be unchanged")));
        }

        @Test
        void putVersioned_sameTermOlderCounter_rejected() {
            engine.putVersioned(key("k"), value("deposed"), 999L, 7L, 2L)
                  .await()
                  .onSuccess(_ -> fail("Expected reject"))
                  .onFailure(cause -> assertThat(cause).isInstanceOf(DHTError.StaleEpochWrite.class));
        }
    }

    @Nested
    class SameEpochLwwUnchanged {
        private MemoryStorageEngine engine;

        @BeforeEach
        void setUp() {
            engine = memoryStorageEngine(new RecordingGate());
            engine.putVersioned(key("k"), value("v1"), 200L, 7L, 3L).await();
        }

        @Test
        void putVersioned_sameEpochOlderVersion_supersededReportedFalse() {
            engine.putVersioned(key("k"), value("v2"), 100L, 7L, 3L)
                  .await()
                  .onFailure(c -> fail("Same-epoch supersede must be a success(false), not a failure: " + c.message()))
                  .onSuccess(written -> assertThat(written).isFalse());

            engine.get(key("k"))
                  .await()
                  .onSuccess(opt -> opt.onPresent(v -> assertThat(v).isEqualTo(value("v1"))));
        }

        @Test
        void putVersioned_sameEpochNewerVersion_accepted() {
            engine.putVersioned(key("k"), value("v3"), 300L, 7L, 3L)
                  .await()
                  .onFailure(c -> fail("Expected accept: " + c.message()))
                  .onSuccess(written -> assertThat(written).isTrue());

            engine.get(key("k"))
                  .await()
                  .onSuccess(opt -> opt.onPresent(v -> assertThat(v).isEqualTo(value("v3"))));
        }
    }

    @Nested
    class NewerEpochAcceptedAdvancesHighWater {
        @Test
        void putVersioned_newerEpoch_acceptedThenDeposesPriorEpoch() {
            var engine = memoryStorageEngine(new RecordingGate());
            engine.putVersioned(key("k"), value("v1"), 200L, 7L, 3L).await();

            engine.putVersioned(key("k"), value("v2-newepoch"), 50L, 8L, 0L)
                  .await()
                  .onFailure(c -> fail("Newer epoch must be accepted even with a smaller HLC version: " + c.message()))
                  .onSuccess(written -> assertThat(written).isTrue());

            // High-water advanced to 8:0 → the prior owner at 7:x is now rejected.
            engine.putVersioned(key("k"), value("v3-oldepoch"), 9999L, 7L, 9L)
                  .await()
                  .onSuccess(_ -> fail("Prior epoch must now be fenced"))
                  .onFailure(cause -> assertThat(cause).isInstanceOf(DHTError.StaleEpochWrite.class));
        }
    }

    @Nested
    class Determinism {
        @Test
        void putVersioned_sameCommittedStateAndPut_sameDecisionOnEveryReplica() {
            var decisions = new ConcurrentHashMap<Integer, Boolean>();

            for (int replica = 0; replica < 3; replica++) {
                var engine = memoryStorageEngine(new RecordingGate());
                engine.putVersioned(key("k"), value("v0"), 100L, 7L, 3L).await();

                var idx = replica;
                engine.putVersioned(key("k"), value("v1"), 200L, 6L, 0L)
                      .await()
                      .onSuccess(_ -> decisions.put(idx, Boolean.TRUE))
                      .onFailure(_ -> decisions.put(idx, Boolean.FALSE));
            }

            // Strictly-older epoch (6:0 < 7:3) → every replica must reject identically.
            assertThat(decisions.values()).containsOnly(Boolean.FALSE);
        }
    }

    @Nested
    class NoOpGate {
        @Test
        void putVersioned_noOpGate_neverFencesRegardlessOfEpoch() {
            var engine = memoryStorageEngine();
            engine.putVersioned(key("k"), value("v0"), 100L, 7L, 3L).await();

            // A wildly older epoch is NOT fenced when the engine has the no-op gate (non-cluster path).
            engine.putVersioned(key("k"), value("v1"), 200L, 1L, 0L)
                  .await()
                  .onFailure(c -> fail("No-op gate must never fence: " + c.message()))
                  .onSuccess(written -> assertThat(written).isTrue());
        }
    }
}
