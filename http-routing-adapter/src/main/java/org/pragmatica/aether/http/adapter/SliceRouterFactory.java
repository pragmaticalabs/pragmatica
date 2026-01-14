package org.pragmatica.aether.http.adapter;

import org.pragmatica.json.JsonMapper;

/**
 * Factory interface for creating {@link SliceRouter} instances for a specific slice type.
 * <p>
 * Implementations are discovered via {@link java.util.ServiceLoader} mechanism.
 * Each slice that exposes HTTP routes generates an implementation of this interface.
 * <p>
 * Usage:
 * <pre>{@code
 * ServiceLoader<SliceRouterFactory> loader = ServiceLoader.load(SliceRouterFactory.class);
 * var factory = loader.stream()
 *     .map(ServiceLoader.Provider::get)
 *     .filter(f -> f.sliceType() == UserService.class)
 *     .findFirst()
 *     .orElseThrow();
 *
 * var router = factory.create(userServiceImpl);
 * }</pre>
 *
 * @param <T> the slice interface type this factory creates routers for
 */
public interface SliceRouterFactory<T> {
    /**
     * Returns the slice interface class this factory handles.
     * <p>
     * Used by Aether to match factories to slice instances.
     *
     * @return the slice interface class
     */
    Class<T> sliceType();

    /**
     * Create a router for the given slice instance using default JSON mapper.
     * <p>
     * Default mapper is configured with Pragmatica types support.
     *
     * @param slice the slice implementation instance
     * @return configured SliceRouter ready to handle HTTP requests
     */
    SliceRouter create(T slice);

    /**
     * Create a router for the given slice instance with custom JSON mapper.
     *
     * @param slice      the slice implementation instance
     * @param jsonMapper custom JSON mapper for serialization/deserialization
     * @return configured SliceRouter ready to handle HTTP requests
     */
    SliceRouter create(T slice, JsonMapper jsonMapper);
}
