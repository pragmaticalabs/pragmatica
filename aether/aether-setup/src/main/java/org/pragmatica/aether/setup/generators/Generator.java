// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.setup.generators;

import java.nio.file.Path;

import org.pragmatica.aether.config.AetherConfig;
import org.pragmatica.lang.Result;


public interface Generator {
    Result<GeneratorOutput> generate(AetherConfig config, Path outputDir);
    boolean supports(AetherConfig config);
}
