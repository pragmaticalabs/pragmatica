// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.SliceLoadingFailure.Fatal;
import org.pragmatica.aether.slice.SliceLoadingFailure.Intermittent;
import org.pragmatica.aether.slice.SliceLoadingFailure.Intermittent.SliceNotInStore;
import org.pragmatica.aether.slice.SliceLoadingFailure.Unrecognised;
import org.pragmatica.lang.io.CoreError;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;

/// #930 — permanence of an UNRECOGNISED cause is an input to `classify`, not a property of the
/// classifier.
///
/// Supersedes the #916 pin this file used to carry. #916 asserted that the catch-all "must keep
/// producing `Fatal.UnexpectedError`", on the argument that an intermittent default would retry a
/// genuinely fatal cause and then abandon it without a rollback. #922 removed the abandonment (retry
/// exhaustion now settles against the owning blueprint's durable apply record) and #930 removed the
/// catch-all, so the assertion that a bare unrecognised cause is Fatal no longer describes the code
/// — there is no longer a call that can ask the question without answering it.
///
/// The two disposition tests below are deliberately MUTUALLY EXCLUSIVE on the same input: one
/// argument value, two incompatible outcomes. A mutation that hard-codes either arm turns the other
/// red, which a single-arm assertion would not catch.
class SliceLoadingFailureClassifyTest {
    private static final String UNRECOGNISED_MESSAGE = "a cause nobody has typed";

    @Test
    void unrecognisedCause_atAPermanentSite_isFatal() {
        var classified = SliceLoadingFailure.classify(Causes.cause(UNRECOGNISED_MESSAGE), Unrecognised.PERMANENT);

        assertThat(classified).isInstanceOf(Fatal.UnexpectedError.class);
        assertThat(classified.isFatal())
                .as("a site that declared PERMANENT must produce a fatal classification, which is what "
                    + "puts the leader on handleDeterministicFailure's rollback")
                .isTrue();
    }

    @Test
    void unrecognisedCause_atARetrySite_isIntermittent() {
        var classified = SliceLoadingFailure.classify(Causes.cause(UNRECOGNISED_MESSAGE), Unrecognised.RETRY);

        assertThat(classified).isInstanceOf(Intermittent.UnrecognisedFailure.class);
        assertThat(classified.isFatal())
                .as("a site that declared RETRY must NOT produce a fatal classification — this is the arm "
                    + "that stops a consensus outage during activation from rolling a blueprint back (#923)")
                .isFalse();
        assertThat(classified.message())
                .as("the message must not claim knowledge the classifier does not have: it reports the cause "
                    + "as unrecognised, never as a resource being unavailable")
                .contains(UNRECOGNISED_MESSAGE)
                .doesNotContain("Resource temporarily unavailable");
    }

    /// The disposition governs ONLY the unrecognised arm. A cause the classifier does recognise is
    /// evidence about the cause itself, so it must classify identically at both kinds of site —
    /// otherwise typing a cause at its raise site would stop being worth doing.
    @Test
    void aTypedCause_classifiesIdenticallyUnderBothDispositions() {
        var raised = SliceNotInStore.sliceNotInStore("com.example:slice-a:1.0.0", "activation");

        assertThat(SliceLoadingFailure.classify(raised, Unrecognised.RETRY)).isSameAs(raised);
        assertThat(SliceLoadingFailure.classify(raised, Unrecognised.PERMANENT))
                .as("#916's typed cause must survive even a PERMANENT declaration untouched — the raise "
                    + "site that TYPED it has already decided, and #930 did not take that away")
                .isSameAs(raised);
        assertThat(SliceLoadingFailure.classify(raised, Unrecognised.PERMANENT).isFatal()).isFalse();
    }

    @Test
    void aTimeout_staysIntermittent_evenAtAPermanentSite() {
        var classified = SliceLoadingFailure.classify(new CoreError.Timeout("activation stalled"), Unrecognised.PERMANENT);

        assertThat(classified).isInstanceOf(Intermittent.Timeout.class);
        assertThat(classified.isFatal()).isFalse();
    }
}
