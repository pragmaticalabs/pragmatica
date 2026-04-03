package org.pragmatica.aether.resource.http;

public record JsonConfig(NamingStrategy naming, NullInclusion nullInclusion, boolean failOnUnknown) {
    public static JsonConfig jsonConfig() {
        return new JsonConfig(NamingStrategy.CAMEL_CASE, NullInclusion.NON_EMPTY, false);
    }

    public enum NamingStrategy {
        CAMEL_CASE,
        SNAKE_CASE,
        KEBAB_CASE
    }

    public enum NullInclusion {
        INCLUDE,
        EXCLUDE,
        NON_EMPTY
    }
}
