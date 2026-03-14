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

import java.nio.charset.StandardCharsets;

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
        void put_unversioned_alwaysWins() {
            engine.putVersioned(key("k1"), value("v1"), Long.MAX_VALUE - 1).await();

            engine.put(key("k1"), value("v2")).await();

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
}
