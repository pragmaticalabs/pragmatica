// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.dht;

import org.pragmatica.dht.DHTClient;

import java.util.function.Function;


final class DhtBackedMapFactory implements ReplicatedMapFactory {
    private final DHTClient client;

    DhtBackedMapFactory(DHTClient client) {
        this.client = client;
    }

    @Override
    public <K, V> ReplicatedMap<K, V> create(String name,
                                             Function<K, byte[]> keySerializer,
                                             Function<byte[], K> keyDeserializer,
                                             Function<V, byte[]> valueSerializer,
                                             Function<byte[], V> valueDeserializer) {
        return new NamespacedReplicatedMap<>(name,
                                             client,
                                             keySerializer,
                                             keyDeserializer,
                                             valueSerializer,
                                             valueDeserializer);
    }
}
