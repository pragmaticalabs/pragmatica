package org.pragmatica.aether.slice.serialization;

import org.pragmatica.lang.type.TypeToken;

import java.util.List;

/**
 * Provider that creates SerializerFactory instances for loaded slices.
 * <p>
 * This is the "factory of factories" configured at runtime. During slice loading,
 * the runtime collects all TypeTokens from the slice's method declarations and
 * invokes this provider exactly once to create a per-slice SerializerFactory.
 * <p>
 * The created factory is then used for all method invocations on that slice instance.
 */
@FunctionalInterface
public interface SerializerFactoryProvider {
    /**
     * Creates a SerializerFactory for a slice based on the types used in its methods.
     * <p>
     * Called exactly once during slice loading with all TypeTokens collected from
     * parameter types and return types of all methods exposed by the slice.
     * <p>
     * The implementation may use this type information to:
     * - Pre-register classes with the underlying serializer
     * - Optimize serialization for known types
     * - Configure type-specific serialization strategies
     *
     * @param typeTokens All TypeTokens from slice method parameters and return types
     *
     * @return A SerializerFactory instance for this slice
     */
    SerializerFactory createFactory(List<TypeToken<?>> typeTokens);
}
