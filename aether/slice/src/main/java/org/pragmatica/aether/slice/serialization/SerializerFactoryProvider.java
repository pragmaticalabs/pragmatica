package org.pragmatica.aether.slice.serialization;

import org.pragmatica.lang.Result;

import java.util.List;

/// Provider that creates SerializerFactory instances for loaded slices.
///
/// This is the "factory of factories" configured at runtime. During slice loading,
/// the runtime collects all serializable classes declared by the slice and
/// invokes this provider exactly once to create a per-slice SerializerFactory.
///
/// The created factory is then used for all method invocations on that slice instance.
@FunctionalInterface
public interface SerializerFactoryProvider {
    /// Creates a SerializerFactory for a slice based on its serializable classes.
    ///
    /// Called exactly once during slice loading with all classes declared by the
    /// slice's {@code serializableClasses()} method.
    ///
    /// The implementation may use this class information to:
    /// - Pre-register classes with the underlying serializer
    /// - Assign deterministic IDs for cross-slice compatibility
    /// - Configure type-specific serialization strategies
    ///
    /// @param serializableClasses All classes declared by the slice for serialization
    /// @param sliceClassLoader    The classloader for the slice
    ///
    /// @return A SerializerFactory instance for this slice, or failure on configuration error
    Result<SerializerFactory> createFactory(List<Class<?>> serializableClasses, ClassLoader sliceClassLoader);
}
