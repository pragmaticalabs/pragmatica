// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.dht.ConsistentHashRing;
import org.pragmatica.dht.DHTAntiEntropy;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.dht.DHTMessage;
import org.pragmatica.dht.DHTNetwork;
import org.pragmatica.dht.DHTNode;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.Message;
import org.pragmatica.messaging.MessageRouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// A6 (#2265): the node routes the repair plane's message types to the DHT, and every request is answered to its
/// sender. Goes through the production wiring (`AetherNode.routeDhtRepairPlane`) with a real DHT node and a recording
/// network. Without the verified-read route, every remote verified read waits out its phase deadline (15 s at
/// defaults) before falling back to the quorum read, and nothing in the DHT module's own tests can see it.
class AetherNodeDhtRepairPlaneRoutesTest {
    private static final NodeId SELF = new NodeId("node-a");
    private static final NodeId PEER = new NodeId("node-b");
    private static final byte[] KEY = "content-blocks/00".getBytes(StandardCharsets.UTF_8);

    private final List<Map.Entry<NodeId, ProtocolMessage>> sent = new CopyOnWriteArrayList<>();
    private final List<MessageRouter.Entry<?>> entries = new ArrayList<>();

    private void assemble() {
        var ring = ConsistentHashRing.<NodeId>consistentHashRing();

        ring.addNode(SELF);
        ring.addNode(PEER);

        var config = new DHTConfig(2, 1, 1, timeSpan(2).seconds());
        var node = DHTNode.dhtNode(SELF, memoryStorageEngine(), ring, config);
        DHTNetwork network = (target, message) -> sent.add(Map.entry(target, message));

        AetherNode.routeDhtRepairPlane(entries, node, network, DHTAntiEntropy.dhtAntiEntropy(node, network, config));
    }

    /// The message router's dispatch: every route entry registered for exactly this message class.
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void fire(Message message) {
        entries.stream()
               .flatMap(MessageRouter.Entry::entries)
               .filter(tuple -> tuple.first().isInstance(message))
               .forEach(tuple -> ((Consumer) tuple.last()).accept(message));
    }

    private long routesFor(Class<?> type) {
        return entries.stream()
                      .flatMap(MessageRouter.Entry::entries)
                      .filter(tuple -> tuple.first().equals(type))
                      .count();
    }

    private <M extends ProtocolMessage> List<M> sentToPeer(Class<M> type) {
        return sent.stream()
                   .filter(entry -> entry.getKey().equals(PEER))
                   .map(Map.Entry::getValue)
                   .filter(type::isInstance)
                   .map(type::cast)
                   .toList();
    }

    /// Red when the verified-read route is dropped: the request reaches no handler, so no answer goes back.
    @Test
    void verifiedReadRequest_isAnsweredToItsSender() {
        assemble();

        fire(new DHTMessage.VerifiedReadRequest("get-1", PEER, KEY, new byte[32], false));
        fire(new DHTMessage.VerifiedReadRequest("exists-1", PEER, KEY, new byte[32], true));

        assertThat(sentToPeer(DHTMessage.GetResponse.class)).extracting(DHTMessage.GetResponse::requestId)
                                                            .containsExactly("get-1");
        assertThat(sentToPeer(DHTMessage.ExistsResponse.class)).extracting(DHTMessage.ExistsResponse::requestId)
                                                               .containsExactly("exists-1");
    }

    @Test
    void stampDigestAndPageRequests_areAnsweredToTheirSender() {
        assemble();

        fire(new DHTMessage.StampDigestRequest("digest-1", PEER, 0));
        fire(new DHTMessage.MigrationPageRequest("page-1", PEER, 0, Option.none(), 1L << 20));

        assertThat(sentToPeer(DHTMessage.DigestResponse.class)).extracting(DHTMessage.DigestResponse::requestId)
                                                               .containsExactly("digest-1");
        assertThat(sentToPeer(DHTMessage.MigrationPageResponse.class)).extracting(DHTMessage.MigrationPageResponse::requestId)
                                                                      .containsExactly("page-1");
    }

    @Test
    void everyRepairPlaneType_hasExactlyOneRoute() {
        assemble();

        for (var type : List.of(DHTMessage.StampDigestRequest.class,
                                DHTMessage.MigrationPageRequest.class,
                                DHTMessage.MigrationPageResponse.class,
                                DHTMessage.VerifiedReadRequest.class)) {
            assertThat(routesFor(type)).as(type.getSimpleName()).isEqualTo(1);
        }
    }
}
