// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.entity.pg;

/// A decoded state together with the row version it was read at.
///
/// @param version the row version; 1 after create, +1 for every committed update
/// @param state   the decoded state
public record Versioned<S>(long version, S state) {
    public static <S> Versioned<S> versioned(long version, S state) {
        return new Versioned<>(version, state);
    }
}
