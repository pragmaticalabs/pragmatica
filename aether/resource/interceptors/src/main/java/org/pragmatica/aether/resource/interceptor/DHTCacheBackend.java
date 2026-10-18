// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.function.LongSupplier;

import org.pragmatica.dht.DHTClient;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.pragmatica.lang.Unit.unit;


/// Distributed entries carry their own expiry (#279 item 3): the DHT has no expiry primitive, so each
/// stored value is the writer's wall-clock expiry (epoch millis, 8 bytes big-endian) followed by the
/// encoded value, and a read past that instant is a miss that also removes the entry. Expiry is
/// therefore enforced lazily, on read: an entry nobody reads again stays in the DHT until it is
/// overwritten or removed. It compares clocks across nodes, so a node whose clock runs ahead of the
/// writer's sees entries expire early by that skew, and one that runs behind sees them live late by it.
/// An entry written before this envelope existed is read as a miss and removed.
final class DHTCacheBackend implements CacheBackend {
    private static final int EXPIRY_BYTES = Long.BYTES;

    private final DHTClient dhtClient;
    private final Serializer serializer;
    private final Deserializer deserializer;
    private final String namespace;
    private final long ttlMillis;
    private final LongSupplier clockMillis;

    private DHTCacheBackend(DHTClient dhtClient,
                            Serializer serializer,
                            Deserializer deserializer,
                            String namespace,
                            long ttlMillis,
                            LongSupplier clockMillis) {
        this.dhtClient = dhtClient;
        this.serializer = serializer;
        this.deserializer = deserializer;
        this.namespace = namespace;
        this.ttlMillis = ttlMillis;
        this.clockMillis = clockMillis;
    }

    static DHTCacheBackend dhtCacheBackend(DHTClient dhtClient,
                                           Serializer serializer,
                                           Deserializer deserializer,
                                           String namespace,
                                           int ttlSeconds) {
        return dhtCacheBackend(dhtClient, serializer, deserializer, namespace, ttlSeconds, System::currentTimeMillis);
    }

    /// Test seam: the wall clock the expiry is stamped and checked against.
    static DHTCacheBackend dhtCacheBackend(DHTClient dhtClient,
                                           Serializer serializer,
                                           Deserializer deserializer,
                                           String namespace,
                                           int ttlSeconds,
                                           LongSupplier clockMillis) {
        return new DHTCacheBackend(dhtClient, serializer, deserializer, namespace, ttlSeconds * 1000L, clockMillis);
    }

    /// The codec calls are lifted into `Result`s: `SliceCodec.read` THROWS on an unknown type tag and
    /// `write` on an unregistered class, and a throw inside a promise mapper is not a failure — it
    /// escapes or leaves the promise unresolved (the total-mapper contract). Lifted, a stale entry
    /// written by a codec that no longer knows its type reads as a miss and an unencodable value
    /// as a failed put, so the interceptor's fail-open covers them like any backend failure
    /// (review of #1084, SF-1). Without this a stale-codec entry made the miss permanent and the call a hang.
    @Override
    public Promise<Option<Object>> get(Object key) {
        return dhtClient.get(namespacedKey(key))
                        .flatMap(opt -> opt.fold(() -> Promise.success(Option.none()),
                                                 stored -> unexpired(key, stored)));
    }

    @Override
    public Promise<Unit> put(Object key, Object value) {
        var keyBytes = namespacedKey(key);

        return Result.lift(Causes::fromThrowable,
                           () -> serializer.encode(value))
                     .fold(Promise::failure,
                           valueBytes -> dhtClient.put(keyBytes,
                                                       withExpiry(valueBytes)));
    }

    private byte[] withExpiry(byte[] valueBytes) {
        return envelope(clockMillis.getAsLong() + ttlMillis, valueBytes);
    }

    /// The stored form: expiry instant, then the encoded value. Package-visible so a test seeds the DHT
    /// with the same bytes a write produces.
    static byte[] envelope(long expiryMillis, byte[] valueBytes) {
        return ByteBuffer.allocate(EXPIRY_BYTES + valueBytes.length)
                         .putLong(expiryMillis)
                         .put(valueBytes)
                         .array();
    }

    private Promise<Option<Object>> unexpired(Object key, byte[] stored) {
        return isLive(stored)
               ? decoded(Arrays.copyOfRange(stored, EXPIRY_BYTES, stored.length))
               : expired(key);
    }

    private boolean isLive(byte[] stored) {
        return stored.length >= EXPIRY_BYTES && ByteBuffer.wrap(stored).getLong() > clockMillis.getAsLong();
    }

    private Promise<Option<Object>> expired(Object key) {
        return remove(key).map(_ -> Option.none());
    }

    private Promise<Option<Object>> decoded(byte[] bytes) {
        return Result.lift(Causes::fromThrowable,
                           () -> (Object) deserializer.decode(bytes))
                     .map(Option::some)
                     .async();
    }

    @Override
    public Promise<Unit> remove(Object key) {
        return dhtClient.remove(namespacedKey(key))
                        .map(_ -> unit());
    }

    private byte[] namespacedKey(Object key) {
        return (namespace + ":" + key).getBytes(UTF_8);
    }
}
