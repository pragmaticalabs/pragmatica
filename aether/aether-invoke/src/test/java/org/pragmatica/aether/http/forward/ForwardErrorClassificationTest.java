// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.forward;

import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #2103: the forward failures classify themselves. A request that may have reached a node is not transient whatever its
/// timeout origin says, one that was never sent follows its origin, and the terminal one stays terminal.
class ForwardErrorClassificationTest {
    @Test
    void outcomeUnknown_isNeitherTransientNorTerminal_whateverItsOriginSays() {
        var fromTransient = new ForwardError.OutcomeUnknown(Causes.transientCause("timeout"), "unknown");
        var fromTerminal = new ForwardError.OutcomeUnknown(Causes.terminal("departed"), "unknown");

        assertThat(fromTransient.isTransient()).isFalse();
        assertThat(fromTransient.isTerminal()).isFalse();
        assertThat(fromTerminal.isTerminal()).isFalse();
        assertThat(fromTerminal.isTransient()).isFalse();
    }

    @Test
    void notRetried_isTerminalAndNeverTransient_evenWithATransientOrigin() {
        var cause = new ForwardError.NotRetried("POST", Causes.transientCause("timeout"), "not retried");

        assertThat(cause.isTerminal()).isTrue();
        assertThat(cause.isTransient()).isFalse();
    }

    @Test
    void notSent_followsItsOrigin() {
        assertThat(new ForwardError.NotSent(Causes.transientCause("no node"), "not sent").isTransient()).isTrue();
        assertThat(new ForwardError.NotSent(Causes.cause("encode failed"), "not sent").isTransient()).isFalse();
    }
}
