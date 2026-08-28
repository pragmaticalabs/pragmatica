// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.management.route;

/// Ordered path-shape token: a literal segment ("spacer", mirroring the vocabulary of
/// [org.pragmatica.http.routing.PathParameter#spacer]) or a named parameter slot.
///
/// The old model -- a flat literal `prefix` followed by trailing `paramNames` -- can only express
/// "literals first, then all params." It cannot express a literal segment after or between params,
/// which is exactly the shape identity-first stream routes need
/// (management-api-versioning-spec.md Sections 3.2/3.3, e.g. `/streams/{ns}/{stream}/{ver}/tail`).
/// A token sequence generalizes the old shape (literal-run followed by param-run is just one
/// possible token ordering) while allowing arbitrary interleaving.
sealed interface PathToken {
    record Spacer(String text) implements PathToken {}

    record Param(String name) implements PathToken {}

    static PathToken spacer(String text) {
        return new Spacer(text);
    }

    static PathToken param(String name) {
        return new Param(name);
    }
}
