// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

import org.pragmatica.dht.DHTClient;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
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

    @Override
    public Promise<Option<Object>> get(Object key) {
        return dhtClient.get(namespacedKey(key))
                        .map(opt -> opt.map(bytes -> (Object) deserializer.decode(bytes)));
    }

    @Override
    public Promise<Unit> put(Object key, Object value) {
        var keyBytes = namespacedKey(key);
        var valueBytes = serializer.encode(value);

        return dhtClient.put(keyBytes, valueBytes);
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
