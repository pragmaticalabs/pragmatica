// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import org.pragmatica.aether.node.assembly.WorkerHierarchyAssembly;

class ActivationRoleTest {
    @Test
    void committedDirectiveCannotChangeConfiguredNodeRole() {
        assertThat(WorkerHierarchyAssembly.activationRoleMatches(true, "WORKER")).isTrue();
        assertThat(WorkerHierarchyAssembly.activationRoleMatches(false, "CORE")).isTrue();
        assertThat(WorkerHierarchyAssembly.activationRoleMatches(true, "CORE")).isFalse();
        assertThat(WorkerHierarchyAssembly.activationRoleMatches(false, "WORKER")).isFalse();
        assertThat(WorkerHierarchyAssembly.activationRoleMatches(false, "")).isFalse();
    }
}
