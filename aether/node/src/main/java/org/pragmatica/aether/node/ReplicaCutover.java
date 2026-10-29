// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

/// Whether a worker follows the replica feed (`LIVE`) or still reads the metadata projection (`DARK`). W3 ships the replica feed client dark: the
/// production value is `DARK`, the Ember test passes `LIVE` through the node factory, and W4 (the cutover, with the relay) deletes this type and
/// its factory. It is a value handed to node construction, not a configuration key: there is no operator switch.
public enum ReplicaCutover {
    DARK,
    LIVE;
    /// The value production builds nodes with. A method, not a `static final` literal: a literal would be inlined by the compiler and a
    /// test could not tell the wiring from the constant. `ReplicaCutoverGateTest` pins that this returns `DARK`.
    static ReplicaCutover production() {
        return DARK;
    }
    public boolean isLive() {
        return this == LIVE;
    }
}
