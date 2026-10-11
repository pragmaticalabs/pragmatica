// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli;

import org.pragmatica.aether.config.BuildInfo;

import picocli.CommandLine.IVersionProvider;


public final class AetherVersionProvider implements IVersionProvider {
    @Override
    public String[] getVersion() {
        return new String[]{"Aether " + BuildInfo.current().displayString()};
    }
}
