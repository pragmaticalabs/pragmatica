package org.pragmatica.jbct.init;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.jar.Manifest;

/**
 * Validates slice project configuration.
 */
public final class SliceProjectValidator {
    private final Path projectDir;

    private SliceProjectValidator(Path projectDir) {
        this.projectDir = projectDir;
    }

    public static SliceProjectValidator sliceProjectValidator(Path projectDir) {
        return new SliceProjectValidator(projectDir);
    }

    /**
     * Validate the slice project.
     *
     * @return Validation result
     */
    public ValidationResult validate() {
        return combinePartialResults(checkPomFile(), checkSliceManifests(), checkManifestEntries());
    }

    private ValidationResult combinePartialResults(PartialResult... partials) {
        var errors = new ArrayList<String>();
        var warnings = new ArrayList<String>();
        for (var partial : partials) {
            errors.addAll(partial.errors());
            warnings.addAll(partial.warnings());
        }
        return ValidationResult.validationResult(errors, warnings);
    }

    private PartialResult checkPomFile() {
        var pomFile = projectDir.resolve("pom.xml");
        if (!Files.exists(pomFile)) {
            return PartialResult.error("pom.xml not found in project directory");
        }
        return readFile(pomFile)
        .fold(cause -> PartialResult.error("Failed to read pom.xml: " + cause.message()),
              content -> checkPomContent(content));
    }

    private PartialResult checkPomContent(String content) {
        var warnings = new ArrayList<String>();
        if (!content.contains("slice.class")) {
            warnings.add("Missing slice.class property in pom.xml");
        }
        if (!content.contains("collect-slice-deps")) {
            warnings.add("Missing collect-slice-deps goal configuration");
        }
        return PartialResult.partialResult(List.of(), warnings);
    }

    private PartialResult checkSliceManifests() {
        var targetDir = projectDir.resolve("target/classes");
        if (!Files.exists(targetDir)) {
            return PartialResult.warning("target/classes not found - run 'mvn compile' first");
        }
        var sliceDir = targetDir.resolve("META-INF/slice");
        if (!Files.exists(sliceDir)) {
            return PartialResult.error("META-INF/slice/ directory not found - ensure annotation processor is configured");
        }
        try (var files = Files.list(sliceDir)) {
            var manifestFiles = files.filter(p -> p.toString()
                                                   .endsWith(".manifest"))
                                     .toList();
            if (manifestFiles.isEmpty()) {
                return PartialResult.error("No .manifest files found in META-INF/slice/");
            }
            var errors = new ArrayList<String>();
            for (var manifestFile : manifestFiles) {
                loadProperties(manifestFile)
                    .onFailure(cause -> errors.add("Failed to read " + manifestFile.getFileName() + ": " + cause.message()))
                    .onSuccess(props -> {
                                   checkRequired(props, "slice.interface", errors);
                                   checkRequired(props, "slice.artifactId", errors);
                               });
            }
            return PartialResult.partialResult(errors, List.of());
        } catch (Exception e) {
            return PartialResult.error("Failed to scan META-INF/slice/: " + e.getMessage());
        }
    }

    private void checkRequired(Properties props, String key, List<String> errors) {
        if (props.getProperty(key) == null) {
            errors.add("Missing required property '" + key + "' in slice manifest");
        }
    }

    private PartialResult checkManifestEntries() {
        var targetDir = projectDir.resolve("target/classes");
        if (!Files.exists(targetDir)) {
            return PartialResult.empty();
        }
        var manifestFile = targetDir.resolve("META-INF/MANIFEST.MF");
        if (!Files.exists(manifestFile)) {
            return PartialResult.warning("MANIFEST.MF not found - will be created during packaging");
        }
        return loadManifest(manifestFile)
        .fold(cause -> PartialResult.error("Failed to read MANIFEST.MF: " + cause.message()),
              this::checkManifestAttributes);
    }

    private PartialResult checkManifestAttributes(Manifest manifest) {
        var warnings = new ArrayList<String>();
        var attrs = manifest.getMainAttributes();
        if (attrs.getValue("Slice-Artifact") == null) {
            warnings.add("Missing Slice-Artifact manifest entry");
        }
        if (attrs.getValue("Slice-Class") == null) {
            warnings.add("Missing Slice-Class manifest entry");
        }
        return PartialResult.partialResult(List.of(), warnings);
    }

    private Result<String> readFile(Path path) {
        try{
            return Result.success(Files.readString(path));
        } catch (IOException e) {
            return Causes.cause(e.getMessage())
                         .result();
        }
    }

    private Result<Properties> loadProperties(Path path) {
        try (var input = new FileInputStream(path.toFile())) {
            var props = new Properties();
            props.load(input);
            return Result.success(props);
        } catch (IOException e) {
            return Causes.cause(e.getMessage())
                         .result();
        }
    }

    private Result<Manifest> loadManifest(Path path) {
        try (var input = new FileInputStream(path.toFile())) {
            return Result.success(new Manifest(input));
        } catch (IOException e) {
            return Causes.cause(e.getMessage())
                         .result();
        }
    }

    private record PartialResult(List<String> errors, List<String> warnings) {
        PartialResult {
            errors = List.copyOf(errors);
            warnings = List.copyOf(warnings);
        }

        static PartialResult empty() {
            return new PartialResult(List.of(), List.of());
        }

        static PartialResult error(String error) {
            return new PartialResult(List.of(error), List.of());
        }

        static PartialResult warning(String warning) {
            return new PartialResult(List.of(), List.of(warning));
        }

        static PartialResult partialResult(List<String> errors, List<String> warnings) {
            return new PartialResult(errors, warnings);
        }
    }

    /**
     * Validation result containing errors and warnings.
     */
    public record ValidationResult(List<String> errors, List<String> warnings) {
        public ValidationResult {
            errors = List.copyOf(errors);
            warnings = List.copyOf(warnings);
        }

        public static ValidationResult validationResult(List<String> errors, List<String> warnings) {
            return new ValidationResult(errors, warnings);
        }

        public boolean hasErrors() {
            return ! errors.isEmpty();
        }

        public boolean hasWarnings() {
            return ! warnings.isEmpty();
        }

        public boolean isValid() {
            return errors.isEmpty();
        }
    }
}
