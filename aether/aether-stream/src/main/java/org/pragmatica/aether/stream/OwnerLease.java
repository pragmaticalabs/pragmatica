// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

/// What the owner checks of #2981 (design §2.2) read: whether this node's lease is valid at this instant. Production binds the
/// node's [NodeLease]; [#UNWIRED] is the explicit default of a manager whose lease seam was never bound, compared BY IDENTITY:
/// with both the lease and the ownership-record seam unwired the pre-lease owner rule applies (unwired test fixtures), a
/// half-wired manager fails closed, and production refuses to start with either seam unwired (`StreamAssembly`).
@FunctionalInterface
public interface OwnerLease {
    /// Whether the lease is valid now. Reads the clock first, then the lease state; never cached.
    boolean valid();
    /// The unbound default. Never valid, so a half-wired manager that reaches it fails closed.
    OwnerLease UNWIRED = () -> false;
    /// The fixed test-scope "valid" lease: the one object every unit fixture that wires the ownership-record seam passes as its lease.
    /// Production never binds it; the boot check in `StreamAssembly` rejects it BY IDENTITY, like [#UNWIRED]. Tests only.
    OwnerLease TEST_VALID = () -> true;
}
