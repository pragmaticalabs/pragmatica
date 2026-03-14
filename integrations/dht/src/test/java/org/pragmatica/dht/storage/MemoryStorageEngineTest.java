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
import org.pragmatica.consensus.NodeId;
import org.pragmatica.dht.ConsistentHashRing;
import org.pragmatica.dht.DHTMessage;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;

class MemoryStorageEngineTest {
    private MemoryStorageEngine engine;

    @BeforeEach
    void setUp() {
        engine = memoryStorageEngine();
    }

    private static byte[] key(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] value(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Nested
    class VersionedWrites {
        @Test
        void putVersioned_newerVersion_writesValue() {
            engine.putVersioned(key("k1"), value("v1"), 100L)
                  .await()
                  .onFailure(c -> fail("Expected success: " + c.message()))
                  .onSuccess(written -> assertThat(written).isTrue());

            engine.get(key("k1"))
                  .await()
                  .onFailure(c -> fail("Expected success: " + c.message()))
                  .onSuccess(opt -> opt.onPresent(v -> assertThat(v).isEqualTo(value("v1")))
                                       .onEmpty(() -> fail("Expected value to be present")));
        }

        @Test
        void putVersioned_olderVersion_rejected() {
            engine.putVersioned(key("k1"), value("v1"), 200L).await();

            engine.putVersioned(key("k1"), value("v2"), 100L)
                  .await()
                  .onFailure(c -> fail("Expected success: " + c.message()))
                  .onSuccess(written -> assertThat(written).isFalse());

            engine.get(key("k1"))
                  .await()
                  .onSuccess(opt -> opt.onPresent(v -> assertThat(v).isEqualTo(value("v1"))));
        }

        @Test
        void putVersioned_sameVersion_rejected() {
            engine.putVersioned(key("k1"), value("v1"), 100L).await();

            engine.putVersioned(key("k1"), value("v2"), 100L)
                  .await()
                  .onFailure(c -> fail("Expected success: " + c.message()))
                  .onSuccess(written -> assertThat(written).isFalse());

            engine.get(key("k1"))
                  .await()
                  .onSuccess(opt -> opt.onPresent(v -> assertThat(v).isEqualTo(value("v1"))));
        }

        @Test
        void put_unversioned_usesVersionZero_canBeOverwrittenByVersionedWrite() {
            engine.put(key("k1"), value("v1")).await();

            engine.putVersioned(key("k1"), value("v2"), 1L)
                  .await()
                  .onFailure(c -> fail("Expected success: " + c.message()))
                  .onSuccess(written -> assertThat(written).isTrue());

            engine.get(key("k1"))
                  .await()
                  .onFailure(c -> fail("Expected success: " + c.message()))
                  .onSuccess(opt -> opt.onPresent(v -> assertThat(v).isEqualTo(value("v2")))
                                       .onEmpty(() -> fail("Expected value to be present")));
        }

        @Test
        void putVersioned_thenGet_returnsLatestValue() {
            engine.putVersioned(key("k1"), value("v1"), 100L).await();
            engine.putVersioned(key("k1"), value("v2"), 200L).await();
            engine.putVersioned(key("k1"), value("v3"), 150L).await();

            engine.get(key("k1"))
                  .await()
                  .onFailure(c -> fail("Expected success: " + c.message()))
                  .onSuccess(opt -> opt.onPresent(v -> assertThat(v).isEqualTo(value("v2")))
                                       .onEmpty(() -> fail("Expected value to be present")));
        }
    }

    @Nested
    class MigrationVersionAwareness {
        @Test
        void putVersioned_afterUnversionedPut_canOverwrite() {
            engine.put(key("mk1"), value("original")).await();

            engine.putVersioned(key("mk1"), value("updated"), 5L)
                  .await()
                  .onFailure(c -> fail("Expected success: " + c.message()))
                  .onSuccess(written -> assertThat(written).isTrue());

            engine.get(key("mk1"))
                  .await()
                  .onFailure(c -> fail("Expected success: " + c.message()))
                  .onSuccess(opt -> opt.onPresent(v -> assertThat(v).isEqualTo(value("updated")))
                                       .onEmpty(() -> fail("Expected value to be present")));
        }

        @Test
        void putVersioned_afterMigrationData_notPoisoned() {
            // Simulate migration: unversioned put (uses version 0 internally)
            engine.put(key("migrated-key"), value("migrated-value")).await();

            // Subsequent versioned write with version > 0 must succeed
            engine.putVersioned(key("migrated-key"), value("new-value"), 1L)
                  .await()
                  .onFailure(c -> fail("Expected success: " + c.message()))
                  .onSuccess(written -> assertThat(written).isTrue());

            engine.get(key("migrated-key"))
                  .await()
                  .onFailure(c -> fail("Expected success: " + c.message()))
                  .onSuccess(opt -> opt.onPresent(v -> assertThat(v).isEqualTo(value("new-value")))
                                       .onEmpty(() -> fail("Expected value to be present")));
        }

        @Test
        void entriesForPartition_includesVersionInKeyValue() {
            var ring = ConsistentHashRing.<NodeId>consistentHashRing();
            var nodeId = new NodeId("test-node");
            ring.addNode(nodeId);

            engine.putVersioned(key("partition-key"), value("pv1"), 42L).await();

            var partition = ring.partitionFor(key("partition-key"));

            engine.entriesForPartition(ring, partition)
                  .await()
                  .onFailure(c -> fail("Expected success: " + c.message()))
                  .onSuccess(entries -> assertThat(entries).isNotEmpty()
                                                           .first()
                                                           .extracting(DHTMessage.KeyValue::version)
                                                           .isEqualTo(42L));
        }

        @Test
        void entries_includesVersionInKeyValue() {
            engine.putVersioned(key("versioned-entry"), value("ve1"), 99L).await();

            engine.entries()
                  .await()
                  .onFailure(c -> fail("Expected success: " + c.message()))
                  .onSuccess(entries -> assertThat(entries).hasSize(1)
                                                           .first()
                                                           .extracting(DHTMessage.KeyValue::version)
                                                           .isEqualTo(99L));
        }
    }

    @Nested
    class ConcurrentVersionedWrites {
        @Test
        void putVersioned_concurrentWritesDifferentVersions_highestVersionWins() throws InterruptedException {
            var threads = new CopyOnWriteArrayList<Thread>();

            for (int i = 0; i < 8; i++) {
                var version = (long) (i + 1) * 100;
                var val = value("value-" + version);
                threads.add(Thread.ofVirtual().start(() ->
                    engine.putVersioned(key("concurrent-key"), val, version).await()
                ));
            }

            for (var thread : threads) {
                thread.join();
            }

            engine.get(key("concurrent-key"))
                  .await()
                  .onFailure(c -> fail("Expected success: " + c.message()))
                  .onSuccess(opt -> opt.onPresent(v -> assertThat(v).isEqualTo(value("value-800")))
                                       .onEmpty(() -> fail("Expected value to be present")));
        }
    }
}
