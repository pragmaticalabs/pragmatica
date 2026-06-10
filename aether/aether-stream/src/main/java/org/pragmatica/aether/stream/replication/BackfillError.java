// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import org.pragmatica.lang.Cause;


public sealed interface BackfillError extends Cause {
    enum General implements BackfillError {
        NO_SOURCE_REPLICA("No caught-up source replica available for backfill"),
        MALFORMED_RESPONSE("Malformed catch-up response: payload/timestamp count mismatch"),
        INCOMPLETE_BACKFILL("Catch-up applied fewer events than the source watermark — gap remains");
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
