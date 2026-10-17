// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge.api;

import org.pragmatica.lang.Option;


/// #1105: the credential Forge presents to the embedded nodes it proxies to — the ADMIN-capable key
/// `ForgeServer` picks from a sibling `aether.toml`, or none when the nodes run without API keys. Read
/// at request time, because the key is resolved after the routes are built.
@FunctionalInterface
public interface OperatorKey {
    Option<String> current();

    static OperatorKey none() {
        return Option::none;
    }
}
