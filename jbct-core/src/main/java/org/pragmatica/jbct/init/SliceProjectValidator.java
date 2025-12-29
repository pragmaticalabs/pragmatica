package org.pragmatica.jbct.init;

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
    private final List<String> errors = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

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
        errors.clear();
        warnings.clear();
        checkPomFile();
        checkSliceApiProperties();
        checkManifestEntries();
        return new ValidationResult(List.copyOf(errors), List.copyOf(warnings));
    }

    private void checkPomFile() {
        var pomFile = projectDir.resolve("pom.xml");
        if (!Files.exists(pomFile)) {
            errors.add("pom.xml not found in project directory");
            return;
        }
        try{
            var content = Files.readString(pomFile);
            if (!content.contains("slice.class")) {
                warnings.add("Missing slice.class property in pom.xml");
            }
            if (!content.contains("collect-slice-deps")) {
                warnings.add("Missing collect-slice-deps goal configuration");
            }
        } catch (IOException e) {
            errors.add("Failed to read pom.xml: " + e.getMessage());
        }
    }

    private void checkSliceApiProperties() {
        var targetDir = projectDir.resolve("target/classes");
        if (!Files.exists(targetDir)) {
            warnings.add("target/classes not found - run 'mvn compile' first");
            return;
        }
        var propsFile = targetDir.resolve("META-INF/slice-api.properties");
        if (!Files.exists(propsFile)) {
            errors.add("slice-api.properties not found - ensure annotation processor is configured");
            return;
        }
        try (var input = new FileInputStream(propsFile.toFile())) {
            var props = new Properties();
            props.load(input);
            checkRequired(props, "api.artifact");
            checkRequired(props, "slice.artifact");
            checkRequired(props, "api.interface");
            checkRequired(props, "impl.interface");
        } catch (IOException e) {
            errors.add("Failed to read slice-api.properties: " + e.getMessage());
        }
    }

    private void checkRequired(Properties props, String key) {
        if (props.getProperty(key) == null) {
            errors.add("Missing required property '" + key + "' in slice-api.properties");
        }
    }

    private void checkManifestEntries() {
        var targetDir = projectDir.resolve("target/classes");
        if (!Files.exists(targetDir)) {
            return;
        }
        var manifestFile = targetDir.resolve("META-INF/MANIFEST.MF");
        if (!Files.exists(manifestFile)) {
            warnings.add("MANIFEST.MF not found - will be created during packaging");
            return;
        }
        try (var input = new FileInputStream(manifestFile.toFile())) {
            var manifest = new Manifest(input);
            var attrs = manifest.getMainAttributes();
            if (attrs.getValue("Slice-Artifact") == null) {
                warnings.add("Missing Slice-Artifact manifest entry");
            }
            if (attrs.getValue("Slice-Class") == null) {
                warnings.add("Missing Slice-Class manifest entry");
            }
        } catch (IOException e) {
            errors.add("Failed to read MANIFEST.MF: " + e.getMessage());
        }
    }

    /**
     * Validation result containing errors and warnings.
     */
    public record ValidationResult(List<String> errors, List<String> warnings) {
        public boolean hasErrors() {
            return !errors.isEmpty();
        }

        public boolean hasWarnings() {
            return !warnings.isEmpty();
        }

        public boolean isValid() {
            return errors.isEmpty();
        }
    }
}
