// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

/// RED BASELINE (#3528): constants only, no remover yet.
sealed interface ApiKeyAuditRetention {
    int MAX_ENTRIES_PER_KEY = 1000;
    int MAX_REMOVALS_PER_SWEEP = 1000;

    record unused() implements ApiKeyAuditRetention {}
}
