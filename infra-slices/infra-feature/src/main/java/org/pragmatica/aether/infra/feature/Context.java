package org.pragmatica.aether.infra.feature;

import org.pragmatica.lang.Option;

import java.util.Map;

/**
 * Context for evaluating feature flags.
 * Contains attributes that can be used for targeting (user ID, region, etc.).
 *
 * @param attributes Context attributes
 */
public record Context(Map<String, String> attributes) {
    /**
     * Create a context with a single attribute.
     *
     * @param key   Attribute key
     * @param value Attribute value
     * @return New context
     */
    public static Context context(String key, String value) {
        return new Context(Map.of(key, value));
    }

    /**
     * Create a context with multiple attributes.
     *
     * @param attributes Attribute map
     * @return New context
     */
    public static Context context(Map<String, String> attributes) {
        return new Context(Map.copyOf(attributes));
    }

    /**
     * Create an empty context.
     *
     * @return Empty context
     */
    public static Context empty() {
        return new Context(Map.of());
    }

    /**
     * Get an attribute value.
     *
     * @param key Attribute key
     * @return Optional attribute value
     */
    public Option<String> get(String key) {
        return Option.option(attributes.get(key));
    }
}
