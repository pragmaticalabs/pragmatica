package org.pragmatica.aether.resource;

import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;


/// Provider for infrastructure resources based on configuration.
///
/// ResourceProvider uses SPI-discovered {@link ResourceFactory} implementations
/// to create resources from configuration sections. It caches created instances
/// by (resourceType, configSection) key.
///
/// Example usage in generated slice factory:
/// ```{@code
/// return Promise.all(
///         ctx.resources().provide(SqlConnector.class, "database.primary"),
///         ctx.invoker().methodHandle("inventory:artifact", "check", ...))
///     .map((db, checkHandle) -> {
///         var inventory = new inventoryService(checkHandle);
///         return aspect.apply(OrderRepository.orderRepository(db, inventory));
///     });
/// }```
public interface ResourceProvider {
    <T> Promise<T> provide(Class<T> resourceType, String configSection);
    <T> Promise<T> provide(Class<T> resourceType, String configSection, ProvisioningContext context);
    boolean hasFactory(Class<?> resourceType);
    Promise<Unit> releaseAll(String sliceId);

    static Option<ResourceProvider> instance() {
        return ResourceProviderHolder.instance();
    }

    static Result<Unit> setInstance(ResourceProvider provider) {
        return ResourceProviderHolder.setInstance(provider);
    }

    static Result<Unit> clear() {
        return ResourceProviderHolder.clear();
    }

    static ResourceProvider resourceProvider() {
        return SpiResourceProvider.spiResourceProvider();
    }
}
