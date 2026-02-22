package org.pragmatica.aether.slice;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

/// Facade interface for resource provisioning within slice context.
///
/// This interface is a simplified view of ResourceProvider for use
/// in slice factory methods. The actual implementation delegates to
/// the full ResourceProvider.
///
/// Example usage:
/// ```{@code
/// ctx.resources().provide(SqlConnector.class, "database.primary")
/// }```
public interface ResourceProviderFacade {
    /// Provide a resource instance for the given type and configuration section.
    ///
    /// @param resourceType  The resource interface class
    /// @param configSection Dot-separated config section path
    /// @param <T>           Resource type
    /// @return Promise containing the resource instance or error
    <T> Promise<T> provide(Class<T> resourceType, String configSection);

    /// Provide a resource instance with additional provisioning context.
    ///
    /// The context carries type tokens and key extractors that resource factories
    /// may use for generic type handling or sharded resource creation.
    ///
    /// @param resourceType  The resource interface class
    /// @param configSection Dot-separated config section path
    /// @param context       Provisioning context with type tokens and key extractor
    /// @param <T>           Resource type
    /// @return Promise containing the resource instance or error
    <T> Promise<T> provide(Class<T> resourceType, String configSection, ProvisioningContext context);

    /// Release all resources associated with a given slice.
    ///
    /// Called during slice shutdown to clean up provisioned resources.
    /// Uses artifact coordinate string as slice identifier to avoid
    /// dependency on the artifact module.
    ///
    /// @param sliceId Artifact coordinate string identifying the slice
    /// @return Promise completing when all resources are released
    default Promise<Unit> releaseAll(String sliceId) {
        return Promise.unitPromise();
    }
}
