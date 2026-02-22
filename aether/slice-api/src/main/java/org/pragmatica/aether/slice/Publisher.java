package org.pragmatica.aether.slice;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

/// Functional interface for publishing messages to a topic.
///
/// Provisioned via `@ResourceQualifier(type = Publisher.class, config = "messaging.xxx")`
/// on a slice factory method parameter. The runtime creates a `TopicPublisher` that
/// routes messages to subscriber methods registered for the same topic.
///
/// Example:
/// ```{@code
/// @ResourceQualifier(type = Publisher.class, config = "messaging.orders")
/// @Retention(RUNTIME) @Target(PARAMETER)
/// public @interface OrderPublisher {}
///
/// static OrderService orderService(@OrderPublisher Publisher<OrderEvent> pub) { ... }
/// }```
@FunctionalInterface
public interface Publisher<T> {
    Promise<Unit> publish(T message);
}
