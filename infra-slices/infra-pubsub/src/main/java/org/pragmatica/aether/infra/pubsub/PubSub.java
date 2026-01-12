package org.pragmatica.aether.infra.pubsub;

import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;
import java.util.Set;

/**
 * Simple topic-based publish/subscribe service.
 */
public interface PubSub extends Slice {
    /**
     * Publish a message to a topic.
     *
     * @param topic   Topic name
     * @param message Message to publish
     * @return Promise completing when message is published
     */
    Promise<Unit> publish(String topic, Message message);

    /**
     * Subscribe to a topic with a message handler.
     *
     * @param topic   Topic name
     * @param handler Message handler
     * @return Promise with subscription handle
     */
    Promise<Subscription> subscribe(String topic, Fn1<Promise<Unit>, Message> handler);

    /**
     * Unsubscribe from a topic.
     *
     * @param subscription Subscription to cancel
     * @return Promise completing when unsubscribed
     */
    Promise<Unit> unsubscribe(Subscription subscription);

    /**
     * Create a new topic.
     *
     * @param topic Topic name
     * @return Promise completing when topic is created
     */
    Promise<Unit> createTopic(String topic);

    /**
     * Delete a topic.
     *
     * @param topic Topic name
     * @return Promise completing when topic is deleted
     */
    Promise<Unit> deleteTopic(String topic);

    /**
     * List all topics.
     *
     * @return Promise with set of topic names
     */
    Promise<Set<String>> listTopics();

    /**
     * Factory method for in-memory implementation.
     *
     * @return PubSub instance
     */
    static PubSub inMemory() {
        return new InMemoryPubSub();
    }

    @Override
    default Promise<Unit> start() {
        return Promise.success(Unit.unit());
    }

    @Override
    default Promise<Unit> stop() {
        return Promise.success(Unit.unit());
    }

    @Override
    default List<SliceMethod< ?, ?>> methods() {
        return List.of();
    }
}
