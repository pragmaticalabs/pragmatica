package org.pragmatica.aether.infra.pubsub;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.Unit.unit;

/// In-memory implementation of PubSub for testing and single-node scenarios.
final class InMemoryPubSub implements PubSub {
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<SubscriptionEntry>> subscriptions = new ConcurrentHashMap<>();
    private final Set<String> topics = ConcurrentHashMap.newKeySet();

    @Override
    public Promise<Unit> publish(String topic, Message message) {
        if (topicMissing(topic)) {
            return new PubSubError.TopicNotFound(topic).promise();
        }
        var subs = option(subscriptions.get(topic));
        return subs.filter(list -> !list.isEmpty())
                   .map(list -> deliverToSubscribers(list, message))
                   .or(Promise.success(unit()));
    }

    @Override
    public Promise<Subscription> subscribe(String topic, Fn1<Promise<Unit>, Message> handler) {
        if (topicMissing(topic)) {
            return new PubSubError.TopicNotFound(topic).promise();
        }
        var subscriptionId = UUID.randomUUID()
                                 .toString();
        var entry = SubscriptionEntry.subscriptionEntry(subscriptionId, topic, handler)
                                     .unwrap();
        subscriptions.computeIfAbsent(topic,
                                      _ -> new CopyOnWriteArrayList<>())
                     .add(entry);
        return Promise.success(buildSubscription(entry));
    }

    @Override
    public Promise<Unit> unsubscribe(Subscription subscription) {
        var subId = subscription.subscriptionId();
        var topicSubs = option(subscriptions.get(subscription.topic()));
        return topicSubs.toResult(new PubSubError.SubscriptionNotFound(subId))
                        .flatMap(subs -> removeSubscription(subs, subId))
                        .async();
    }

    private static Result<Unit> removeSubscription(CopyOnWriteArrayList<SubscriptionEntry> subs, String subId) {
        var removed = subs.removeIf(e -> e.subscriptionId()
                                          .equals(subId));
        if (removed) {
            return success(unit());
        }
        return new PubSubError.SubscriptionNotFound(subId).result();
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

    private boolean topicMissing(String topic) {
        return ! topics.contains(topic);
    }

    private static boolean isActive(SubscriptionEntry entry) {
        return ! entry.paused()
                     .get();
    }

    private static void deliverMessage(SubscriptionEntry entry, Message message) {
        entry.handler()
             .apply(message);
    }

    private Promise<Unit> deliverToSubscribers(CopyOnWriteArrayList<SubscriptionEntry> subscribers, Message message) {
        subscribers.stream()
                   .filter(InMemoryPubSub::isActive)
                   .map(SubscriptionEntry::handler)
                   .forEach(handler -> handler.apply(message));
        return Promise.success(unit());
    }

    private static Subscription buildSubscription(SubscriptionEntry entry) {
        return InMemorySubscription.inMemorySubscription(entry.subscriptionId(),
                                                         entry.topic(),
                                                         entry.paused())
                                   .unwrap();
    }

    private record SubscriptionEntry(String subscriptionId,
                                     String topic,
                                     Fn1<Promise<Unit>, Message> handler,
                                     AtomicBoolean paused) {
        SubscriptionEntry(String subscriptionId, String topic, Fn1<Promise<Unit>, Message> handler) {
            this(subscriptionId, topic, handler, new AtomicBoolean(false));
        }

        static Result<SubscriptionEntry> subscriptionEntry(String subscriptionId,
                                                           String topic,
                                                           Fn1<Promise<Unit>, Message> handler) {
            return success(new SubscriptionEntry(subscriptionId, topic, handler));
        }
    }

    private record InMemorySubscription(String subscriptionId,
                                        String topic,
                                        AtomicBoolean paused) implements Subscription {
        static Result<InMemorySubscription> inMemorySubscription(String subscriptionId,
                                                                 String topic,
                                                                 AtomicBoolean paused) {
            return success(new InMemorySubscription(subscriptionId, topic, paused));
        }

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
