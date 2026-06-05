// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import org.pragmatica.lang.Cause;


public sealed interface BackfillError extends Cause {
    enum General implements BackfillError {
        NO_SOURCE_REPLICA_CAUSE("No caught-up source replica available for backfill");
        private final String message;
        General(String message) {
            this.message = message;
        }
        @Override public String message() {
            return message;
        }
    }

    BackfillError NO_SOURCE_REPLICA = General.NO_SOURCE_REPLICA_CAUSE;
}
