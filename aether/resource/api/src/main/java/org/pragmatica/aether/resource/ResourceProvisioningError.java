package org.pragmatica.aether.resource;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.some;


/// Error types for resource provisioning operations.
public sealed interface ResourceProvisioningError extends Cause {
    record FactoryNotFound(Class<?> resourceType) implements ResourceProvisioningError {
        public static FactoryNotFound factoryNotFound(Class<?> resourceType) {
            return new FactoryNotFound(resourceType);
        }

        @Override public String message() {
            return "No factory registered for resource type: " + resourceType.getName();
        }
    }

    static FactoryNotFound factoryNotFound(Class<?> resourceType) {
        return FactoryNotFound.factoryNotFound(resourceType);
    }

    record CreationFailed(Class<?> resourceType, String configSection, Cause underlying) implements ResourceProvisioningError {
        public static CreationFailed creationFailed(Class<?> resourceType, String configSection, Cause underlying) {
            return new CreationFailed(resourceType, configSection, underlying);
        }

        @Override public String message() {
            return "Failed to create " + resourceType.getSimpleName() + " from config '" + configSection + "': " + underlying.message();
        }

        @Override public Option<Cause> source() {
            return some(underlying);
        }
    }

    static CreationFailed creationFailed(Class<?> resourceType, String configSection, Cause underlying) {
        return CreationFailed.creationFailed(resourceType, configSection, underlying);
    }

    record ConfigLoadFailed(String configSection, Cause configError) implements ResourceProvisioningError {
        public static ConfigLoadFailed configLoadFailed(String configSection, Cause configError) {
            return new ConfigLoadFailed(configSection, configError);
        }

        @Override public String message() {
            return "Failed to load config for resource: " + configError.message();
        }

        @Override public Option<Cause> source() {
            return some(configError);
        }
    }

    static ConfigLoadFailed configLoadFailed(String configSection, Cause configError) {
        return ConfigLoadFailed.configLoadFailed(configSection, configError);
    }

    enum ConfigServiceNotAvailable implements ResourceProvisioningError {
        INSTANCE;
        @Override public String message() {
            return "ConfigService not available - call ConfigService.setInstance() first";
        }
    }
}
