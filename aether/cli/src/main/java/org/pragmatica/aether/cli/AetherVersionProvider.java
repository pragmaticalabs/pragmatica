// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli;

import org.pragmatica.aether.config.BuildInfo;
import picocli.CommandLine.IVersionProvider;


/// Picocli `IVersionProvider` that surfaces the JAR's `Implementation-Version` and
/// `Implementation-Build-Date` so `aether --version` always reflects the actual
/// jar bytes, not a hardcoded literal.
public final class AetherVersionProvider implements IVersionProvider {
    @Override public String[] getVersion() {
        return new String[]{"Aether " + BuildInfo.current().displayString()};
    }
}
