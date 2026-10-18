// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.

package org.pragmatica.aether.resource.interceptor;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.dht.DHTClient;
import org.pragmatica.dht.Partition;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.serialization.SliceCodec;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Unit.unit;

class DHTCacheBackendTest {

    /// A pre-envelope value: tag 3 (one VLQ byte), then payload; long enough to hold a header, so only the
    /// header check (not the length guard) can reject it.
    static final byte[] LEGACY_ONE_BYTE_TAG = {3, 0, 0, 0, 5, 'h', 'e', 'l', 'l', 'o', ' ', 'w', 'o', 'r', 'l', 'd'};

    private ConcurrentHashMap<String, byte[]> storage;
    private FakeDHTClient dhtClient;
    private DHTCacheBackend backend;
    private final AtomicLong clock = new AtomicLong(1_000_000L);

    @BeforeEach
    void setUp() {
        storage = new ConcurrentHashMap<>();
        dhtClient = new FakeDHTClient(storage);
        var serializer = new StringSerializer();
        var deserializer = new StringDeserializer();
        backend = DHTCacheBackend.dhtCacheBackend(dhtClient, serializer, deserializer, "test-ns", 60, clock::get);
    }

    @Test
    void get_existingKey_returnsDeserializedValue() {
        storage.put("test-ns:key1", DHTCacheBackend.envelope(Long.MAX_VALUE, "hello".getBytes(UTF_8)));

        var result = backend.get("key1").await().fold(_ -> Option.none(), v -> v);

        assertThat(result.isPresent()).isTrue();
        assertThat(result.unwrap()).isEqualTo("hello");
    }

    @Test
    void get_missingKey_returnsNone() {
        var result = backend.get("missing").await().fold(_ -> Option.none(), v -> v);

        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    void put_value_serializesAndStores() {
        backend.put("key1", "world").await();

        assertThat(storage.containsKey("test-ns:key1")).isTrue();
        assertThat(new String(storage.get("test-ns:key1"), DHTCacheBackend.HEADER_BYTES, 5, UTF_8)).isEqualTo("world");
    }

    @Test
    void remove_existingKey_removesEntry() {
        storage.put("test-ns:key1", DHTCacheBackend.envelope(Long.MAX_VALUE, "value".getBytes(UTF_8)));

        backend.remove("key1").await();

        assertThat(storage.containsKey("test-ns:key1")).isFalse();
    }

    @Test
    void get_namespacedKeys_isolatesDifferentNamespaces() {
        var otherBackend = DHTCacheBackend.dhtCacheBackend(
            new FakeDHTClient(storage), new StringSerializer(), new StringDeserializer(), "other-ns", 60);

        backend.put("key1", "ns1-value").await();
        otherBackend.put("key1", "ns2-value").await();

        var result1 = backend.get("key1").await().fold(_ -> Option.none(), v -> v);
        var result2 = otherBackend.get("key1").await().fold(_ -> Option.none(), v -> v);

        assertThat(result1.unwrap()).isEqualTo("ns1-value");
        assertThat(result2.unwrap()).isEqualTo("ns2-value");
    }

    @Test
    void put_thenGet_returnsValue() {
        backend.put("round-trip", "serialized-value").await();

        var result = backend.get("round-trip").await().fold(_ -> Option.none(), v -> v);

        assertThat(result.isPresent()).isTrue();
        assertThat(result.unwrap()).isEqualTo("serialized-value");
    }

    @Test
    void put_nullishKey_handlesGracefully() {
        backend.put("null", "value-for-null-key").await();

        var result = backend.get("null").await().fold(_ -> Option.none(), v -> v);

        assertThat(result.isPresent()).isTrue();
        assertThat(result.unwrap()).isEqualTo("value-for-null-key");
    }

    @Test
    void put_stampsTheExpiryInstant_asNowPlusTtl() {
        backend.put("key1", "world").await();

        assertThat(DHTCacheBackend.expiryMillis(storage.get("test-ns:key1"))).isEqualTo(1_000_000L + 60_000L);
    }

    @Test
    void get_beforeTheTtlElapses_returnsTheValue() {
        backend.put("key1", "world").await();
        clock.addAndGet(59_999L);

        var result = backend.get("key1").await().fold(_ -> Option.none(), v -> v);

        assertThat(result.unwrap()).isEqualTo("world");
    }

    @Test
    void get_afterTheTtlElapses_isAMissAndRemovesTheEntry() {
        backend.put("key1", "world").await();
        clock.addAndGet(60_000L);

        var result = backend.get("key1").await().fold(_ -> Option.some("failed"), v -> v);

        assertThat(result.isEmpty()).as("an entry past its TTL is a miss").isTrue();
        assertThat(storage.containsKey("test-ns:key1")).as("and the expired entry is removed").isFalse();
    }

    @Test
    void get_expiredEntryOverwrittenBeforeTheReread_keepsTheFreshValue() {
        backend.put("key1", "old").await();
        clock.addAndGet(60_000L);
        dhtClient.afterGet = () -> storage.put("test-ns:key1",
                                               DHTCacheBackend.envelope(clock.get() + 60_000L, "fresh".getBytes(UTF_8)));

        var first = backend.get("key1").await().fold(_ -> Option.some("failed"), v -> v);
        dhtClient.afterGet = () -> {};
        var second = backend.get("key1").await().fold(_ -> Option.none(), v -> v);

        assertThat(first.isEmpty()).as("the read saw the expired value").isTrue();
        assertThat(second.unwrap()).as("the re-read sees the fresh put, so it is not removed").isEqualTo("fresh");
    }

    /// KNOWN residual window (see the class doc): [DHTClient] has no compare-and-remove, so a fresh put that
    /// lands after the re-read and before the remove is deleted. Pinned so the behaviour stays visible; if a
    /// conditional remove ever exists this test should be turned around.
    @Test
    void get_expiredEntryOverwrittenAfterTheRereadBeforeTheRemove_losesTheFreshValue_knownWindow() {
        backend.put("key1", "old").await();
        clock.addAndGet(60_000L);
        dhtClient.beforeRemove = () -> storage.put("test-ns:key1",
                                                   DHTCacheBackend.envelope(clock.get() + 60_000L, "fresh".getBytes(UTF_8)));

        backend.get("key1").await();

        assertThat(storage.containsKey("test-ns:key1")).as("the fresh put is lost in the window").isFalse();
    }

    @Test
    void get_manyDistinctExpiredKeys_areRemovedByTheirReads() {
        for (var i = 0; i < 50; i++) {
            backend.put("key" + i, "v" + i).await();
        }
        assertThat(storage).hasSize(50);
        clock.addAndGet(60_000L);

        for (var i = 0; i < 50; i++) {
            backend.get("key" + i).await();
        }

        assertThat(storage).as("expired entries do not accumulate").isEmpty();
    }

    @Test
    void put_overAnExpiredEntry_startsAFreshTtl() {
        backend.put("key1", "old").await();
        clock.addAndGet(120_000L);
        backend.put("key1", "new").await();

        var result = backend.get("key1").await().fold(_ -> Option.none(), v -> v);

        assertThat(result.unwrap()).isEqualTo("new");
    }

    @Test
    void legacyEntry_tagBelow128_isMissAndRemoved() {
        storage.put("test-ns:key1", LEGACY_ONE_BYTE_TAG);

        var result = backend.get("key1").await().fold(_ -> Option.some("failed"), v -> v);

        assertThat(result.isEmpty()).isTrue();
        assertThat(storage.containsKey("test-ns:key1")).isFalse();
    }

    /// An envelope of a format version this code does not know is not readable: it is treated like a
    /// foreign value (a miss, removed), never decoded as if it were version 1.
    @Test
    void get_unknownVersionByte_isAMissAndRemovesTheEntry() {
        var unknownVersion = DHTCacheBackend.envelope(Long.MAX_VALUE, "hello".getBytes(UTF_8));
        unknownVersion[3] = 2;
        storage.put("test-ns:key1", unknownVersion);

        var result = backend.get("key1").await().fold(_ -> Option.some("failed"), v -> v);

        assertThat(result.isEmpty()).isTrue();
        assertThat(storage.containsKey("test-ns:key1")).isFalse();
    }

    @Test
    void get_entryShorterThanTheHeader_isAMissAndRemovesTheEntry() {
        storage.put("test-ns:key1", new byte[] {1, 2, 3});

        var result = backend.get("key1").await().fold(_ -> Option.some("failed"), v -> v);

        assertThat(result.isEmpty()).isTrue();
        assertThat(storage.containsKey("test-ns:key1")).isFalse();
    }

    /// Every tag a pre-envelope value can start with (0 .. the 2^21 tag limit, VLQ-encoded exactly as
    /// the codec writes it) leaves at least one of the first three bytes with the high bit clear, so
    /// none can match the header's three 0xFF bytes.
    @Test
    void header_isNeverProducedByAnyLegacyTag() {
        var buf = Unpooled.buffer(8);
        var offending = new ArrayList<Integer>();

        for (var tag = 0; tag < SliceCodec.USER_TAG_LIMIT; tag++) {
            buf.clear();
            SliceCodec.writeCompact(buf, tag);

            if (buf.writerIndex() > 3 || (buf.getByte(buf.writerIndex() - 1) & 0x80) != 0) {
                offending.add(tag);
            }
        }

        assertThat(offending).as("tags that could start with the header").isEmpty();
    }

    @Test
    void put_emptyEncodedValue_roundTrips() {
        backend.put("key1", "").await();

        var result = backend.get("key1").await().fold(_ -> Option.none(), v -> v);

        assertThat(storage.get("test-ns:key1")).hasSize(DHTCacheBackend.HEADER_BYTES);
        assertThat(result.unwrap()).isEqualTo("");
    }

    @Test
    void put_thirtyDayTtl_stampsTheExpiryWithoutIntOverflow() {
        var thirtyDays = 30 * 24 * 3600;
        var longTtl = DHTCacheBackend.dhtCacheBackend(dhtClient, new StringSerializer(), new StringDeserializer(),
                                                      "test-ns", thirtyDays, clock::get);

        longTtl.put("key1", "v").await();

        assertThat(DHTCacheBackend.expiryMillis(storage.get("test-ns:key1"))).isEqualTo(1_000_000L + thirtyDays * 1000L);
    }

    @Test
    void remove_nonExistentKey_succeeds() {
        var result = backend.remove("never-existed").await();

        assertThat(result.isSuccess()).isTrue();
    }

    private static final class FakeDHTClient implements DHTClient {
        private final ConcurrentHashMap<String, byte[]> storage;
        Runnable afterGet = () -> {};
        Runnable beforeRemove = () -> {};

        FakeDHTClient(ConcurrentHashMap<String, byte[]> storage) {
            this.storage = storage;
        }

        @Override
        public Promise<Option<byte[]>> get(byte[] key) {
            var stored = storage.get(new String(key, UTF_8));

            afterGet.run();
            return Promise.success(Option.option(stored));
        }

        @Override
        public Promise<Unit> put(byte[] key, byte[] value) {
            storage.put(new String(key, UTF_8), value);
            return Promise.success(unit());
        }

        @Override
        public Promise<Boolean> remove(byte[] key) {
            beforeRemove.run();
            var removed = storage.remove(new String(key, UTF_8)) != null;
            return Promise.success(removed);
        }

        @Override
        public Promise<Boolean> exists(byte[] key) {
            return Promise.success(storage.containsKey(new String(key, UTF_8)));
        }

        @Override
        public Partition partitionFor(byte[] key) {
            return null;
        }
    }

    private static final class StringSerializer implements Serializer {
        @Override
        public <T> byte[] encode(T object) {
            return object.toString().getBytes(UTF_8);
        }

        @Override
        public <T> void write(ByteBuf byteBuf, T object) {
            byteBuf.writeBytes(object.toString().getBytes(UTF_8));
        }
    }

    private static final class StringDeserializer implements Deserializer {
        @Override
        @SuppressWarnings("unchecked")
        public <T> T decode(byte[] bytes) {
            return (T) new String(bytes, UTF_8);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T read(ByteBuf byteBuf) {
            var bytes = new byte[byteBuf.readableBytes()];
            byteBuf.readBytes(bytes);
            return (T) new String(bytes, UTF_8);
        }
    }
}
