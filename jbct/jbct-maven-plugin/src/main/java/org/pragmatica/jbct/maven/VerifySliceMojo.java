package org.pragmatica.jbct.maven;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Stream;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

/**
 * Validates slice configuration by checking manifest entries and generated artifacts.
 */
@Mojo(name = "verify-slice",
 defaultPhase = LifecyclePhase.VERIFY,
 requiresDependencyResolution = ResolutionScope.COMPILE)
public class VerifySliceMojo extends AbstractMojo {
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(property = "jbct.failOnWarning", defaultValue = "false")
    private boolean failOnWarning;

    @Parameter(property = "jbct.skip", defaultValue = "false")
    private boolean skip;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Skipping slice verification");
            return;
        }
        getLog().info("Validating slice configuration...");
        var result = validateSlice();
        for (var warning : result.warnings()) {
            getLog().warn(warning);
        }
        for (var error : result.errors()) {
            getLog().error(error);
        }
        if (!result.errors()
                   .isEmpty()) {
            throw new MojoFailureException("Slice validation failed with " + result.errors()
                                                                                  .size() + " error(s)");
        }
        if (failOnWarning && !result.warnings()
                                    .isEmpty()) {
            throw new MojoFailureException("Slice validation failed with " + result.warnings()
                                                                                  .size() + " warning(s)");
        }
        getLog().info("Slice validation passed");
    }

    private ValidationResult validateSlice() {
        var manifestResult = checkManifestEntries();
        var sliceManifestResult = checkSliceManifests();
        var scopeResult = checkRuntimeDependencyScopes();
        var sliceScopeResult = checkSliceDependencyScopes();
        var errors = new ArrayList<String>();
        var warnings = new ArrayList<String>();
        Stream.of(manifestResult, sliceManifestResult, scopeResult, sliceScopeResult)
              .forEach(partial -> {
                  errors.addAll(partial.errors());
                  warnings.addAll(partial.warnings());
              });
        return new ValidationResult(errors, warnings);
    }

    private PartialResult checkManifestEntries() {
        var manifestFile = new File(project.getBuild()
                                           .getOutputDirectory(),
                                    "META-INF/MANIFEST.MF");
        if (!manifestFile.exists()) {
            return PartialResult.warning("MANIFEST.MF not found - will be created during packaging");
        }
        try (var input = new FileInputStream(manifestFile)) {
            var manifest = new Manifest(input);
            var warnings = new ArrayList<String>();
            var attrs = manifest.getMainAttributes();
            if (attrs.getValue("Slice-Artifact") == null) {
                warnings.add("Missing Slice-Artifact manifest entry");
            }
            if (attrs.getValue("Slice-Class") == null) {
                warnings.add("Missing Slice-Class manifest entry");
            }
            return PartialResult.partialResult(List.of(), warnings);
        } catch (IOException e) {
            return PartialResult.error("Failed to read MANIFEST.MF: " + e.getMessage());
        }
    }

    private PartialResult checkSliceManifests() {
        var sliceDir = new File(project.getBuild()
                                       .getOutputDirectory(),
                                "META-INF/slice");
        if (!sliceDir.exists() || !sliceDir.isDirectory()) {
            return PartialResult.error("META-INF/slice/ directory not found. "
                                       + "Ensure annotation processor is configured.");
        }
        var manifestFiles = sliceDir.listFiles((dir, name) -> name.endsWith(".manifest"));
        if (manifestFiles == null || manifestFiles.length == 0) {
            return PartialResult.error("No .manifest files found in META-INF/slice/");
        }
        var errors = new ArrayList<String>();
        for (var manifestFile : manifestFiles) {
            try (var input = new FileInputStream(manifestFile)) {
                var props = new Properties();
                props.load(input);
                checkRequired(props, "slice.interface", errors);
                checkRequired(props, "slice.artifactId", errors);
            } catch (IOException e) {
                errors.add("Failed to read " + manifestFile.getName() + ": " + e.getMessage());
            }
        }
        return PartialResult.partialResult(errors, List.of());
    }

    /**
     * Validates that Aether runtime and Pragmatica Lite dependencies are marked as 'provided'.
     * These libraries are provided by the Aether runtime and should not be bundled with slices.
     */
    private PartialResult checkRuntimeDependencyScopes() {
        // Group IDs that must use 'provided' scope
        var runtimeGroupIds = Set.of("org.pragmatica-lite", "org.pragmatica-lite.aether");
        // Artifact IDs that are exceptions (e.g., slice-processor is correctly provided for compilation)
        var allowedNonProvided = Set.of("slice-processor");
        var errors = new ArrayList<String>();
        for (var artifact : project.getDependencyArtifacts()) {
            var groupId = artifact.getGroupId();
            var artifactId = artifact.getArtifactId();
            var scope = artifact.getScope();
            if (runtimeGroupIds.contains(groupId) && !allowedNonProvided.contains(artifactId)) {
                if (!Artifact.SCOPE_PROVIDED.equals(scope) && !Artifact.SCOPE_TEST.equals(scope)) {
                    errors.add("Dependency " + groupId + ":" + artifactId + " must have 'provided' scope. "
                               + "Aether runtime libraries should not be bundled with slices. " + "Current scope: " + (scope == null
                                                                                                                       ? "compile"
                                                                                                                       : scope));
                }
            }
        }
        return PartialResult.partialResult(errors, List.of());
    }

    /**
     * Validates that slice dependencies use 'provided' scope.
     * Slice dependencies should not be bundled - the Aether runtime loads them at deployment.
     */
    private PartialResult checkSliceDependencyScopes() {
        var errors = new ArrayList<String>();
        for (var artifact : project.getDependencyArtifacts()) {
            var file = artifact.getFile();
            if (file == null || !file.exists() || !file.getName()
                                                       .endsWith(".jar")) {
                continue;
            }
            // Check if this is a slice dependency (has slice-api.properties)
            if (!isSliceArtifact(file)) {
                continue;
            }
            var scope = artifact.getScope();
            if (!Artifact.SCOPE_PROVIDED.equals(scope) && !Artifact.SCOPE_TEST.equals(scope)) {
                errors.add("Slice dependency " + artifact.getGroupId() + ":" + artifact.getArtifactId()
                           + " must have 'provided' scope. "
                           + "Slice dependencies are loaded by Aether runtime and should not be bundled. "
                           + "Current scope: " + (scope == null
                                                  ? "compile"
                                                  : scope));
            }
        }
        return PartialResult.partialResult(errors, List.of());
    }

    private boolean isSliceArtifact(File jarFile) {
        try (var jar = new JarFile(jarFile)) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (entry.getName()
                         .startsWith("META-INF/slice/") && entry.getName()
                                                                .endsWith(".manifest")) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            getLog().debug("Could not read JAR: " + jarFile + " - " + e.getMessage());
            return false;
        }
    }

    private void checkRequired(Properties props, String key, List<String> errors) {
        if (props.getProperty(key) == null) {
            errors.add("Missing required property '" + key + "' in slice manifest");
        }
    }

    private record PartialResult(List<String> errors, List<String> warnings) {
        PartialResult {
            errors = List.copyOf(errors);
            warnings = List.copyOf(warnings);
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

    private record ValidationResult(List<String> errors, List<String> warnings) {
        ValidationResult {
            errors = List.copyOf(errors);
            warnings = List.copyOf(warnings);
        }
    }
}
