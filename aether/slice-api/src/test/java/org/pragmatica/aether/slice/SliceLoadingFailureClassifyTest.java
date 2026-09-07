// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.SliceLoadingFailure.Fatal;
import org.pragmatica.aether.slice.SliceLoadingFailure.Intermittent.SliceNotInStore;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;

/// #916 — the two halves of the classification ruling, pinned as behaviour rather than as prose.
///
/// The ticket asked whether `classify`'s catch-all should stay fail-permanent. It does. That is a
/// deliberate choice with a cost, so it gets a test that fails if someone quietly reverses it, and
/// a second test showing the price being paid: the transient cause on the activation path is typed
/// at its raise site so it never reaches the catch-all at all.
class SliceLoadingFailureClassifyTest {
    @Test
    void unrecognisedCause_staysPermanent() {
        var classified = SliceLoadingFailure.classify(Causes.cause("a cause nobody has typed"));

        assertThat(classified).as("the catch-all arm must keep producing Fatal.UnexpectedError")
                              .isInstanceOf(Fatal.UnexpectedError.class);
        assertThat(classified.isFatal())
                .as("#916 ruling: permanent-by-default is kept, because intermittent-by-default "
                    + "would retry a genuinely permanent failure five times and then abandon it "
                    + "WITHOUT a rollback, silently breaking the ALL_OR_NOTHING guarantee")
                .isTrue();
    }

    @Test
    void sliceNotInStore_isIntermittent_andSurvivesClassifyUnchanged() {
        var raised = SliceNotInStore.sliceNotInStore("com.example:slice-a:1.0.0", "activation");
        var classified = SliceLoadingFailure.classify(raised);

        assertThat(classified).as("an already-typed SliceLoadingFailure must pass through classify untouched")
                              .isSameAs(raised);
        assertThat(classified.isFatal())
                .as("#916: the unload/activate crossing is retryable, so it must not be fatal — "
                    + "this is what keeps the leader on handleTransientFailure's bounded retry "
                    + "instead of handleDeterministicFailure's rollback")
                .isFalse();
        assertThat(classified.message()).contains("not present in SliceStore");
    }
}
