// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.config;

import org.pragmatica.lang.Cause;


public sealed interface ConfigError extends Cause {
    record KeyNotFound(String key, ConfigScope scope) implements ConfigError {
        @Override
        public String message() {
            return "Configuration key not found: " + key + " in scope " + scope;
        }
    }

    record ParseFailed(String location, String reason) implements ConfigError {
        @Override
        public String message() {
            return "Failed to parse configuration from " + location + ": " + reason;
        }
    }

    record ValidationFailed(String key, String reason) implements ConfigError {
        @Override
        public String message() {
            return "Configuration validation failed for " + key + ": " + reason;
        }
    }

    record WatchFailed(String key, String reason) implements ConfigError {
        @Override
        public String message() {
            return "Failed to watch configuration key " + key + ": " + reason;
        }
    }
}
