package org.pragmatica.aether.slice;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.lang.utils.Causes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;


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
                                  Option<Fn1<?, ?>> keyExtractor,
                                  Map<Class<?>, Object> extensions) {
    private static final Fn1<Cause, String> MISSING_EXTENSION = Causes.forOneValue("Context does not contain %s");

    public static ProvisioningContext provisioningContext() {
        return new ProvisioningContext(List.of(), none(), Map.of());
    }

    public ProvisioningContext withTypeToken(TypeToken<?> token) {
        var tokens = new ArrayList<>(typeTokens);
        tokens.add(token);
        return new ProvisioningContext(List.copyOf(tokens), keyExtractor, extensions);
    }

    public ProvisioningContext withKeyExtractor(Fn1<?, ?> extractor) {
        return new ProvisioningContext(typeTokens, some(extractor), extensions);
    }

    @SuppressWarnings("unchecked") public <T> Result<T> extension(Class<T> type) {
        return option((T) extensions.get(type)).toResult(MISSING_EXTENSION.apply(type.getSimpleName()));
    }

    public <T> ProvisioningContext withExtension(Class<T> type, T value) {
        var newExtensions = new HashMap<>(extensions);
        newExtensions.put(type, value);
        return new ProvisioningContext(typeTokens, keyExtractor, Map.copyOf(newExtensions));
    }
}
