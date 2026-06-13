// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.ttm;

import org.pragmatica.aether.cli.ExitCode;
import org.pragmatica.lang.Cause;


final class TtmCliHelper {
    private TtmCliHelper() {}

    static int onFailure(Cause cause) {
        System.err.println("Error: " + cause.message());

        return ExitCode.ERROR;
    }
}
