package org.pragmatica.aether.resource.http;
/// JSON serialization configuration for HTTP client.
/// Pure data record — no Jackson imports. Enums map to Jackson config in the implementation module.
///
/// @param naming        Property naming strategy
/// @param nullInclusion Null value inclusion policy
/// @param failOnUnknown Whether to fail on unknown properties during deserialization
public record JsonConfig(NamingStrategy naming,
                         NullInclusion nullInclusion,
                         boolean failOnUnknown) {
    /// Creates default JSON configuration: camelCase, NON_EMPTY, don't fail on unknown.
    public static JsonConfig jsonConfig() {
        return new JsonConfig(NamingStrategy.CAMEL_CASE, NullInclusion.NON_EMPTY, false);
    }

    /// Property naming strategy for JSON serialization.
    public enum NamingStrategy {
        CAMEL_CASE,
        SNAKE_CASE,
        KEBAB_CASE
    }

    /// Null value inclusion policy for JSON serialization.
    public enum NullInclusion {
        INCLUDE,
        EXCLUDE,
        NON_EMPTY
    }
}
