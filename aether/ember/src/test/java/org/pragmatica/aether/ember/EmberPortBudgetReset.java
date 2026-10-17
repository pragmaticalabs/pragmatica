// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/// Gives every test its own port back-off budget ([EmberTestPorts#EXHAUSTION_WAIT_MS]) and arms the probe for the test's
/// duration: [EmberTestPorts#freeBase] refuses to run unarmed. Applied through [PortBudget].
public final class EmberPortBudgetReset implements BeforeEachCallback, AfterEachCallback {
    @Override
    public void beforeEach(ExtensionContext context) {
        EmberTestPorts.resetBackoffBudget(EmberTestPorts.EXHAUSTION_WAIT_MS);
        EmberTestPorts.arm(true);
    }

    @Override
    public void afterEach(ExtensionContext context) {
        EmberTestPorts.arm(false);
    }
}
