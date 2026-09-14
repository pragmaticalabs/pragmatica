// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.fsm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #689 pins the classification it deliberately does NOT change: a blank or absent role label
/// counts as core, exactly as the explicit literal `core` does, and only the literal `worker` is
/// excluded from the core set. Acting on an unresolved view is the dangerous direction, so the
/// default fails toward core; #689 adds detection of the mismatch, never a different default.
class MemberDescriptorRoleClassificationTest {
    @Test
    void blankAndCore_areEquivalent_andOnlyWorkerIsExcluded() {
        assertThat(MemberDescriptor.isCoreRole("")).as("blank counts as core").isTrue();
        assertThat(MemberDescriptor.isCoreRole("core")).as("the explicit literal").isTrue();
        assertThat(MemberDescriptor.isCoreRole("")).as("#689: blank and core must stay equivalent")
                                                   .isEqualTo(MemberDescriptor.isCoreRole("core"));
        assertThat(MemberDescriptor.isCoreRole("worker")).as("the only excluded literal").isFalse();
    }
}
