// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db;

/// Who shares a datasource's pool (#2973): `pool_scope` in the datasource section, not in `pool_config`.
///
/// - `NODE` (the default when absent): slices on one node whose datasource resolves to the same connection settings
///   share one pool per connector type.
/// - `SLICE`: this slice keeps a private pool, as before #2973.
///
/// Any other value fails the slice load; it never falls back to a default.
public enum PoolScope {
    NODE,
    SLICE
}
