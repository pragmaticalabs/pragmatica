// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.dht.DHTClient;
import org.pragmatica.dht.Partition;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.concurrent.ConcurrentHashMap;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Unit.unit;

/// #1777 (CTO ruling R2 / Q4): idempotency stores its dedup records in the REPLICATED DHT — the bare `DHTClient`
/// extension, at the committed `[replication]` factors — and only the cache namespace uses the cache's own, lower
/// `[cache]` replication ([CacheDhtClient]). A dedup record lost with its only copy re-executes the call it guarded.
class DhtNamespaceExtensionTest {
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

    private static final class RecordingDhtClient implements DHTClient {
        final ConcurrentHashMap<String, byte[]> storage = new ConcurrentHashMap<>();

        @Override
        public Promise<Option<byte[]>> get(byte[] key) {
            return Promise.success(Option.option(storage.get(new String(key, UTF_8))));
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
