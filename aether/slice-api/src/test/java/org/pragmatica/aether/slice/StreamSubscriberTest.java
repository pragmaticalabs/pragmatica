// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.slice;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StreamSubscriberTest {

    @Test
    void isMarkerInterface_withNoMethods() {
        assertThat(StreamSubscriber.class.getMethods())
            .filteredOn(m -> m.getDeclaringClass() == StreamSubscriber.class)
            .isEmpty();
    }

    @Test
    void isInterface() {
        assertThat(StreamSubscriber.class.isInterface()).isTrue();
    }
}
