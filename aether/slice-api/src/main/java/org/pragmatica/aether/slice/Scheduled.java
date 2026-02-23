package org.pragmatica.aether.slice;
/// Marker type for scheduled task annotations.
///
/// Used with `@ResourceQualifier` on method annotations to declare scheduled invocation.
/// The annotated method must take zero parameters and return `Promise<Unit>`.
///
/// Example:
/// ```{@code
/// @ResourceQualifier(type = Scheduled.class, config = "scheduling.cleanup")
/// @Retention(RUNTIME) @Target(METHOD)
/// public @interface CleanupSchedule {}
///
/// @CleanupSchedule
/// Promise<Unit> cleanupExpiredOrders();
/// }```
public interface Scheduled {}
