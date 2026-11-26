// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.storage;

import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #2103: the remote tier's failures. A rejection is a verdict, terminal and never transient, even over a transient origin;
/// an unavailable provider follows its origin.
class RemoteTierErrorClassificationTest {
    @Test
    void rejected_isTerminalAndNeverTransient_evenWithATransientOrigin() {
        var cause = new RemoteTierError.Rejected("put", "k", Causes.transientCause("503"), "rejected");

        assertThat(cause.isTerminal()).isTrue();
        assertThat(cause.isTransient()).isFalse();
    }

    @Test
    void unavailable_followsItsOrigin() {
        assertThat(new RemoteTierError.Unavailable("get", "k", Causes.transientCause("timeout"), "unavailable").isTransient()).isTrue();
        assertThat(new RemoteTierError.Unavailable("get", "k", Causes.cause("plain"), "unavailable").isTransient()).isFalse();
    }
}
