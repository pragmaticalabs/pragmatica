// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmberAppNotPackagedTest {
    /// v3031b survivor m44: the caller's list is copied, so a later change to it never rewrites an already built refusal.
    @Test
    void emberAppNotPackaged_copiesTheCallersList() {
        var found = new ArrayList<>(List.of("org.example:echo:1.0.0"));
        var cause = EmberAppNotPackaged.emberAppNotPackaged("org.example:echo:1.0.0", true, "missing", found);

        found.add("org.example:late:1.0.0");

        assertThat(cause.stagedSliceArtifacts()).containsExactly("org.example:echo:1.0.0");
        assertThat(cause.message()).doesNotContain("late");
    }
}
