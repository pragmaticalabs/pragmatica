// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.dht;

import org.pragmatica.aether.slice.kvstore.AetherKey.EndpointKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.EndpointValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


@SuppressWarnings("JBCT-UTIL-02")
public sealed interface DhtNodeCleanup {
    Logger log = LoggerFactory.getLogger(DhtNodeCleanup.class);

    static Promise<Unit> cleanupDeadNodeEndpoints(NodeId deadNode,
                                                  ReplicatedMap<EndpointKey, EndpointValue> endpointMap,
                                                  List<EndpointKey> endpointKeysForNode) {
        if (endpointKeysForNode.isEmpty()) {
            log.debug("No endpoints to clean up for dead node {}", deadNode);

            return Promise.unitPromise();
        }

        log.info("Cleaning up {} endpoints for dead node {}", endpointKeysForNode.size(), deadNode);

        return removeEndpointsSequentially(deadNode, endpointMap, endpointKeysForNode);
    }

    private static Promise<Unit> removeEndpointsSequentially(NodeId deadNode,
                                                             ReplicatedMap<EndpointKey, EndpointValue> endpointMap,
                                                             List<EndpointKey> keys) {
        var result = Promise.unitPromise();

        for (var key : keys) {
            result = result.flatMap(_ -> endpointMap.remove(key)
                                                    .mapToUnit());
        }

        return result.onSuccess(_ -> log.info("Completed cleanup of {} endpoints for dead node {}",
                                              keys.size(),
                                              deadNode));
    }

    record unused() implements DhtNodeCleanup {}
}
