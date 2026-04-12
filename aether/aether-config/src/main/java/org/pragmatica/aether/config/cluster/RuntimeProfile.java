package org.pragmatica.aether.config.cluster;

import org.pragmatica.lang.Option;

/// Runtime profile definition. S5.2
public record RuntimeProfile(String name, RuntimeType type, Option<String> image, Option<String> jvmArgs) {
    public static RuntimeProfile runtimeProfile(String name, RuntimeType type, Option<String> image, Option<String> jvmArgs) {
        return new RuntimeProfile(name, type, image, jvmArgs);
    }
}
