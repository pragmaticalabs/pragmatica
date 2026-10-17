// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.setup.generators;

import java.nio.file.Path;
import java.util.List;

import org.pragmatica.lang.Option;

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
