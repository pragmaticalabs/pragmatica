package org.pragmatica.aether.infra;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

/// Provider for infrastructure resources based on configuration.
///
/// ResourceProvider uses SPI-discovered {@link ResourceFactory} implementations
/// to create resources from configuration sections. It caches created instances
/// by (resourceType, configSection) key.
///
/// Example usage in generated slice factory:
/// ```{@code
/// return Promise.all(
///         ctx.resources().provide(DatabaseConnector.class, "database.primary"),
///         ctx.invoker().methodHandle("inventory:artifact", "check", ...))
///     .map((db, checkHandle) -> {
///         var inventory = new inventoryService(checkHandle);
///         return aspect.apply(OrderRepository.orderRepository(db, inventory));
///     });
/// }```
public interface ResourceProvider {
    /// Provide a resource instance for the given type and configuration section.
    ///
    /// If an instance has already been created for this (type, configSection) pair,
    /// the cached instance is returned. Otherwise, the configuration is loaded
    /// and a new instance is created via the appropriate {@link ResourceFactory}.
    ///
    /// @param resourceType  The resource interface class (e.g., DatabaseConnector.class)
    /// @param configSection Dot-separated config section path (e.g., "database.primary")
    /// @param <T>           Resource type
    /// @return Promise containing the resource instance or error
    <T> Promise<T> provide(Class<T> resourceType, String configSection);

    /// Check if a factory is registered for the given resource type.
    ///
    /// @param resourceType The resource interface class
    /// @return true if a factory is registered
    boolean hasFactory(Class<?> resourceType);

    // Static accessor pattern
    /// Get the global ResourceProvider instance.
    ///
    /// @return ResourceProvider if configured, empty otherwise
    static Option<ResourceProvider> instance() {
        return ResourceProviderHolder.instance();
    }

    /// Set the global ResourceProvider instance.
    ///
    /// Called by AetherNode during startup.
    ///
    /// @param provider ResourceProvider implementation
    static void setInstance(ResourceProvider provider) {
        ResourceProviderHolder.setInstance(provider);
    }

    /// Clear the global ResourceProvider instance.
    ///
    /// Called during shutdown or in tests.
    static void clear() {
        ResourceProviderHolder.clear();
    }

    /// Create a default SPI-based ResourceProvider.
    ///
    /// Discovers factories via ServiceLoader.
    ///
    /// @return New ResourceProvider instance
    static ResourceProvider resourceProvider() {
        return SpiResourceProvider.spiResourceProvider();
    }
}
