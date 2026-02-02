package org.pragmatica.aether.slice.serialization;

import org.pragmatica.lang.Promise;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

/**
 * Factory for obtaining serializer and deserializer instances for slice method invocations.
 * <p>
 * Implementations must be thread-safe and may use either:
 * - Singleton instances (if underlying implementation supports concurrent access)
 * - Pooled instances with FIFO asynchronous access protocol
 * <p>
 * Each obtained instance is used exactly once for a single serialization/deserialization operation.
 */
public interface SerializerFactory {
    /**
     * Obtains a serializer instance for use in a single method invocation.
     * <p>
     * The returned Promise resolves to a Serializer that will be used exactly once
     * and then released back to the pool (if pooled) or discarded (if singleton).
     *
     * @return Promise resolving to a Serializer instance
     */
    Promise<Serializer> serializer();

    /**
     * Obtains a deserializer instance for use in a single method invocation.
     * <p>
     * The returned Promise resolves to a Deserializer that will be used exactly once
     * and then released back to the pool (if pooled) or discarded (if singleton).
     *
     * @return Promise resolving to a Deserializer instance
     */
    Promise<Deserializer> deserializer();
}
