// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

/// The capability to write a `system:` stream (#1338). System streams are read-only to blueprints, slices and the
/// Management API; only the node's own system publishers write them, and they are the only holders of this token.
///
/// The constructor is private and the single instance is reachable only from this package
/// ([SystemStreamFactories] hands it to the publisher it builds), so no slice-facing factory can obtain one. A
/// [StreamWriteRouter] or [DefaultStreamPublisher] built without it refuses every write to a `system:` stream
/// with [StreamError.SystemStreamWriteRefused].
public final class SystemWriteGrant {
    static final SystemWriteGrant INSTANCE = new SystemWriteGrant();

    private SystemWriteGrant() {}
}
