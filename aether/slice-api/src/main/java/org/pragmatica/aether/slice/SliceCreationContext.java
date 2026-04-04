package org.pragmatica.aether.slice;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;


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
///             ctx.resources().provide(SqlConnector.class, "database.primary"),
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
    SliceInvokerFacade invoker();
    ResourceProviderFacade resources();
    ConfigFacade config();

    default Option<String> sliceId() {
        return none();
    }

    static SliceCreationContext sliceCreationContext(SliceInvokerFacade invoker, ResourceProviderFacade resources) {
        return DefaultSliceCreationContext.defaultSliceCreationContext(invoker, resources, none(), NoOpConfigFacade.INSTANCE).unwrap();
    }

    static SliceCreationContext sliceCreationContext(SliceInvokerFacade invoker,
                                                     ResourceProviderFacade resources,
                                                     String sliceId) {
        return DefaultSliceCreationContext.defaultSliceCreationContext(invoker, resources, some(sliceId), NoOpConfigFacade.INSTANCE).unwrap();
    }

    static SliceCreationContext sliceCreationContext(SliceInvokerFacade invoker,
                                                     ResourceProviderFacade resources,
                                                     String sliceId,
                                                     ConfigFacade config) {
        return DefaultSliceCreationContext.defaultSliceCreationContext(invoker, resources, some(sliceId), config).unwrap();
    }

    static SliceCreationContext sliceCreationContext(SliceInvokerFacade invoker,
                                                     ResourceProviderFacade resources,
                                                     ConfigFacade config) {
        return DefaultSliceCreationContext.defaultSliceCreationContext(invoker, resources, none(), config).unwrap();
    }
}

/// Default implementation of SliceCreationContext.
record DefaultSliceCreationContext(SliceInvokerFacade invoker,
                                   ResourceProviderFacade resources,
                                   Option<String> sliceId,
                                   ConfigFacade config) implements SliceCreationContext {
    static Result<DefaultSliceCreationContext> defaultSliceCreationContext(SliceInvokerFacade invoker,
                                                                           ResourceProviderFacade resources,
                                                                           Option<String> sliceId,
                                                                           ConfigFacade config) {
        return success(new DefaultSliceCreationContext(invoker, resources, sliceId, config));
    }
}
