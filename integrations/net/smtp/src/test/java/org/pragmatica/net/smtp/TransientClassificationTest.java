// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.net.smtp;

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
        Cause[] transientCauses = {new SmtpError.ConnectionFailed("refused"), new SmtpError.Timeout("no banner"), new SmtpError.Rejected(451,
                                                                                                                                         "try again"), new SmtpError.AuthFailed(454,
                                                                                                                                                                                "temporary authentication failure"), new SmtpError.TlsFailed(454,
                                                                                                                                                                                                                                             "TLS not available"), new SmtpError.ProtocolError(421,
                                                                                                                                                                                                                                                                                               "service not available"), };

        for (var cause : transientCauses) {
            assertThat(cause.isTransient()).as(cause.getClass().getName()).isTrue();
            assertThat(cause.isTerminal()).as(cause.getClass().getName()).isFalse();
        }
    }

    @Test
    void terminalCauses_areTerminalAndNotTransient() {
        Cause[] terminalCauses = {new SmtpError.Rejected(550, "no such user"), new SmtpError.AuthFailed(535,
                                                                                                        "bad credentials"), new SmtpError.TlsFailed(554,
                                                                                                                                                    "TLS not supported"), new SmtpError.ProtocolError(502,
                                                                                                                                                                                                      "command not implemented"), new SmtpError.AuthFailed(334,
                                                                                                                                                                                                                                                           "a challenge this client cannot answer"), new SmtpError.Rejected(354,
                                                                                                                                                                                                                                                                                                                            "3yz where a completion was expected"), new SmtpError.TlsSetupFailed("no trust store"), };

        for (var cause : terminalCauses) {
            assertThat(cause.isTerminal()).as(cause.getClass().getName()).isTrue();
            assertThat(cause.isTransient()).as(cause.getClass().getName()).isFalse();
        }
    }
}
