// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.dht.DHTClient;
import org.pragmatica.dht.Partition;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Unit.unit;

/// #1777 (CTO ruling R2 / Q4): idempotency stores its dedup records in the REPLICATED DHT — the bare `DHTClient`
/// extension, at the committed `[replication]` factors — and only the cache namespace uses the cache's own, lower
/// `[cache]` replication ([CacheDhtClient]). A dedup record lost with its only copy re-executes the call it guarded.
class DhtNamespaceExtensionTest {
    private static final Cause METHOD_FAILED = Causes.cause("method failed");

    private final RecordingDhtClient replicated = new RecordingDhtClient();
    private final RecordingDhtClient cache = new RecordingDhtClient();
    private final ProvisioningContext context = ProvisioningContext.provisioningContext()
                                                                   .withKeyExtractor(key -> key)
                                                                   .withExtension(DHTClient.class, replicated)
                                                                   .withExtension(CacheDhtClient.class, new CacheDhtClient(cache))
                                                                   .withExtension(Serializer.class, new StringSerializer())
                                                                   .withExtension(Deserializer.class, new StringDeserializer());

    @Test
    void distributedIdempotency_storesInTheReplicatedDht_neverTheCacheNamespace() {
        var config = IdempotencyConfig.idempotencyConfig("dedup-replicated", CacheMode.DISTRIBUTED).unwrap();
        var interceptor = new IdempotencyInterceptorFactory().provision(config, context).await().unwrap();
        Fn1<Promise<String>, String> method = request -> Promise.success("done-" + request);

        interceptor.intercept(method).apply("call-1").await().unwrap();

        assertThat(replicated.storage).as("the dedup record is in the replicated DHT").isNotEmpty();
        assertThat(cache.storage).as("and not in the cache namespace").isEmpty();
    }

    @Test
    void distributedCache_storesInTheCacheNamespace() {
        var config = CacheConfig.cacheConfig("cache-namespace", CacheStrategy.CACHE_ASIDE, CacheMode.DISTRIBUTED).unwrap();
        var interceptor = new CacheInterceptorFactory().provision(config, context).await().unwrap();
        Fn1<Promise<String>, String> method = request -> Promise.success("value-" + request);

        interceptor.intercept(method).apply("key-1").await().unwrap();

        assertThat(cache.storage).as("the cache entry is in the cache namespace").isNotEmpty();
        assertThat(replicated.storage).isEmpty();
    }

    /// #279 item 3: the configured TTL reaches the distributed entry as its stamped expiry -- the cache's
    /// `ttl_seconds` for a cache, the idempotency `retention_seconds` for a dedup record.
    @Test
    void distributedCache_stampsTheConfiguredTtlOnTheStoredEntry() {
        var config = CacheConfig.cacheConfig("cache-ttl", CacheStrategy.CACHE_ASIDE, CacheMode.DISTRIBUTED).unwrap();
        var interceptor = new CacheInterceptorFactory().provision(config, context).await().unwrap();
        Fn1<Promise<String>, String> method = request -> Promise.success("value-" + request);
        var before = System.currentTimeMillis();

        interceptor.intercept(method).apply("key-1").await().unwrap();

        assertThat(expiryOf(cache)).isBetween(before + config.ttlSeconds() * 1000L,
                                              System.currentTimeMillis() + config.ttlSeconds() * 1000L);
    }

    @Test
    void distributedIdempotency_stampsTheRetentionOnTheStoredRecord() {
        var config = IdempotencyConfig.idempotencyConfig("dedup-ttl", CacheMode.DISTRIBUTED).unwrap();
        var interceptor = new IdempotencyInterceptorFactory().provision(config, context).await().unwrap();
        Fn1<Promise<String>, String> method = request -> Promise.success("done-" + request);
        var before = System.currentTimeMillis();

        interceptor.intercept(method).apply("call-1").await().unwrap();

        assertThat(expiryOf(replicated)).isBetween(before + config.retentionSeconds() * 1000L,
                                                   System.currentTimeMillis() + config.retentionSeconds() * 1000L);
    }

    /// A pre-envelope record must not wedge the key: its read is a miss (not a failed read that stops the
    /// call before the method runs), the method re-runs, and its outcome is recorded over the legacy bytes.
    @Test
    void distributedIdempotency_legacyRecord_isAMissThatRerunsAndReRecords() {
        var interceptor = idempotencyInterceptor("dedup-legacy");
        var runs = new AtomicInteger();
        Fn1<Promise<String>, String> method = request -> Promise.success("done-" + request + "-" + runs.incrementAndGet());
        interceptor.intercept(method).apply("call-1").await().unwrap();
        var storedKey = replicated.storage.keySet().iterator().next();
        replicated.storage.put(storedKey, DHTCacheBackendTest.LEGACY_ONE_BYTE_TAG);

        var rerun = interceptor.intercept(method).apply("call-1").await();
        var deduped = interceptor.intercept(method).apply("call-1").await();

        assertThat(rerun.unwrap()).isEqualTo("done-call-1-2");
        assertThat(deduped.unwrap()).as("the re-recorded outcome is served").isEqualTo("done-call-1-2");
        assertThat(runs).hasValue(2);
    }

    /// An expired record read must not delete a record a concurrent writer stored between the read and
    /// the miss: the DHT has no compare-and-remove, so the expired read leaves the entry alone. The
    /// method fails here so the interceptor does not overwrite the entry; what remains is the fresh record.
    @Test
    void distributedIdempotency_expiredRecordReadRacingAFreshPut_keepsTheFreshRecord() {
        var interceptor = idempotencyInterceptor("dedup-race");
        Fn1<Promise<String>, String> ok = request -> Promise.success("done-" + request);
        interceptor.intercept(ok).apply("call-1").await().unwrap();
        var storedKey = replicated.storage.keySet().iterator().next();
        var fresh = DHTCacheBackend.envelope(Long.MAX_VALUE, "fresh-outcome".getBytes(UTF_8));
        replicated.storage.put(storedKey, DHTCacheBackend.envelope(1L, "expired-outcome".getBytes(UTF_8)));
        replicated.afterGet = () -> replicated.storage.put(storedKey, fresh);
        Fn1<Promise<String>, String> failing = _ -> METHOD_FAILED.promise();

        var outcome = interceptor.intercept(failing).apply("call-1").await();

        assertThat(outcome.isFailure()).as("the expired record was a miss, so the method ran").isTrue();
        assertThat(replicated.storage.get(storedKey)).as("the concurrent fresh record survives").isEqualTo(fresh);
    }

    private IdempotencyMethodInterceptor idempotencyInterceptor(String name) {
        var config = IdempotencyConfig.idempotencyConfig(name, CacheMode.DISTRIBUTED).unwrap();

        return new IdempotencyInterceptorFactory().provision(config, context).await().unwrap();
    }

    private static long expiryOf(RecordingDhtClient client) {
        return DHTCacheBackend.expiryMillis(client.storage.values().iterator().next());
    }

    private static final class RecordingDhtClient implements DHTClient {
        final ConcurrentHashMap<String, byte[]> storage = new ConcurrentHashMap<>();
        Runnable afterGet = () -> {};

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
            return Promise.success(storage.remove(new String(key, UTF_8)) != null);
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
