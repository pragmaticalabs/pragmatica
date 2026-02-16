package org.pragmatica.aether.slice;

import org.pragmatica.lang.Promise;

/// Facade interface for resource provisioning within slice context.
///
/// This interface is a simplified view of ResourceProvider for use
/// in slice factory methods. The actual implementation delegates to
/// the full ResourceProvider.
///
/// Example usage:
/// ```{@code
/// ctx.resources().provide(DatabaseConnector.class, "database.primary")
/// }```
public interface ResourceProviderFacade {
    /// Provide a resource instance for the given type and configuration section.
    ///
    /// @param resourceType  The resource interface class
    /// @param configSection Dot-separated config section path
    /// @param <T>           Resource type
    /// @return Promise containing the resource instance or error
    <T> Promise<T> provide(Class<T> resourceType, String configSection);
}
