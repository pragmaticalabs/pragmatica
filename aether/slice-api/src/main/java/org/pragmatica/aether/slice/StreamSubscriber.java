package org.pragmatica.aether.slice;

/// Marker type for stream subscription annotations.
///
/// Used with `@ResourceQualifier` on method annotations to declare stream consumers.
/// The annotated method receives stream events. Two delivery modes:
///
/// - **Single event:** method takes `T` -- called once per event.
/// - **Batch:** method takes `List<T>` -- called with a batch of events.
///
/// Batch size is configurable per consumer group in TOML (`batch-size`).
///
/// The method must return `Promise<Unit>`.
///
/// Example (single event):
/// ```{@code
/// @ResourceQualifier(type = StreamSubscriber.class, config = "streams.order-events")
/// @Retention(RUNTIME) @Target(METHOD)
/// public @interface OrderStreamConsumer {}
///
/// @OrderStreamConsumer
/// Promise<Unit> processOrder(OrderEvent event);
/// }```
///
/// Example (batch):
/// ```{@code
/// @OrderStreamConsumer
/// Promise<Unit> processOrderBatch(List<OrderEvent> events);
/// }```
public interface StreamSubscriber {}
