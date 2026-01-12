package org.pragmatica.aether.infra.feature;

import org.pragmatica.lang.Cause;

/**
 * Error hierarchy for feature flag operations.
 */
public sealed interface FeatureFlagError extends Cause {
    record FlagNotFound(String flag) implements FeatureFlagError {
        @Override
        public String message() {
            return "Feature flag not found: " + flag;
        }
    }

    record FlagAlreadyExists(String flag) implements FeatureFlagError {
        @Override
        public String message() {
            return "Feature flag already exists: " + flag;
        }
    }
}
