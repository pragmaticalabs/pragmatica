package org.pragmatica.aether.endpoint;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.kvstore.AetherKey.TopicSubscriptionKey;
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
        var key = TopicSubscriptionKey.topicSubscriptionKey(topicName, artifact, method);
        var value = TopicSubscriptionValue.topicSubscriptionValue(nodeId);
        var put = new KVCommand.Put<>(key, value);
        registry.onSubscriptionPut(new ValuePut<>(put, Option.none()));
    }

    private void removeSubscription(String topicName, Artifact artifact, MethodName method) {
        var key = TopicSubscriptionKey.topicSubscriptionKey(topicName, artifact, method);
        var remove = new KVCommand.Remove<TopicSubscriptionKey>(key);
        registry.onSubscriptionRemove(new ValueRemove<>(remove, Option.none()));
    }

    @Nested
    class FindSubscribers {
        @Test
        void findSubscribers_emptyRegistry_returnsEmptyList() {
            var subscribers = registry.findSubscribers("orders");

            assertTrue(subscribers.isEmpty());
        }

        @Test
        void findSubscribers_multipleSubscribersForTopic_returnsAll() {
            var artifact2 = Artifact.artifact("org.example:other-slice:1.0.0").unwrap();
            var method2 = MethodName.methodName("handleOrder").unwrap();

            registerSubscription("orders", artifact, method, nodeA);
            registerSubscription("orders", artifact2, method2, nodeB);

            var subscribers = registry.findSubscribers("orders");

            assertEquals(2, subscribers.size());
        }

        @Test
        void findSubscribers_differentArtifactsSameTopic_returnsOnePerArtifact() {
            var artifact2 = Artifact.artifact("org.example:billing-slice:2.0.0").unwrap();

            registerSubscription("orders", artifact, method, nodeA);
            registerSubscription("orders", artifact2, method, nodeB);

            var subscribers = registry.findSubscribers("orders");

            assertEquals(2, subscribers.size());
            var nodeIds = subscribers.stream()
                                     .map(TopicSubscriptionRegistry.TopicSubscriber::nodeId)
                                     .toList();
            assertTrue(nodeIds.contains(nodeA));
            assertTrue(nodeIds.contains(nodeB));
        }

        @Test
        void findSubscribers_multipleVersionsSameTopic_roundRobinPerGroup() {
            // TopicSubscriptionKey = (topicName, artifact, method) — same key overwrites
            // Use different versions to create separate groups for round-robin
            var artifact2 = Artifact.artifact("org.example:my-slice:1.0.1").unwrap();

            registerSubscription("orders", artifact, method, nodeA);
            registerSubscription("orders", artifact2, method, nodeB);

            // Each artifact+method is a separate group, so both should be returned
            var first = registry.findSubscribers("orders");
            var second = registry.findSubscribers("orders");

            assertEquals(2, first.size());
            assertEquals(2, second.size());
        }
    }

    @Nested
    class SubscriptionPut {
        @Test
        void onSubscriptionPut_singleSubscription_findReturnsIt() {
            registerSubscription("orders", artifact, method, nodeA);

            var subscribers = registry.findSubscribers("orders");

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

            var orderSubscribers = registry.findSubscribers("orders");
            var paymentSubscribers = registry.findSubscribers("payments");

            assertEquals(1, orderSubscribers.size());
            assertEquals(nodeA, orderSubscribers.getFirst().nodeId());
            assertEquals(1, paymentSubscribers.size());
            assertEquals(nodeB, paymentSubscribers.getFirst().nodeId());
        }

        @Test
        void onSubscriptionPut_duplicateKey_updatesValue() {
            registerSubscription("orders", artifact, method, nodeA);
            registerSubscription("orders", artifact, method, nodeB);

            var subscribers = registry.findSubscribers("orders");

            assertEquals(1, subscribers.size());
            assertEquals(nodeB, subscribers.getFirst().nodeId());
        }
    }

    @Nested
    class SubscriptionRemove {
        @Test
        void onSubscriptionRemove_existingSubscription_noLongerFound() {
            registerSubscription("orders", artifact, method, nodeA);

            removeSubscription("orders", artifact, method);

            var subscribers = registry.findSubscribers("orders");
            assertTrue(subscribers.isEmpty());
        }

        @Test
        void onSubscriptionRemove_nonExistentKey_noError() {
            removeSubscription("orders", artifact, method);

            var subscribers = registry.findSubscribers("orders");
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
}
