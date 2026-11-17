// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

/// #686: a refusal the management API renders with a machine-readable `code` member in its problem body, so a client
/// classifies it by that code rather than by the HTTP status alone (a 409 is both the definitive `RouteMismatch` and the
/// transient "current core leader required").
///
/// The code is part of the wire contract: renaming it breaks every client that classifies on it.
public interface TypedRefusal {
    String code();
}
