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
    <T> Promise<T> provide(Class<T> resourceType, String configSection);
    <T> Promise<T> provide(Class<T> resourceType, String configSection, ProvisioningContext context);

    default Promise<Unit> releaseAll(String sliceId) {
        return Promise.unitPromise();
    }
}
