package org.pragmatica.aether.endpoint;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.kvstore.AetherKey.TopicSubscriptionKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.TopicSubscriptionValue;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageReceiver;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Passive KV-Store watcher maintaining a local cache of topic subscriptions.
///
///
/// Tracks which slice methods subscribe to which topics. Used by TopicPublisher
/// to discover and invoke subscriber methods. Supports competing consumers:
/// when multiple instances of the same artifact subscribe to a topic,
/// only one instance per artifact receives each message (round-robin).
public interface TopicSubscriptionRegistry {
    @MessageReceiver
    @SuppressWarnings("JBCT-RET-01") // MessageReceiver callback — void required by messaging framework
    void onSubscriptionPut(ValuePut<TopicSubscriptionKey, TopicSubscriptionValue> valuePut);

    @MessageReceiver
    @SuppressWarnings("JBCT-RET-01") // MessageReceiver callback — void required by messaging framework
    void onSubscriptionRemove(ValueRemove<TopicSubscriptionKey, TopicSubscriptionValue> valueRemove);

    /// Find all subscribers for a topic, selecting one node per (artifact, method) group.
    List<TopicSubscriber> findSubscribers(String topicName);

    /// All registered subscriptions (for monitoring).
    List<TopicSubscription> allSubscriptions();

    /// A subscription entry in the registry.
    record TopicSubscription(String topicName, Artifact artifact, MethodName methodName, NodeId nodeId) {
        public TopicSubscriptionKey toKey() {
            return TopicSubscriptionKey.topicSubscriptionKey(topicName, artifact, methodName);
        }
    }

    /// A selected subscriber for message delivery (one per artifact+method group).
    record TopicSubscriber(Artifact artifact, MethodName methodName, NodeId nodeId) {}

    /// Create a new topic subscription registry.
    static TopicSubscriptionRegistry topicSubscriptionRegistry() {
        record topicSubscriptionRegistry(Map<TopicSubscriptionKey, TopicSubscription> subscriptions,
                                         Map<String, AtomicInteger> roundRobinCounters) implements TopicSubscriptionRegistry {
            private static final Logger log = LoggerFactory.getLogger(topicSubscriptionRegistry.class);

            @Override
            @SuppressWarnings("JBCT-RET-01")
            public void onSubscriptionPut(ValuePut<TopicSubscriptionKey, TopicSubscriptionValue> valuePut) {
                var key = valuePut.cause()
                                  .key();
                var value = valuePut.cause()
                                    .value();
                var subscription = new TopicSubscription(key.topicName(),
                                                         key.artifact(),
                                                         key.methodName(),
                                                         value.nodeId());
                subscriptions.put(key, subscription);
                log.debug("Registered topic subscription: {}", subscription);
            }

            @Override
            @SuppressWarnings("JBCT-RET-01")
            public void onSubscriptionRemove(ValueRemove<TopicSubscriptionKey, TopicSubscriptionValue> valueRemove) {
                var key = valueRemove.cause()
                                     .key();
                Option.option(subscriptions.remove(key))
                      .onPresent(removed -> log.debug("Unregistered topic subscription: {}", removed));
            }

            @Override
            public List<TopicSubscriber> findSubscribers(String topicName) {
                var groups = new LinkedHashMap<String, List<TopicSubscription>>();
                for (var sub : subscriptions.values()) {
                    if (sub.topicName()
                           .equals(topicName)) {
                        var groupKey = sub.artifact()
                                          .asString() + "/" + sub.methodName()
                                                                .name();
                        groups.computeIfAbsent(groupKey,
                                               _ -> new ArrayList<>())
                              .add(sub);
                    }
                }
                var result = new ArrayList<TopicSubscriber>();
                for (var entry : groups.entrySet()) {
                    var group = entry.getValue();
                    group.sort(Comparator.comparing(s -> s.nodeId()
                                                          .id()));
                    var counter = roundRobinCounters.computeIfAbsent(entry.getKey(), _ -> new AtomicInteger(0));
                    var index = (counter.getAndIncrement() & 0x7FFFFFFF) % group.size();
                    var selected = group.get(index);
                    result.add(new TopicSubscriber(selected.artifact(), selected.methodName(), selected.nodeId()));
                }
                return result;
            }

            @Override
            public List<TopicSubscription> allSubscriptions() {
                return List.copyOf(subscriptions.values());
            }
        }
        return new topicSubscriptionRegistry(new ConcurrentHashMap<>(), new ConcurrentHashMap<>());
    }
}
