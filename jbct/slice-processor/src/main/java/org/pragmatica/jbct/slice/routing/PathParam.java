// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.jbct.slice.routing;
/// Path parameter extracted from route DSL.
///
/// @param name     parameter name (e.g., "id" from "{id}")
/// @param type     parameter type (defaults to "String" if not specified)
/// @param position zero-based position in the path
public record PathParam(String name,
                        String type,
                        int position) {
    private static final String DEFAULT_TYPE = "String";

    public static PathParam pathParam(String name, int position) {
        return new PathParam(name, DEFAULT_TYPE, position);
    }

    public static PathParam pathParam(String name, String type, int position) {
        return new PathParam(name, type, position);
    }
}
