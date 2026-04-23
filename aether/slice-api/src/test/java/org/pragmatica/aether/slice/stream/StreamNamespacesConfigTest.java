// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


class StreamNamespacesConfigTest {

    @Test
    void defaultIsDisabled() {
        assertThat(StreamNamespacesConfig.defaultConfig().enabled()).isFalse();
        assertThat(StreamNamespacesConfig.defaultConfig()).isSameAs(StreamNamespacesConfig.DISABLED);
    }

    @Test
    void enabledConstantIsEnabled() {
        assertThat(StreamNamespacesConfig.ENABLED.enabled()).isTrue();
    }
}
