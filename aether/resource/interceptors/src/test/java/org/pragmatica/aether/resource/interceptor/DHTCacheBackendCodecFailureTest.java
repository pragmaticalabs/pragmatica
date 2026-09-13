// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.dht.DHTClient;
import org.pragmatica.dht.Partition;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import org.junit.jupiter.api.Test;

import io.netty.buffer.ByteBuf;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.Unit.unit;


/// #279 (review of #1084, SF-1): the interceptor's fail-open recovers FAILED PROMISES, but the DHT
/// backend's codec calls THROW — `SliceCodec.read` on an unknown type tag (a stale entry written by
/// a codec that no longer knows the type), `write` on an unregistered class — and a throw inside a
/// promise mapper is neither a miss nor a failure: with a synchronous client it escaped
/// `intercept(...)` raw, with the real client it hung. Because distributed entries never expire,
/// a stale-codec entry made that permanent. The codec calls are now lifted, so the interceptor
/// treats them like any backend failure: the method runs, its value is returned.
class DHTCacheBackendCodecFailureTest {
    private static final Fn1<Object, ?> IDENTITY = Fn1.id();
    private static final TimeSpan TIMEOUT = TimeSpan.timeSpan(5).seconds();

    @Test
    void cacheAside_staleEntryTheCodecCannotRead_isAMiss_andTheMethodRuns() {
        var storage = new ConcurrentHashMap<String, byte[]>();

        storage.put("ns:k", "written-by-an-older-codec".getBytes(UTF_8));

        var backend = DHTCacheBackend.dhtCacheBackend(new FakeDHTClient(storage), new ThrowingCodec(), new ThrowingCodec(), "ns");
        var calls = new AtomicInteger();
        var intercepted = new CacheMethodInterceptor(backend, CacheStrategy.CACHE_ASIDE, IDENTITY)
                              .intercept((String request) -> Promise.success(computed(calls, request)));

        var value = intercepted.apply("k").await(TIMEOUT).fold(cause -> fail("must be fail-open, got " + cause.message()), v -> v);

        assertThat(value).isEqualTo("result-k");
        assertThat(calls.get()).as("the business method ran; a codec throw is a miss, not a hang").isEqualTo(1);
    }

    @Test
    void writeThrough_valueTheCodecCannotWrite_returnsTheMethodsValue() {
        var backend = DHTCacheBackend.dhtCacheBackend(new FakeDHTClient(new ConcurrentHashMap<>()), new ThrowingCodec(), new ThrowingCodec(), "ns");
        var calls = new AtomicInteger();
        var intercepted = new CacheMethodInterceptor(backend, CacheStrategy.WRITE_THROUGH, IDENTITY)
                              .intercept((String request) -> Promise.success(computed(calls, request)));

        var value = intercepted.apply("k").await(TIMEOUT).fold(cause -> fail("a codec failure on put must not fail the write, got " + cause.message()), v -> v);

        assertThat(value).isEqualTo("result-k");
    }

    /// The backend itself, without the interceptor: a codec throw is a FAILED promise, the shape
    /// every recover above depends on.
    @Test
    void backend_codecThrow_isAFailedPromise_notAThrow() {
        var storage = new ConcurrentHashMap<String, byte[]>();

        storage.put("ns:k", "stale".getBytes(UTF_8));

        var backend = DHTCacheBackend.dhtCacheBackend(new FakeDHTClient(storage), new ThrowingCodec(), new ThrowingCodec(), "ns");

        backend.get("k").await(TIMEOUT).onSuccess(_ -> fail("decode threw; the promise must fail"));
        backend.put("k", "v").await(TIMEOUT).onSuccess(_ -> fail("encode threw; the promise must fail"));
    }

    private static String computed(AtomicInteger calls, String request) {
        calls.incrementAndGet();

        return "result-" + request;
    }

    /// A codec that knows no types — what `SliceCodec` does for a tag it has never registered.
    private static final class ThrowingCodec implements Serializer, Deserializer {
        @Override
        public <T> void write(ByteBuf byteBuf, T object) {
            throw new IllegalArgumentException("No codec registered for class: " + object.getClass().getName());
        }

        @Override
        public <T> T read(ByteBuf byteBuf) {
            throw new IllegalArgumentException("Unknown type tag 4242");
        }
    }

    private static final class FakeDHTClient implements DHTClient {
        private final ConcurrentHashMap<String, byte[]> storage;

        FakeDHTClient(ConcurrentHashMap<String, byte[]> storage) {
            this.storage = storage;
        }

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
            return new Partition(0);
        }
    }
}
