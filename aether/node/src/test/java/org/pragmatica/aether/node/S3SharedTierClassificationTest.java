// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #2103: a shared tier that could not be admitted at boot is terminal (the node does not start on it) and never transient,
/// even when the probe failed on a transient origin.
class S3SharedTierClassificationTest {
    @Test
    void sharedTierUnavailable_isTerminalAndNeverTransient_evenWithATransientOrigin() {
        var cause = new S3SharedTier.SharedTierUnavailable("s", "e", "b", Causes.transientCause("timeout"), "unavailable");

        assertThat(cause.isTerminal()).isTrue();
        assertThat(cause.isTransient()).isFalse();
    }
}
