package org.pragmatica.aether.setup.generators;

import org.pragmatica.lang.Option;

import java.nio.file.Path;
import java.util.List;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// Output from a generator run.
///
/// @param outputDir      Root directory containing generated files
/// @param generatedFiles List of all generated file paths (relative to outputDir)
/// @param startScript    Path to the start script (if applicable)
/// @param stopScript     Path to the stop script (if applicable)
/// @param instructions   Human-readable instructions for next steps
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
