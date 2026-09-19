// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.lang.reflect.Proxy;

import org.pragmatica.aether.stream.consumer.ConsumerGroupCoordinator;
import org.pragmatica.aether.stream.consumer.ConsumerGroupRegistry;
import org.pragmatica.aether.api.routes.StreamRoutes.StreamCreateRequest;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.resource.DurableTopicSpec;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.stream.StreamNamespacesService;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.StreamWriteRouter;
import org.pragmatica.aether.stream.topic.DurableTopicSubstrate;
import org.pragmatica.cluster.state.kvstore.KVStore;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// #1282: stream KIND is carried by the engine-name prefix — `entity:` (entity keyspace logs,
/// `EntityPartitionArc`), `topic:` (durable topics and their DLQs, `DurableTopicNames`) and `system:`
/// (system streams) — and runtime rules key off it (#1233's fail-on-drop rule, the system budget
/// bypass). The Management API must never mint a stream under one of those prefixes with an
/// operator-chosen config: the real resource would later find it already present. Every external mint
/// site is covered — the legacy body-carried create, the catalog create (whose `topic`/`entity`
/// namespaces yield `topic:…`/`entity:…` engine keys), and both publish auto-create fallbacks. Internal
/// provisioning calls `StreamPartitionManager` directly and is unaffected.
class StreamRoutesReservedPrefixTest {

    @Test
    void createStream_topicPrefixedName_isRefusedAndNothingIsMinted() {
        assertLegacyCreateRefused("topic:foo");
    }

    @Test
    void createStream_entityPrefixedName_isRefusedAndNothingIsMinted() {
        assertLegacyCreateRefused("entity:ledger");
    }

    /// The pre-existing guard refuses only the ENUMERATED system streams (`SystemStreams.ALL`); any
    /// other `system:` name still minted a stream that then took the system budget bypass.
    @Test
    void createStream_nonEnumeratedSystemPrefixedName_isRefusedAndNothingIsMinted() {
        assertLegacyCreateRefused("system:foo:1.0.0");
        assertLegacyCreateRefused("system:bare");
    }

    @Test
    void catalogCreate_topicNamespace_isRefusedAndNothingIsMintedOrRegistered() {
        assertCatalogCreateRefused("topic", "topic:foo:1.0.0");
    }

    @Test
    void catalogCreate_entityNamespace_isRefusedAndNothingIsMintedOrRegistered() {
        assertCatalogCreateRefused("entity", "entity:foo:1.0.0");
    }

    /// Publish auto-create with NO committed config would fabricate a management default under the
    /// reserved name — the exact collision the refusal exists to prevent.
    @Test
    void ensureStreamExists_reservedNameWithoutCommittedConfig_isRefusedAndNothingIsMinted() {
        var manager = streamPartitionManager(Long.MAX_VALUE);

        try {
            legacyRoutes(manager).ensureStreamExists("topic:foo")
                                 .onSuccess(_ -> fail("publish auto-create must not mint 'topic:foo' with a management default"));

            assertThat(manager.streamInfo("topic:foo").isEmpty()).isTrue();
        } finally {
            manager.close();
        }
    }

    @Test
    void catalogPublish_reservedNamespaceWithoutCommittedConfig_isRefusedAndNothingIsMinted() {
        var manager = streamPartitionManager(Long.MAX_VALUE);

        try {
            catalogRoutes(manager, StreamNamespacesService.inMemory())
                .publishEvent("topic", "foo", "1.0.0", new StreamApiRoutes.PublishRequest("payload", null))
                .await()
                .onSuccess(_ -> fail("catalog publish auto-create must not mint 'topic:foo:1.0.0'"));

            assertThat(manager.streamInfo("topic:foo:1.0.0").isEmpty()).isTrue();
        } finally {
            manager.close();
        }
    }

    /// The refusal is a Management-API boundary, not a manager-level one: internal durable-topic
    /// provisioning still creates `topic:<address>` (and its DLQ) on the same manager.
    @Test
    void durableTopicProvisioning_stillCreatesTopicPrefixedStreams() {
        var manager = streamPartitionManager(Long.MAX_VALUE);

        try {
            var spec = DurableTopicSpec.durableTopicSpec(1, 2, 2, DurableTopicSpec.DEFAULT_RETENTION).unwrap();

            DurableTopicSubstrate.durableTopicSubstrate(manager)
                                 .activateTopic("foo", spec)
                                 .onFailure(cause -> fail("internal topic provisioning must succeed: " + cause.message()));

            assertThat(manager.streamInfo("topic:foo").isPresent()).isTrue();
            assertThat(manager.streamInfo("topic:foo.dlq").isPresent()).isTrue();
        } finally {
            manager.close();
        }
    }

    // === helpers ===

    private static void assertLegacyCreateRefused(String name) {
        var manager = streamPartitionManager(Long.MAX_VALUE);

        try {
            legacyRoutes(manager).createStream(new StreamCreateRequest(name, 4))
                                 .onSuccess(_ -> fail("a create under a reserved prefix must be refused: " + name));

            assertThat(manager.streamInfo(name).isEmpty()).as("nothing minted under " + name).isTrue();
        } finally {
            manager.close();
        }
    }

    private static void assertCatalogCreateRefused(String namespace, String engineKey) {
        var manager = streamPartitionManager(Long.MAX_VALUE);
        var namespacesService = StreamNamespacesService.inMemory();

        try {
            catalogRoutes(manager, namespacesService)
                .createStream(namespace, "foo", "1.0.0", new StreamApiRoutes.CreateRequest(null))
                .onSuccess(_ -> fail("a catalog create yielding engine key " + engineKey + " must be refused"));

            assertThat(manager.streamInfo(engineKey).isEmpty()).as("nothing minted under " + engineKey).isTrue();
            assertThat(namespacesService.snapshot()).as("nothing registered for " + engineKey).isEmpty();
        } finally {
            manager.close();
        }
    }

    private static StreamRoutes legacyRoutes(StreamPartitionManager manager) {
        return StreamRoutes.streamRoutes(() -> nodeWith(manager), null, null);
    }

    private static StreamApiRoutes catalogRoutes(StreamPartitionManager manager, StreamNamespacesService namespacesService) {
        return StreamApiRoutes.streamApiRoutes(() -> nodeWith(manager),
                                               namespacesService,
                                               ConsumerGroupCoordinator.noOp(),
                                               ConsumerGroupRegistry.consumerGroupRegistry());
    }

    private static ManageableNode nodeWith(StreamPartitionManager manager) {
        return (ManageableNode) Proxy.newProxyInstance(ManageableNode.class.getClassLoader(),
                                                       new Class[]{ManageableNode.class},
                                                       (_, method, _) -> stubbed(method.getName(), manager));
    }

    private static Object stubbed(String method, StreamPartitionManager manager) {
        return switch (method) {
            case "streamPartitionManager" -> manager;
            case "kvStore" -> new KVStore<AetherKey, AetherValue>(null, null, null);
            case "streamWriteRouter" -> StreamWriteRouter.localOnly(manager);
            default -> throw new UnsupportedOperationException("Not stubbed in test proxy: " + method);
        };
    }
}
