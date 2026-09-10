// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #952 — the startup-deploy failure message must report what Forge checked, not diagnose what it
/// did not.
///
/// The clean-room run that found #952 hit this message with every Maven artifact present and
/// correct; the actual cause was a database container that had been dead for ten seconds. The old
/// text ended by telling the reader to check `mvn install`, so the evidence pointed away from the
/// defect and the next reader spent their effort proving a healthy component healthy.
class ForgeServerMessageTest {
    private static final String COORDINATES = "org.example:demo:1.0.0:blueprint";
    private static final String DETAIL = "HTTP 503: upstream unavailable";

    /// `mvn`/`maven` and `install` in one sentence, however they are spelled or separated - the
    /// family the shipped message must never fall into, rather than the single wording that was
    /// removed from it.
    private static final String MAVEN_INSTALL_ADVICE = "(?i)\\b(mvn|maven)\\b[^.]*\\binstall\\b";

    @Test
    void startupDeployFailure_quotesWhatWasObserved() {
        var message = ForgeServer.startupDeployFailureMessage(COORDINATES, DETAIL);

        assertThat(message).contains(COORDINATES)
                           .contains(DETAIL)
                           .contains("/api/v1/blueprints/deploy");
    }

    /// The load-bearing property: the message states that the cause is undetermined, rather than
    /// naming one. An error that names an unverified cause reads as a diagnosis.
    @Test
    void startupDeployFailure_saysTheCauseWasNotEstablished() {
        var message = ForgeServer.startupDeployFailureMessage(COORDINATES, DETAIL);

        assertThat(message).contains("checked exactly one thing")
                           .contains("did NOT establish why");
    }

    /// Every candidate consistent with the observation is offered, including the one #952 actually
    /// hit, and none is presented as the answer.
    @Test
    void startupDeployFailure_offersCandidatesWithoutRankingThem() {
        var message = ForgeServer.startupDeployFailureMessage(COORDINATES, DETAIL);

        assertThat(message).contains("coordinates")
                           .contains("resolvable repository")
                           .contains("a database, for instance")
                           .contains("Forge probes none of them");
    }

    /// The regression itself. `mvn install` was the instruction that misdirected the #952
    /// investigation, and Forge has never verified it.
    ///
    /// This is a PATTERN and not a substring, because the substring version was evaded. An
    /// adversarial pass put the misdirection back into the shipped message worded `mvn clean
    /// install`, and every test in this class stayed green: `"mvn clean install"` does not contain
    /// `"mvn install"`. A pin that defends one spelling defends nothing, because the next author
    /// reintroduces the defect by paraphrase rather than by copy. The property is "never send the
    /// reader to Maven for a cause Forge did not check", so the match is `mvn` or `maven` followed
    /// by `install` anywhere in the same sentence, case-insensitively.
    @Test
    void startupDeployFailure_neverDirectsTheReaderToAMavenInstall() {
        var message = ForgeServer.startupDeployFailureMessage(COORDINATES, DETAIL);

        assertThat(message).doesNotContainPattern(MAVEN_INSTALL_ADVICE);
    }
}
