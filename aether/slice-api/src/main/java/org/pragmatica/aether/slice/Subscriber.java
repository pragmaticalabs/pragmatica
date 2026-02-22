package org.pragmatica.aether.slice;
/// Marker type for topic subscription annotations.
///
/// Used with `@ResourceQualifier` on method annotations to declare subscription handlers.
/// The annotated method receives topic messages and must return `Promise<Unit>`.
///
/// Example:
/// ```{@code
/// @ResourceQualifier(type = Subscriber.class, config = "messaging.orders")
/// @Retention(RUNTIME) @Target(METHOD)
/// public @interface OrderSubscription {}
///
/// @OrderSubscription
/// Promise<Unit> handleOrderEvent(OrderEvent event);
/// }```
public interface Subscriber {}
