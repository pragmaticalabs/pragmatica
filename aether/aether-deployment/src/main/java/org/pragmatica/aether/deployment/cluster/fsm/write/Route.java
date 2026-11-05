// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm.write;

/// How a [WriteSite] recovers from a failed write (#2584).
///
/// `LEGACY`: the site keeps the failure handling it had before the write path was shared. `PASS`: a reconcile pass
/// re-derives the write from committed state, so a lost write is re-issued by the next tick.
public enum Route {
    LEGACY,
    PASS
}
