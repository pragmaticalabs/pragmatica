// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.invoke;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.lang.Cause;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// #280: the retry interceptor's default policy retries only causes that declare themselves
/// transient (`Cause.isTransient()`), so a transient infrastructure failure that is NOT marked is
/// silently never retried. This pins this module's classification — removing a `Cause.Transient`
/// reddens the row for it, and the unclassified rows guard against over-marking.
class TransientClassificationTest {
    private static final Artifact ARTIFACT = Artifact.artifact("com.example:my-slice:1.0.0").unwrap();
    private static final MethodName METHOD = MethodName.methodName("lookup").unwrap();

    @Test
    void transientCauses_areClassifiedTransient() {
        Cause[] transientCauses = {
            new SliceInvokerError.NoEndpointsError(ARTIFACT, METHOD),
            new SliceInvokerError.TimeoutError(ARTIFACT, METHOD, 1_000),
        };

        for (var cause : transientCauses) {
            assertThat(cause.isTransient()).as(cause.getClass().getName()).isTrue();
            assertThat(cause.isTerminal()).as(cause.getClass().getName() + " is not also terminal").isFalse();
        }
    }

    @Test
    void unclassifiedCauses_stayUnclassified() {
        Cause[] unclassified = {
            new SliceInvokerError.SerializationError("bad"),
            new SliceInvokerError.MethodHandleError("a", "m", "reason"),
        };

        for (var cause : unclassified) {
            assertThat(cause.isTransient()).as(cause.getClass().getName()).isFalse();
        }
    }
}
