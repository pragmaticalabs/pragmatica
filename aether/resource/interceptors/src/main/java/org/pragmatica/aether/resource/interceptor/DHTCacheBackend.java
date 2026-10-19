// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.function.LongSupplier;

import org.pragmatica.dht.DHTClient;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.pragmatica.lang.Unit.unit;


/// Distributed entries carry their own expiry (#279 item 3): the DHT has no expiry primitive, so each
/// stored value is a self-identifying envelope -- a 4-byte header (three `0xFF` bytes and a format
/// version), the writer's wall-clock expiry (epoch millis, 8 bytes big-endian), then the encoded value.
/// A read past the expiry is a miss, and the expired entry is removed so that it does not accumulate
/// (idempotency keys are unique per request, so entries nobody rewrites would grow without bound).
///
/// The removal is NOT conditional. Search space: [DHTClient], [DistributedDHTClient], `DHTMessage` and
/// the storage engines under `integrations/dht`. Replicas keep a per-entry HLC version, but the client
/// exposes neither a versioned read nor a remove-if-unchanged: `RemoveRequest` carries only the remove's
/// own fresh stamp, which supersedes any older copy (a remove is a tombstone, last-writer-wins). So the
/// expired entry is re-read immediately before the remove and removed only if it is still byte-identical
/// to the expired one, which narrows the window but cannot close it: a fresh put of the same key landing
/// in that window is deleted. The window is set by the quorums, not by one round trip: the re-read and the
/// remove each wait for a quorum, and when R + W <= RF (the replicated DHT the idempotency store uses) a
/// read quorum need not include the replicas that already hold an acknowledged put, so the window lasts
/// until that put has replicated to the replicas the re-read and remove reach (the replication lag).
/// For the distributed idempotency store that means a completed-outcome record can be lost and a retry
/// re-runs the method. The entry is already expired, so a failure of the cleanup (the re-read or the remove)
/// does not fail the get: it is logged at WARN with the key and reported as a miss, and the entry stays for
/// the next read to retry. Only a failure of the primary read fails the get. Expiry compares clocks across
/// nodes, so a node whose clock runs ahead of the writer's sees entries expire early by that skew, and
/// one that runs behind sees them live late by it.
///
/// The header makes the envelope distinguishable from every value written before it existed. Those
/// start with a VLQ type tag (`SliceCodec.writeCompact`) and tags stay below 2^21, so a tag is at most
/// 3 bytes and its last byte has the high bit clear: no legacy value can begin with three bytes that all
/// have the high bit set. A value without the header is read as a miss and removed, with the same
/// unconditional removal and the same window.
final class DHTCacheBackend implements CacheBackend {
    private static final Logger log = LoggerFactory.getLogger(DHTCacheBackend.class);

    private static final byte[] HEADER = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 1};

    static final int HEADER_BYTES = HEADER.length + Long.BYTES;

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
        return ByteBuffer.allocate(HEADER_BYTES + valueBytes.length)
                         .put(HEADER)
                         .putLong(expiryMillis)
                         .put(valueBytes)
                         .array();
    }

    /// The expiry instant of a stored envelope.
    static long expiryMillis(byte[] stored) {
        return ByteBuffer.wrap(stored, HEADER.length, Long.BYTES).getLong();
    }

    private Promise<Option<Object>> unexpired(Object key, byte[] stored) {
        return hasHeader(stored)
               ? liveValue(key, stored)
               : expired(key, stored);
    }

    private boolean hasHeader(byte[] stored) {
        return stored.length >= HEADER_BYTES && Arrays.equals(stored, 0, HEADER.length, HEADER, 0, HEADER.length);
    }

    private Promise<Option<Object>> liveValue(Object key, byte[] stored) {
        return expiryMillis(stored) > clockMillis.getAsLong()
               ? decoded(Arrays.copyOfRange(stored, HEADER_BYTES, stored.length))
               : expired(key, stored);
    }

    private Promise<Option<Object>> expired(Object key, byte[] expiredBytes) {
        return dhtClient.get(namespacedKey(key))
                        .flatMap(current -> removeIfUnchanged(key, expiredBytes, current))
                        .recover(cause -> missAfterFailedCleanup(key, cause));
    }

    /// FER: the entry is already expired, so the read's answer (a miss) is the same whether or not the
    /// cleanup happened; failing the get over it would turn a transient DHT error into a failed request
    /// (the idempotency interceptor does not fail open). Guarantee earned: the get never fails because of
    /// cleanup. Mechanism: the failure is logged and the entry is left in place, so the next read that
    /// finds it expired retries the cleanup; nothing is queued or retried here.
    private Option<Object> missAfterFailedCleanup(Object key, Cause cause) {
        log.warn("Cleanup of expired entry {}:{} failed, reporting a miss and leaving it for the next read: {}",
                 namespace,
                 key,
                 cause.message());

        return Option.none();
    }

    private Promise<Option<Object>> removeIfUnchanged(Object key, byte[] expiredBytes, Option<byte[]> current) {
        return current.filter(bytes -> Arrays.equals(bytes, expiredBytes))
                      .isPresent()
               ? discarded(key)
               : Promise.success(Option.none());
    }

    private Promise<Option<Object>> discarded(Object key) {
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
