// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

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


final class DHTCacheBackend implements CacheBackend {
    private final DHTClient dhtClient;
    private final Serializer serializer;
    private final Deserializer deserializer;
    private final String namespace;

    private DHTCacheBackend(DHTClient dhtClient, Serializer serializer, Deserializer deserializer, String namespace) {
        this.dhtClient = dhtClient;
        this.serializer = serializer;
        this.deserializer = deserializer;
        this.namespace = namespace;
    }

    static DHTCacheBackend dhtCacheBackend(DHTClient dhtClient,
                                           Serializer serializer,
                                           Deserializer deserializer,
                                           String namespace) {
        return new DHTCacheBackend(dhtClient, serializer, deserializer, namespace);
    }

    /// The codec calls are lifted into `Result`s: `SliceCodec.read` THROWS on an unknown type tag and
    /// `write` on an unregistered class, and a throw inside a promise mapper is not a failure — it
    /// escapes or leaves the promise unresolved (the total-mapper contract). Lifted, a stale entry
    /// written by a codec that no longer knows its type reads as a miss and an unencodable value
    /// as a failed put, so the interceptor's fail-open covers them like any backend failure
    /// (review of #1084, SF-1). Distributed entries do not expire (#279 item 3), so without this a
    /// stale-codec entry made the miss permanent and the call a hang.
    @Override
    public Promise<Option<Object>> get(Object key) {
        return dhtClient.get(namespacedKey(key))
                        .flatMap(opt -> opt.fold(() -> Promise.success(Option.none()),
                                                 this::decoded));
    }

    @Override
    public Promise<Unit> put(Object key, Object value) {
        var keyBytes = namespacedKey(key);

        return Result.lift(Causes::fromThrowable,
                           () -> serializer.encode(value))
                     .fold(Promise::failure,
                           valueBytes -> dhtClient.put(keyBytes, valueBytes));
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
