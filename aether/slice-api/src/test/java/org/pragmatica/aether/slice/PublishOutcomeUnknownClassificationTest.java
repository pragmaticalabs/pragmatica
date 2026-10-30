// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice;

import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #2103: a publish whose outcome is unknown is neither transient nor terminal, whatever its origin says. A timeout origin
/// is transient, and inheriting that would let a transient-only retry policy re-drive a write that may already be in the log.
class PublishOutcomeUnknownClassificationTest {
    @Test
    void aTransientOrigin_doesNotMakeTheOutcomeUnknownTransient() {
        var cause = new PublishOutcomeUnknown(Causes.transientCause("ack timeout"), "unknown");

        assertThat(cause.isTransient()).isFalse();
        assertThat(cause.isTerminal()).isFalse();
    }

    @Test
    void aTerminalOrigin_doesNotMakeTheOutcomeUnknownTerminal() {
        var cause = new PublishOutcomeUnknown(Causes.terminal("log closed"), "unknown");

        assertThat(cause.isTerminal()).isFalse();
        assertThat(cause.isTransient()).isFalse();
    }
}
