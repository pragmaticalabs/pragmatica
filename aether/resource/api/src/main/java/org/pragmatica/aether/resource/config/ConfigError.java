// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
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

    /// #1455: a key that is present but not of the requested type is a failed read, not an absent key.
    record TypeMismatch(String key, String expected, String raw) implements ConfigError {
        @Override
        public String message() {
            return "Configuration value for " + key + " is not " + expected + ": \"" + raw + "\"";
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
