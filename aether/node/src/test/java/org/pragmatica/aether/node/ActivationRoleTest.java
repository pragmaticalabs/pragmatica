// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ActivationRoleTest {
    @Test
    void committedDirectiveCannotChangeConfiguredNodeRole() {
        assertThat(AetherNode.activationRoleMatches(true, "WORKER")).isTrue();
        assertThat(AetherNode.activationRoleMatches(false, "CORE")).isTrue();
        assertThat(AetherNode.activationRoleMatches(true, "CORE")).isFalse();
        assertThat(AetherNode.activationRoleMatches(false, "WORKER")).isFalse();
        assertThat(AetherNode.activationRoleMatches(false, "")).isFalse();
    }
}
