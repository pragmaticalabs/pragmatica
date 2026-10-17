// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.lang.Cause;


/// #2098: another caller is initializing the fleet inventory, so this provision cannot be counted yet. Transient by construction: a caller that
/// can wait (the node replacement reconciler) defers and tries again on its next tick instead of treating it as a refusal.
public record FleetInventoryInitializing() implements Cause {
    @Override
    public String message() {
        return "Fleet inventory initialization in progress";
    }
}
