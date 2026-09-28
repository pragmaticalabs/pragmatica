// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.some;


public sealed interface ResourceProvisioningError extends Cause {
    record FactoryNotFound(Class<?> resourceType) implements ResourceProvisioningError {
        public static FactoryNotFound factoryNotFound(Class<?> resourceType) {
            return new FactoryNotFound(resourceType);
        }

        @Override
        public String message() {
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

        @Override
        public String message() {
            return "Failed to create " + resourceType.getSimpleName()
                 + " from config '" + configSection
                 + "': " + underlying.message();
        }

        @Override
        public Option<Cause> source() {
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

        @Override
        public String message() {
            return "Failed to load config for resource: " + configError.message();
        }

        @Override
        public Option<Cause> source() {
            return some(configError);
        }
    }

    static ConfigLoadFailed configLoadFailed(String configSection, Cause configError) {
        return ConfigLoadFailed.configLoadFailed(configSection, configError);
    }

    /// A factory that binds its own section ([ResourceFactory#sectionBinder()]) was asked to provision with no
    /// slice configuration provider in the context (#1549). Refused rather than handed to the generic record
    /// binder, which reads different key spellings and defaults whatever it does not find — the silent path
    /// #1549 closed. Reachable only when a slice is loaded without a slice-composite
    /// (`SliceLoadingContext.setSliceComposite` never called, e.g. a node started without a node-composite),
    /// or through the context-free `provide(type, section)` overload.
    record SectionBinderNeedsProvider(Class<?> resourceType, String configSection) implements ResourceProvisioningError {
        @Override
        public String message() {
            return resourceType.getSimpleName()
                 + " binds [" + configSection
                 + "] from the slice's configuration provider, and none is available — refusing rather than binding defaults";
        }
    }

    enum ConfigServiceNotAvailable implements ResourceProvisioningError {
        INSTANCE;
        @Override
        public String message() {
            return "ConfigService not available - call ConfigService.setInstance() first";
        }
    }
}
