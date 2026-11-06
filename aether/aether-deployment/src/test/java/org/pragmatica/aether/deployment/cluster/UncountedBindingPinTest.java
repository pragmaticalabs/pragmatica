// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.LifecycleMutations;

import static org.assertj.core.api.Assertions.assertThat;

/// The slice module cannot see `CapacityControlledLifecycle`, so `LifecycleMutations` carries its own copy of the uncounted binding; this pins the two
/// to one string.
class UncountedBindingPinTest {
    @Test
    void theLifecycleLibrary_andTodaysCapacityLifecycle_nameTheUncountedBindingAlike() {
        assertThat(LifecycleMutations.UNCOUNTED_BINDING).isEqualTo(CapacityControlledLifecycle.UNCOUNTED_BINDING).isEqualTo("external-uncounted");
    }
}
