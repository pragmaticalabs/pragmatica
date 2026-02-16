package org.pragmatica.aether.slice;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.type.TypeToken;

import java.util.ArrayList;
import java.util.List;

/// Context for resource provisioning, carrying additional type and key information.
///
/// Used to pass extra metadata to {@link ResourceProviderFacade#provide} when
/// the resource factory needs type tokens (e.g., for generic types) or
/// key extractors (e.g., for sharded resources).
///
/// Example usage:
/// ```{@code
/// var context = ProvisioningContext.provisioningContext()
///     .withTypeToken(new TypeToken<List<Order>>() {})
///     .withKeyExtractor(Order::customerId);
///
/// ctx.resources().provide(EventStore.class, "events.orders", context);
/// }```
public record ProvisioningContext(List<TypeToken<?>> typeTokens,
                                  Option<Fn1<?, ?>> keyExtractor) {
    /// Create an empty provisioning context.
    ///
    /// @return New empty ProvisioningContext
    public static ProvisioningContext provisioningContext() {
        return new ProvisioningContext(List.of(), Option.none());
    }

    /// Add a type token to this context.
    ///
    /// @param token Type token to add
    /// @return New ProvisioningContext with the added type token
    public ProvisioningContext withTypeToken(TypeToken<?> token) {
        var tokens = new ArrayList<>(typeTokens);
        tokens.add(token);
        return new ProvisioningContext(List.copyOf(tokens), keyExtractor);
    }

    /// Set the key extractor for this context.
    ///
    /// @param extractor Key extractor function
    /// @return New ProvisioningContext with the key extractor set
    public ProvisioningContext withKeyExtractor(Fn1<?, ?> extractor) {
        return new ProvisioningContext(typeTokens, Option.some(extractor));
    }
}
