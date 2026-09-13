// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.email.http;

import org.pragmatica.lang.Cause;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// #271/#280: the retry interceptor's default policy retries only causes that declare themselves
/// transient (`Cause.isTransient()`), and the notification senders stop on terminal ones. This
/// pins this module's classification directly — a wrong mark reddens its row — because the
/// sender-level pins read only `isTerminal()` and `DeliveryFailed` used to erase the rest.
class TransientClassificationTest {
    @Test
    void transientCauses_areTransientAndNotTerminal() {
        Cause[] transientCauses = {
            new HttpEmailError.RequestFailed(503, "unavailable"),
            new HttpEmailError.RequestFailed(429, "slow down"),
            new HttpEmailError.RequestFailed(408, "timeout"),
            new HttpEmailError.RequestFailed(500, "boom"),
        };

        for (var cause : transientCauses) {
            assertThat(cause.isTransient()).as(cause.getClass().getName()).isTrue();
            assertThat(cause.isTerminal()).as(cause.getClass().getName()).isFalse();
        }
    }

    @Test
    void terminalCauses_areTerminalAndNotTransient() {
        Cause[] terminalCauses = {
            new HttpEmailError.RequestFailed(400, "bad request"),
            new HttpEmailError.RequestFailed(404, "gone"),
            new HttpEmailError.AuthError("HTTP 401"),
            new HttpEmailError.VendorNotFound("nope"),
        };

        for (var cause : terminalCauses) {
            assertThat(cause.isTerminal()).as(cause.getClass().getName()).isTrue();
            assertThat(cause.isTransient()).as(cause.getClass().getName()).isFalse();
        }
    }
}
