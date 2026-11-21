// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.pragmatica.lang.Cause;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #3481 fix round item 4 (survivors SE3, SE4): `StreamNotMaterialized` is transient ("a repeat may succeed") and its message
/// is the sentence the 503 body carries, both asserted on the record itself.
class StreamNotMaterializedTest {
    @Test
    void streamNotMaterialized_isTransient() {
        assertThat(new StreamError.StreamNotMaterialized("ns/orders")).isInstanceOf(Cause.Transient.class);
    }

    @Test
    void streamNotMaterialized_messageNamesTheStreamAndSaysItIsNotMaterializedYet() {
        assertThat(new StreamError.StreamNotMaterialized("ns/orders").message())
            .isEqualTo("Stream ns/orders is registered but not materialized on this node yet");
    }
}
