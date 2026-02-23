package org.pragmatica.aether.invoke;

import org.pragmatica.aether.endpoint.TopicSubscriptionRegistry;
import org.pragmatica.aether.endpoint.TopicSubscriptionRegistry.TopicSubscriber;
import org.pragmatica.aether.slice.Publisher;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.type.TypeToken;

import java.util.ArrayList;

/// Publisher implementation that routes messages to topic subscribers
/// via SliceInvoker for reliable delivery.
///
/// Each subscriber is invoked using request-response (expectResponse=true)
/// to ensure reliable delivery with retry/failover.
public record TopicPublisher<T>(String topicName,
                                TopicSubscriptionRegistry registry,
                                SliceInvoker invoker) implements Publisher<T> {
    private static final TypeToken<Unit> UNIT_TYPE_TOKEN = new TypeToken<>() {};

    @Override
    public Promise<Unit> publish(T message) {
        var subscribers = registry.findSubscribers(topicName);
        if (subscribers.isEmpty()) {
            return Promise.unitPromise();
        }
        var deliveries = new ArrayList<Promise<Unit>>(subscribers.size());
        for (var subscriber : subscribers) {
            deliveries.add(deliverToSubscriber(subscriber, message));
        }
        return Promise.allOf(deliveries)
                      .map(_ -> Unit.unit());
    }

    private Promise<Unit> deliverToSubscriber(TopicSubscriber subscriber, T message) {
        return invoker.invoke(subscriber.artifact(), subscriber.methodName(), message, UNIT_TYPE_TOKEN);
    }
}
