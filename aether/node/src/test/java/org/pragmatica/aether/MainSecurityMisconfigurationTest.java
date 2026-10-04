// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.ConfigValidator.ConfigError;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// #909 — only a self-contradicting security setting stops the boot; every other load failure keeps
/// the existing log-and-continue behaviour (see the note on `ConfigValidator#MINIMUM_CLUSTER_SIZE`).
class MainSecurityMisconfigurationTest {
    @Test
    void securityMisconfiguration_exitsWithStatusOne() {
        var exited = new AtomicInteger(-1);

        Main.refuseBootOnSecurityMisconfiguration(ConfigError.securityMisconfigured("jwks_url is missing"), exited::set);

        assertEquals(1, exited.get());
    }

    @Test
    void otherLoadFailures_doNotExit() {
        var exited = new AtomicInteger(-1);

        Main.refuseBootOnSecurityMisconfiguration(ConfigError.validationFailed(List.of("some other problem")), exited::set);

        assertEquals(-1, exited.get(), "control: a generic validation failure must keep the log-and-continue path");
    }
}
