package org.pragmatica.jbct.maven;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.jar.Manifest;
import java.util.stream.Stream;

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
            getLog()
            .info("Skipping slice verification");
            return;
        }
        getLog()
        .info("Validating slice configuration...");

        var result = validateSlice();

        for (var warning : result.warnings()) {
            getLog().warn(warning);
        }
        for (var error : result.errors()) {
            getLog().error(error);
        }

        if (!result.errors().isEmpty()) {
            throw new MojoFailureException(
            "Slice validation failed with " + result.errors().size() + " error(s)");
        }
        if (failOnWarning && !result.warnings().isEmpty()) {
            throw new MojoFailureException(
            "Slice validation failed with " + result.warnings().size() + " warning(s)");
        }
        getLog()
        .info("Slice validation passed");
    }

    private ValidationResult validateSlice() {
        var manifestResult = checkManifestEntries();
        var propsResult = checkSliceApiProperties();

        var errors = new ArrayList<String>();
        var warnings = new ArrayList<String>();

        Stream.of(manifestResult, propsResult)
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

    private PartialResult checkSliceApiProperties() {
        var propsFile = new File(project.getBuild()
                                        .getOutputDirectory(),
                                 "META-INF/slice-api.properties");
        if (!propsFile.exists()) {
            return PartialResult.error("slice-api.properties not found. " + "Ensure annotation processor is configured.");
        }
        try (var input = new FileInputStream(propsFile)) {
            var props = new Properties();
            props.load(input);
            var errors = new ArrayList<String>();
            checkRequired(props, "api.artifact", errors);
            checkRequired(props, "slice.artifact", errors);
            checkRequired(props, "api.interface", errors);
            checkRequired(props, "impl.interface", errors);
            return PartialResult.partialResult(errors, List.of());
        } catch (IOException e) {
            return PartialResult.error("Failed to read slice-api.properties: " + e.getMessage());
        }
    }

    private void checkRequired(Properties props, String key, List<String> errors) {
        if (props.getProperty(key) == null) {
            errors.add("Missing required property '" + key + "' in slice-api.properties");
        }
    }

    private record PartialResult(List<String> errors, List<String> warnings) {
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
    }
}
