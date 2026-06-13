// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.setup.generators;

import org.pragmatica.lang.Option;

import java.nio.file.Path;
import java.util.List;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


public record GeneratorOutput(Path outputDir,
                              List<Path> generatedFiles,
                              Option<Path> startScript,
                              Option<Path> stopScript,
                              String instructions) {
    public static GeneratorOutput generatorOutput(Path outputDir, List<Path> files, String instructions) {
        return new GeneratorOutput(outputDir, files, none(), none(), instructions);
    }

    public static GeneratorOutput generatorOutput(Path outputDir,
                                                  List<Path> files,
                                                  Path start,
                                                  Path stop,
                                                  String instructions) {
        return new GeneratorOutput(outputDir, files, some(start), some(stop), instructions);
    }
}
