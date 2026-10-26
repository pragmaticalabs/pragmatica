// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

import org.pragmatica.lang.Cause;


/// Why an app HTTP certificate rotation did not take effect although its listeners were rebound.
public sealed interface AppHttpRotationError extends Cause {
    enum General implements AppHttpRotationError {
        /// The server was stopped, or had not finished starting, when the rotation reported its new listeners. The
        /// state machine closed them, so the new certificate is NOT being served.
        ROTATION_DISCARDED("Certificate rotation discarded: the app HTTP server was stopped or still starting when the rotation completed; the new certificate is not applied");
        private final String message;
        General(String message) {
            this.message = message;
        }
        @Override
        public String message() {
            return message;
        }
    }
}
