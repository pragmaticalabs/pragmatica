// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
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
