// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import org.pragmatica.lang.Cause;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// #280: the retry interceptor's default policy retries only causes that declare themselves
/// transient (`Cause.isTransient()`), so a transient infrastructure failure that is NOT marked is
/// silently never retried. This pins this module's classification — removing a `Cause.Transient`
/// reddens the row for it, and the unclassified rows guard against over-marking.
class TransientClassificationTest {
    @Test
    void transientCauses_areClassifiedTransient() {
        Cause[] transientCauses = {
            new EntityError.OwnershipNotYetCommitted("k", "ks", 0),
            new EntityError.LinearizableUnavailable("k"),
            new EntityLogError.FoldInProgress("ks", 0),
        };

        for (var cause : transientCauses) {
            assertThat(cause.isTransient()).as(cause.getClass().getName()).isTrue();
            assertThat(cause.isTerminal()).as(cause.getClass().getName() + " is not also terminal").isFalse();
        }
    }

    @Test
    void unclassifiedCauses_stayUnclassified() {
        Cause[] unclassified = {
            new EntityError.EntityNotFound("k"),
            new EntityLogError.PartitionNotHeld("ks", 0),
        };

        for (var cause : unclassified) {
            assertThat(cause.isTransient()).as(cause.getClass().getName()).isFalse();
        }
    }
}
