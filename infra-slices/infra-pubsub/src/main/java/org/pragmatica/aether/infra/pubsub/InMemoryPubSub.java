package org.pragmatica.aether.infra.pubsub;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.pragmatica.lang.Unit.unit;

/**
 * In-memory implementation of PubSub for testing and single-node scenarios.
 */
final class InMemoryPubSub implements PubSub {
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<SubscriptionEntry>> subscriptions = new ConcurrentHashMap<>();
    private final Set<String> topics = ConcurrentHashMap.newKeySet();

    @Override
    public Promise<Unit> publish(String topic, Message message) {
        if (!topics.contains(topic)) {
            return new PubSubError.TopicNotFound(topic).promise();
        }
        var subscribers = subscriptions.get(topic);
        if (subscribers == null || subscribers.isEmpty()) {
            return Promise.success(unit());
        }
        return deliverToSubscribers(subscribers, message);
    }

    @Override
    public Promise<Subscription> subscribe(String topic, Fn1<Promise<Unit>, Message> handler) {
        if (!topics.contains(topic)) {
            return new PubSubError.TopicNotFound(topic).promise();
        }
        var subscriptionId = UUID.randomUUID()
                                 .toString();
        var entry = new SubscriptionEntry(subscriptionId, topic, handler);
        subscriptions.computeIfAbsent(topic,
                                      k -> new CopyOnWriteArrayList<>())
                     .add(entry);
        return Promise.success(createSubscription(entry));
    }

    @Override
    public Promise<Unit> unsubscribe(Subscription subscription) {
        var subscribers = subscriptions.get(subscription.topic());
        if (subscribers == null) {
            return new PubSubError.SubscriptionNotFound(subscription.subscriptionId()).promise();
        }
        var removed = subscribers.removeIf(e -> e.subscriptionId()
                                                 .equals(subscription.subscriptionId()));
        if (!removed) {
            return new PubSubError.SubscriptionNotFound(subscription.subscriptionId()).promise();
        }
        return Promise.success(unit());
    }

    @Override
    public Promise<Unit> createTopic(String topic) {
        if (!topics.add(topic)) {
            return new PubSubError.TopicAlreadyExists(topic).promise();
        }
        return Promise.success(unit());
    }

    @Override
    public Promise<Unit> deleteTopic(String topic) {
        if (!topics.remove(topic)) {
            return new PubSubError.TopicNotFound(topic).promise();
        }
        subscriptions.remove(topic);
        return Promise.success(unit());
    }

    @Override
    public Promise<Set<String>> listTopics() {
        return Promise.success(Set.copyOf(topics));
    }

    private Promise<Unit> deliverToSubscribers(CopyOnWriteArrayList<SubscriptionEntry> subscribers, Message message) {
        for (var entry : subscribers) {
            if (!entry.paused()
                      .get()) {
                entry.handler()
                     .apply(message);
            }
        }
        return Promise.success(unit());
    }

    private Subscription createSubscription(SubscriptionEntry entry) {
        return new InMemorySubscription(entry.subscriptionId(), entry.topic(), entry.paused());
    }

    private record SubscriptionEntry(String subscriptionId,
                                     String topic,
                                     Fn1<Promise<Unit>, Message> handler,
                                     AtomicBoolean paused) {
        SubscriptionEntry(String subscriptionId, String topic, Fn1<Promise<Unit>, Message> handler) {
            this(subscriptionId, topic, handler, new AtomicBoolean(false));
        }
    }

    private record InMemorySubscription(String subscriptionId,
                                        String topic,
                                        AtomicBoolean paused) implements Subscription {
        @Override
        public Promise<Unit> pause() {
            paused.set(true);
            return Promise.success(unit());
        }

        @Override
        public Promise<Unit> resume() {
            paused.set(false);
            return Promise.success(unit());
        }
    }
}
