// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.terra;

import org.pragmatica.lang.Cause;


public sealed interface TerraError extends Cause {
    record InvalidBlueprint(String message) implements TerraError {}

    record InvalidGraph(String message) implements TerraError {}

    record UnsupportedCapability(String message) implements TerraError {}

    record NotRunning(String message) implements TerraError {}
}
