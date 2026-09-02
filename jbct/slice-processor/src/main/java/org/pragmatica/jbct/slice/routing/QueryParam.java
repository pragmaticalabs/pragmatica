// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.jbct.slice.routing;

/// Query parameter extracted from route DSL.
///
/// @param name parameter name (e.g., "status" from "?status")
/// @param type parameter type (defaults to "String" if not specified)
public record QueryParam(String name, String type) {
    private static final String DEFAULT_TYPE = "String";

    public static QueryParam queryParam(String name) {
        return new QueryParam(name, DEFAULT_TYPE);
    }

    public static QueryParam queryParam(String name, String type) {
        return new QueryParam(name, type);
    }
}
