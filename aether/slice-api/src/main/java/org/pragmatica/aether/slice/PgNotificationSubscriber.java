package org.pragmatica.aether.slice;
/// Marker type for PostgreSQL LISTEN/NOTIFY subscription annotations.
///
/// Used with {@code @ResourceQualifier} on method annotations to declare notification handlers.
/// The annotated method receives {@link PgNotification} objects when notifications arrive
/// on any of the configured channels.
///
/// The method must accept a single {@link PgNotification} parameter and return {@code Promise<Unit>}.
///
/// Example:
/// ```{@code
/// @ResourceQualifier(type = PgNotificationSubscriber.class, config = "pg-notifications.order-events")
/// @Retention(RUNTIME) @Target(METHOD)
/// public @interface OnOrderChange {}
///
/// @OnOrderChange
/// Promise<Unit> handleNotification(PgNotification notification) {
///     log.info("Channel: {}, Payload: {}", notification.channel(), notification.payload());
///     return Promise.unitPromise();
/// }
/// }```
public interface PgNotificationSubscriber {}
