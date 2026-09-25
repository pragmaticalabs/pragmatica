// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.endpoint;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.kvstore.AetherKey.TopicSubscriptionKey;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.aether.slice.resource.ResourceVersion;
import org.pragmatica.aether.slice.kvstore.AetherValue.TopicSubscriptionValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopicSubscriptionRegistryTest {
    private TopicSubscriptionRegistry registry;
    private Artifact artifact;
    private MethodName method;
    private NodeId nodeA;
    private NodeId nodeB;

    @BeforeEach
    void setUp() {
        registry = TopicSubscriptionRegistry.topicSubscriptionRegistry();
        artifact = Artifact.artifact("org.example:my-slice:1.0.0").unwrap();
        method = MethodName.methodName("onMessage").unwrap();
        nodeA = new NodeId("node-a");
        nodeB = new NodeId("node-b");
    }

    private void registerSubscription(String topicName, Artifact artifact, MethodName method, NodeId nodeId) {
        registerSubscriptionAt(resourceAddress(topicName), artifact, method, nodeId);
    }

    /// #1448: the node is part of the KEY, so a remove names the INSTANCE being unloaded, not the
    /// subscription as a whole. A remove for one node cannot reach another node's row.
    private void removeSubscription(String topicName, Artifact artifact, MethodName method, NodeId nodeId) {
        var key = TopicSubscriptionKey.topicSubscriptionKey(resourceAddress(topicName), artifact, method, nodeId);
        var remove = new KVCommand.Remove<TopicSubscriptionKey>(key);
        registry.onSubscriptionRemove(new ValueRemove<>(remove, Option.none()));
    }

    private static ResourceAddress resourceAddress(String topicName) {
        return ResourceAddress.resourceAddress(ResourceAddress.DEFAULT_NAMESPACE, topicName, ResourceVersion.defaultVersion()).unwrap();
    }

    /// Full routing identity (`namespace:name:version`) for a bare topic name in the default namespace.
    private static String routingKey(String topicName) {
        return resourceAddress(topicName).asString();
    }

    /// Register a subscription at an explicit, fully-qualified address (`namespace:name:version`).
    private void registerSubscriptionAt(ResourceAddress address, Artifact artifact, MethodName method, NodeId nodeId) {
        var key = TopicSubscriptionKey.topicSubscriptionKey(address, artifact, method, nodeId);
        var value = TopicSubscriptionValue.topicSubscriptionValue(nodeId);
        var put = new KVCommand.Put<>(key, value);
        registry.onSubscriptionPut(new ValuePut<>(put, Option.none()));
    }

    private static ResourceAddress addressOf(String namespace, String name, String version) {
        return ResourceAddress.resourceAddress(namespace, name, version).unwrap();
    }

    @Nested
    class FindSubscribers {
        @Test
        void findSubscribers_emptyRegistry_returnsEmptyList() {
            var subscribers = registry.findSubscribers(routingKey("orders"));

            assertTrue(subscribers.isEmpty());
        }

        @Test
        void findSubscribers_multipleSubscribersForTopic_returnsAll() {
            var artifact2 = Artifact.artifact("org.example:other-slice:1.0.0").unwrap();
            var method2 = MethodName.methodName("handleOrder").unwrap();

            registerSubscription("orders", artifact, method, nodeA);
            registerSubscription("orders", artifact2, method2, nodeB);

            var subscribers = registry.findSubscribers(routingKey("orders"));

            assertEquals(2, subscribers.size());
        }

        @Test
        void findSubscribers_differentArtifactsSameTopic_returnsOnePerArtifact() {
            var artifact2 = Artifact.artifact("org.example:billing-slice:2.0.0").unwrap();

            registerSubscription("orders", artifact, method, nodeA);
            registerSubscription("orders", artifact2, method, nodeB);

            var subscribers = registry.findSubscribers(routingKey("orders"));

            assertEquals(2, subscribers.size());
            var nodeIds = subscribers.stream()
                                     .map(TopicSubscriptionRegistry.TopicSubscriber::nodeId)
                                     .toList();
            assertTrue(nodeIds.contains(nodeA));
            assertTrue(nodeIds.contains(nodeB));
        }

        @Test
        void findSubscribers_multipleVersionsSameTopic_roundRobinPerGroup() {
            // TopicSubscriptionKey = (address, artifact, method, node). Two VERSIONS of one slice are
            // two artifacts, hence two groups, hence one selected subscriber each. (Before #1448 the
            // key had no node and this comment read "same key overwrites" — differing versions were
            // the only way to get two rows at all. Same-version instances now get a row each; that
            // case is covered by sameSliceOnTwoNodes_* below.)
            var artifact2 = Artifact.artifact("org.example:my-slice:1.0.1").unwrap();

            registerSubscription("orders", artifact, method, nodeA);
            registerSubscription("orders", artifact2, method, nodeB);

            // Each artifact+method is a separate group, so both should be returned
            var first = registry.findSubscribers(routingKey("orders"));
            var second = registry.findSubscribers(routingKey("orders"));

            assertEquals(2, first.size());
            assertEquals(2, second.size());
        }
    }

    @Nested
    class SubscriptionPut {
        @Test
        void onSubscriptionPut_singleSubscription_findReturnsIt() {
            registerSubscription("orders", artifact, method, nodeA);

            var subscribers = registry.findSubscribers(routingKey("orders"));

            assertEquals(1, subscribers.size());
            var subscriber = subscribers.getFirst();
            assertEquals(artifact, subscriber.artifact());
            assertEquals(method, subscriber.methodName());
            assertEquals(nodeA, subscriber.nodeId());
        }

        @Test
        void onSubscriptionPut_multipleTopics_findReturnsOnlyMatching() {
            registerSubscription("orders", artifact, method, nodeA);
            var artifact2 = Artifact.artifact("org.example:billing:1.0.0").unwrap();
            registerSubscription("payments", artifact2, method, nodeB);

            var orderSubscribers = registry.findSubscribers(routingKey("orders"));
            var paymentSubscribers = registry.findSubscribers(routingKey("payments"));

            assertEquals(1, orderSubscribers.size());
            assertEquals(nodeA, orderSubscribers.getFirst().nodeId());
            assertEquals(1, paymentSubscribers.size());
            assertEquals(nodeB, paymentSubscribers.getFirst().nodeId());
        }

        /// #1448 INVERTED THIS TEST, and the old assertion was encoding the defect rather than a
        /// requirement. It read `onSubscriptionPut_duplicateKey_updatesValue`: two INSTANCES of one
        /// slice were one key, so the second node's put OVERWROTE the first and the registry could
        /// only ever route to the last writer. With the node in the key they are two rows, both
        /// retained, and the round-robin that `findSubscribers` already implements finally has two
        /// members to alternate between. One subscriber per call is unchanged — that is the
        /// at-most-once-per-group contract.
        @Test
        void onSubscriptionPut_sameSliceOnTwoNodes_bothRetainedAndRoundRobined() {
            registerSubscription("orders", artifact, method, nodeA);
            registerSubscription("orders", artifact, method, nodeB);

            assertEquals(2, registry.allSubscriptions().size(), "two instances are two rows, not one overwrite");

            var first = registry.findSubscribers(routingKey("orders"));
            var second = registry.findSubscribers(routingKey("orders"));

            assertEquals(1, first.size(), "one subscriber per group per call");
            assertEquals(1, second.size());
            assertEquals(List.of(nodeA, nodeB),
                         List.of(first.getFirst().nodeId(), second.getFirst().nodeId()),
                         "successive publishes alternate across the group's instances (sorted by node id)");
        }

        /// The ephemeral half of #1448, which the durable-group tests in `aether/node` cannot see:
        /// one instance unloading must not un-route the topic for the instance still running. Before
        /// the node was in the key this removed the only row and `findSubscribers` returned empty,
        /// which is the branch `TopicPublisher.publish` answers with success-and-zero-deliveries.
        @Test
        void onSubscriptionRemove_oneOfTwoInstances_otherStillRoutable() {
            registerSubscription("orders", artifact, method, nodeA);
            registerSubscription("orders", artifact, method, nodeB);

            removeSubscription("orders", artifact, method, nodeB);

            var subscribers = registry.findSubscribers(routingKey("orders"));

            assertEquals(1, subscribers.size(), "the surviving instance is still a subscriber");
            assertEquals(nodeA, subscribers.getFirst().nodeId());
            assertEquals(1, registry.allSubscriptions().size(), "and only the unloading node's row was removed");
        }
    }

    @Nested
    class SubscriptionRemove {
        @Test
        void onSubscriptionRemove_existingSubscription_noLongerFound() {
            registerSubscription("orders", artifact, method, nodeA);

            removeSubscription("orders", artifact, method, nodeA);

            var subscribers = registry.findSubscribers(routingKey("orders"));
            assertTrue(subscribers.isEmpty());
        }

        @Test
        void onSubscriptionRemove_nonExistentKey_noError() {
            removeSubscription("orders", artifact, method, nodeA);

            var subscribers = registry.findSubscribers(routingKey("orders"));
            assertTrue(subscribers.isEmpty());
        }
    }

    @Nested
    class AllSubscriptions {
        @Test
        void allSubscriptions_afterPuts_returnsAll() {
            var artifact2 = Artifact.artifact("org.example:billing:1.0.0").unwrap();
            registerSubscription("orders", artifact, method, nodeA);
            registerSubscription("payments", artifact2, method, nodeB);

            var all = registry.allSubscriptions();

            assertEquals(2, all.size());
        }
    }

    /// RC2 #274 — routing must be namespace- and version-aware so the same bare topic name in
    /// different blueprints/namespaces (or different versions) never cross-delivers.
    @Nested
    class NamespaceIsolation {
        @Test
        void findSubscribers_sameBareNameDifferentNamespaces_selectsOnlyMatchingNamespace() {
            var nsA = addressOf("ns-a", "events", "1.0.0");
            var nsB = addressOf("ns-b", "events", "1.0.0");

            registerSubscriptionAt(nsA, artifact, method, nodeA);
            registerSubscriptionAt(nsB, artifact, method, nodeB);

            var subscribers = registry.findSubscribers(nsA.asString());

            assertEquals(1, subscribers.size());
            assertEquals(nodeA, subscribers.getFirst().nodeId());
        }

        @Test
        void findSubscribers_sameBareNameDifferentNamespaces_otherNamespaceNotReached() {
            var nsA = addressOf("ns-a", "events", "1.0.0");
            var nsB = addressOf("ns-b", "events", "1.0.0");

            registerSubscriptionAt(nsA, artifact, method, nodeA);
            registerSubscriptionAt(nsB, artifact, method, nodeB);

            var subscribers = registry.findSubscribers(nsB.asString());

            assertEquals(1, subscribers.size());
            assertEquals(nodeB, subscribers.getFirst().nodeId());
        }

        @Test
        void findSubscribers_matchingNamespace_isSelected() {
            var nsA = addressOf("ns-a", "events", "1.0.0");

            registerSubscriptionAt(nsA, artifact, method, nodeA);

            var subscribers = registry.findSubscribers(nsA.asString());

            assertEquals(1, subscribers.size());
            assertEquals(nodeA, subscribers.getFirst().nodeId());
        }

        @Test
        void findSubscribers_sameNamespaceAndNameDifferentVersions_noCrossVersionBleed() {
            var v1 = addressOf("ns-a", "events", "1.0.0");
            var v2 = addressOf("ns-a", "events", "2.0.0");

            registerSubscriptionAt(v1, artifact, method, nodeA);
            registerSubscriptionAt(v2, artifact, method, nodeB);

            var subscribers = registry.findSubscribers(v1.asString());

            assertEquals(1, subscribers.size());
            assertEquals(nodeA, subscribers.getFirst().nodeId());
        }
    }
}
