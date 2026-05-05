// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.management.route;


/// One segment of a route path template after the route's literal prefix.
///
/// Routes are described as `prefix + segments` where the prefix is purely literal
/// (e.g. `/api/streams`) and the segments may interleave additional literals with
/// named parameters. This supports spec shapes such as
/// `/api/streams/{ns}/{stream}/{version}/groups/{group}` where `groups` is a literal
/// sandwiched between two parameter positions.
///
/// For backward compatibility, `Param` only routes (e.g. `/api/deploy/{deploymentId}`)
/// keep their existing behaviour: `paramCount()` returns the number of `Param` segments.
public sealed interface PathSegment {
    String text();

    boolean isParam();

    static PathSegment literal(String text) {
        return new Literal(text);
    }

    static PathSegment param(String name) {
        return new Param(name);
    }

    record Literal(String text) implements PathSegment {
        @Override public boolean isParam() {
            return false;
        }
    }

    record Param(String text) implements PathSegment {
        @Override public boolean isParam() {
            return true;
        }
    }
}
