package org.pragmatica.aether.config.cluster;

import org.pragmatica.lang.Option;

/// Runtime configuration for Aether node execution.
///
/// @param type runtime type (container or jvm)
/// @param image container image reference (required when type is CONTAINER)
/// @param jvmArgs optional extra JVM arguments
public record RuntimeConfig(RuntimeType type,
                            Option<String> image,
                            Option<String> jvmArgs) {
    /// Factory method with defaults.
    public static RuntimeConfig runtimeConfig(RuntimeType type, Option<String> image, Option<String> jvmArgs) {
        return new RuntimeConfig(type, image, jvmArgs);
    }

    /// Default runtime config (container, no image).
    public static RuntimeConfig defaultRuntimeConfig() {
        return new RuntimeConfig(RuntimeType.CONTAINER, Option.none(), Option.none());
    }
}
