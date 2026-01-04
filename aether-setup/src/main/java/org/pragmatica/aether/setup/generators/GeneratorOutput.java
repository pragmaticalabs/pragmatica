package org.pragmatica.aether.setup.generators;

import java.nio.file.Path;
import java.util.List;

/**
 * Output from a generator run.
 *
 * @param outputDir      Root directory containing generated files
 * @param generatedFiles List of all generated file paths (relative to outputDir)
 * @param startScript    Path to the start script (if applicable)
 * @param stopScript     Path to the stop script (if applicable)
 * @param instructions   Human-readable instructions for next steps
 */
public record GeneratorOutput(
 Path outputDir,
 List<Path> generatedFiles,
 Path startScript,
 Path stopScript,
 String instructions) {
    public static GeneratorOutput of(Path outputDir, List<Path> files, String instructions) {
        return new GeneratorOutput(outputDir, files, null, null, instructions);
    }

    public static GeneratorOutput withScripts(Path outputDir,
                                              List<Path> files,
                                              Path start,
                                              Path stop,
                                              String instructions) {
        return new GeneratorOutput(outputDir, files, start, stop, instructions);
    }
}
