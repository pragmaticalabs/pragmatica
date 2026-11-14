// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.fsm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// Unknown peers never contribute to the core electorate.
class MemberDescriptorRoleClassificationTest {
    @Test
    void isCoreRole_requiresExplicitCoreIdentity() {
        assertThat(MemberDescriptor.isCoreRole("core")).isTrue();
        assertThat(MemberDescriptor.isCoreRole("")).isFalse();
        assertThat(MemberDescriptor.isCoreRole("worker")).isFalse();
        assertThat(MemberDescriptor.isCoreRole("spot")).isFalse();
        assertThat(MemberDescriptor.isCoreRole("other")).isFalse();
    }

    /// #2600: the worker role match is case-insensitive, like `isCore`. Red under `equals` for `worker` or for `spot`.
    @Test
    void isWorker_matchesWorkerAndSpotIgnoringCase() {
        assertThat(descriptorWithRole("Worker").isWorker()).isTrue();
        assertThat(descriptorWithRole("SPOT").isWorker()).isTrue();
        assertThat(descriptorWithRole("worker").isWorker()).isTrue();
        assertThat(descriptorWithRole("spot").isWorker()).isTrue();
        assertThat(descriptorWithRole("CORE").isWorker()).isFalse();
        assertThat(descriptorWithRole("").isWorker()).isFalse();
        assertThat(descriptorWithRole("CORE").isCore()).as("isCore is case-insensitive too").isTrue();
    }

    private static MemberDescriptor descriptorWithRole(String role) {
        return new MemberDescriptor(org.pragmatica.lang.Option.none(), role, "", "");
    }
}
