// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
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
