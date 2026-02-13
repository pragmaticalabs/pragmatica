package org.pragmatica.aether.slice;

/// Unified context for slice factory methods during slice creation.
///
/// Provides access to all services needed when creating a slice:
///
///   - {@link #invoker()} - For cross-slice method invocation (slice dependencies)
///   - {@link #resources()} - For infrastructure resource provisioning
///
///
/// Example usage in generated factory:
/// ```{@code
/// public static Promise<OrderRepository> orderRepository(Aspect<OrderRepository> aspect,
///                                                         SliceCreationContext ctx) {
///     return Promise.all(
///             ctx.resources().provide(DatabaseConnector.class, "database.primary"),
///             ctx.invoker().methodHandle("inventory:artifact", "check", ...),
///             ctx.invoker().methodHandle("inventory:artifact", "save", ...))
///         .map((db, checkHandle, saveHandle) -> {
///             var inventory = new inventoryService(checkHandle, saveHandle);
///             return aspect.apply(OrderRepository.orderRepository(db, inventory));
///         });
/// }
/// }```
///
/// This replaces the previous pattern where SliceInvokerFacade was passed directly.
/// The unified context enables parallel resolution of resources and dependencies
/// via Promise.all(), improving startup latency.
public interface SliceCreationContext {

    /// Get the slice invoker facade for cross-slice method invocation.
    ///
    /// Use this to obtain MethodHandle instances for calling methods on other slices.
    ///
    /// @return SliceInvokerFacade instance
    SliceInvokerFacade invoker();

    /// Get the resource provider for infrastructure resource provisioning.
    ///
    /// Use this to obtain infrastructure resources like DatabaseConnector,
    /// HttpClient, etc. based on configuration sections.
    ///
    /// @return ResourceProviderFacade instance
    ResourceProviderFacade resources();

    /// Create a SliceCreationContext with the given components.
    ///
    /// @param invoker   SliceInvokerFacade for cross-slice invocation
    /// @param resources ResourceProviderFacade for resource provisioning
    /// @return New SliceCreationContext
    static SliceCreationContext sliceCreationContext(SliceInvokerFacade invoker,
                                                      ResourceProviderFacade resources) {
        return new DefaultSliceCreationContext(invoker, resources);
    }
}

/// Default implementation of SliceCreationContext.
record DefaultSliceCreationContext(SliceInvokerFacade invoker,
                                    ResourceProviderFacade resources) implements SliceCreationContext {
}
