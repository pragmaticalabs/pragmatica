// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.dht.DHTClient;
import org.pragmatica.dht.Partition;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.storage.BlockId;
import org.pragmatica.storage.EncryptionError;
import org.pragmatica.storage.StorageError;
import org.pragmatica.storage.TierLevel;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.Arrays.copyOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

class DhtStorageTierTest {
    private static final byte[] SAMPLE_CONTENT = "hello distributed world".getBytes();
    private static final String KEY_PREFIX = "test-blocks";

    private InMemoryDHTClient dhtClient;
    private DhtStorageTier tier;

    @BeforeEach
    void setUp() {
        dhtClient = new InMemoryDHTClient();
        tier = DhtStorageTier.dhtStorageTier(dhtClient, KEY_PREFIX);
    }

    @Nested
    class PutAndGet {
        @Test
        void putGet_roundTrip_returnsOriginalContent() {
            var blockId = blockIdOf(SAMPLE_CONTENT);

            tier.put(blockId, SAMPLE_CONTENT)
                .await()
                .onFailure(_ -> fail("put should succeed"));

            tier.get(blockId)
                .await()
                .onFailure(_ -> fail("get should succeed"))
                .onSuccess(opt -> assertThat(opt.isPresent()).isTrue());
        }

        @Test
        void get_nonExistent_returnsNone() {
            var blockId = blockIdOf("nonexistent".getBytes());

            tier.get(blockId)
                .await()
                .onFailure(_ -> fail("get should succeed"))
                .onSuccess(opt -> assertThat(opt.isPresent()).isFalse());
        }
    }

    @Nested
    class ExistsAndDelete {
        @Test
        void exists_afterPut_returnsTrue() {
            var blockId = blockIdOf(SAMPLE_CONTENT);

            tier.put(blockId, SAMPLE_CONTENT).await();

            tier.exists(blockId)
                .await()
                .onFailure(_ -> fail("exists should succeed"))
                .onSuccess(found -> assertThat(found).isTrue());
        }

        @Test
        void exists_beforePut_returnsFalse() {
            var blockId = blockIdOf(SAMPLE_CONTENT);

            tier.exists(blockId)
                .await()
                .onFailure(_ -> fail("exists should succeed"))
                .onSuccess(found -> assertThat(found).isFalse());
        }

        @Test
        void delete_removesBlock() {
            var blockId = blockIdOf(SAMPLE_CONTENT);

            tier.put(blockId, SAMPLE_CONTENT).await();
            tier.delete(blockId).await();

            tier.exists(blockId)
                .await()
                .onFailure(_ -> fail("exists should succeed"))
                .onSuccess(found -> assertThat(found).isFalse());
        }
    }

    @Nested
    class TierProperties {
        @Test
        void level_returnsRemote() {
            assertThat(tier.level()).isEqualTo(TierLevel.REMOTE);
        }

        @Test
        void usedBytes_returnsZero() {
            assertThat(tier.usedBytes()).isZero();
        }

        @Test
        void maxBytes_returnsMaxValue() {
            assertThat(tier.maxBytes()).isEqualTo(Long.MAX_VALUE);
        }

        /// #250: the DHT is a cluster-wide shared store -- node-local garbage collection must
        /// never delete here on the strength of this node's own refcount belief alone. Pins the
        /// override against a silent revert to the `StorageTier` interface default (`false`).
        @Test
        void isShared_returnsTrue() {
            assertThat(tier.isShared()).isTrue();
        }
    }

    /// #858 C1: `DhtStorageTier.get` is gated on a per-instance `readGate` that
    /// `StorageFactory.verifyDhtMarker` resolves post-formation. These pin the ruling's two required
    /// states: a read arriving while the gate is still PENDING waits at most a bound then fails with
    /// a named cause; a read arriving after the gate was already REFUSED fails immediately with the
    /// refusal cause, never waiting the bound.
    @Nested
    class Admission {
        /// Far below the 30s production default (`DhtStorageTier.DEFAULT_ADMISSION_TIMEOUT`) so the
        /// pending-then-times-out test proves the bound in milliseconds, not seconds.
        private static final TimeSpan SHORT_ADMISSION_TIMEOUT = timeSpan(150).millis();

        @Test
        void get_whileGatePending_timesOutWithTierNotAdmitted_afterBound() {
            var readGate = Promise.<Unit> promise();
            var gatedTier = DhtStorageTier.dhtStorageTier(dhtClient, KEY_PREFIX, "content", readGate, SHORT_ADMISSION_TIMEOUT);
            var started = System.nanoTime();

            gatedTier.get(blockIdOf(SAMPLE_CONTENT))
                     .await()
                     .onSuccess(_ -> fail("a read against a gate that never resolves must not succeed"))
                     .onFailure(cause -> assertThat(cause).isInstanceOf(StorageError.TierNotAdmitted.class));

            var elapsedMillis = (System.nanoTime() - started) / 1_000_000;

            assertThat(elapsedMillis).as("must wait at least the admission bound, not fail instantly")
                                     .isGreaterThanOrEqualTo(SHORT_ADMISSION_TIMEOUT.millis());
            assertThat(elapsedMillis).as("must not wait meaningfully longer than the bound -- proves the wait is "
                                         + "bounded, not merely eventually satisfied by it")
                                     .isLessThan(SHORT_ADMISSION_TIMEOUT.millis() + 2000);
        }

        @Test
        void get_afterGateRefused_failsImmediately_withRefusalCause() {
            var readGate = Promise.<Unit> promise();
            var refusal = new EncryptionError.EncryptedTierRequiresKeyring("content", "key-1");
            readGate.resolve(Result.failure(refusal));

            var gatedTier = DhtStorageTier.dhtStorageTier(dhtClient, KEY_PREFIX, "content", readGate, SHORT_ADMISSION_TIMEOUT);
            var started = System.nanoTime();

            gatedTier.get(blockIdOf(SAMPLE_CONTENT))
                     .await()
                     .onSuccess(_ -> fail("a read against a refused tier must not succeed"))
                     .onFailure(cause -> assertThat(cause).isSameAs(refusal));

            var elapsedMillis = (System.nanoTime() - started) / 1_000_000;

            assertThat(elapsedMillis).as("a refusal must fail immediately, never wait out the admission bound")
                                     .isLessThan(SHORT_ADMISSION_TIMEOUT.millis());
        }
    }

    // --- Helpers ---

    private static BlockId blockIdOf(byte[] content) {
        return BlockId.blockId(content).unwrap();
    }

    /// In-memory DHTClient stub backed by ConcurrentHashMap.
    static final class InMemoryDHTClient implements DHTClient {
        private final ConcurrentHashMap<String, byte[]> store = new ConcurrentHashMap<>();

        @Override
        public Promise<Option<byte[]>> get(byte[] key) {
            return Promise.success(option(store.get(keyString(key))).map(v -> copyOf(v, v.length)));
        }

        @Override
        public Promise<Unit> put(byte[] key, byte[] value) {
            store.put(keyString(key), copyOf(value, value.length));
            return Promise.success(unit());
        }

        @Override
        public Promise<Boolean> remove(byte[] key) {
            return Promise.success(store.remove(keyString(key)) != null);
        }

        @Override
        public Promise<Boolean> exists(byte[] key) {
            return Promise.success(store.containsKey(keyString(key)));
        }

        @Override
        public Partition partitionFor(byte[] key) {
            return null;
        }

        private static String keyString(byte[] key) {
            return new String(key, StandardCharsets.UTF_8);
        }
    }
}
