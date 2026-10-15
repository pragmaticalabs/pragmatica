// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.health;

import org.pragmatica.lang.Cause;


/// Why [CoreSwimHealthDetector#start] refused to start the detector.
public sealed interface SwimStartError extends Cause {
    enum General implements SwimStartError {
        /// `stop()` reached the detector before its first `start()`; that stop is terminal.
        DETECTOR_CLOSED("SWIM detector is closed: stop() preceded start(), so it will not start");
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
