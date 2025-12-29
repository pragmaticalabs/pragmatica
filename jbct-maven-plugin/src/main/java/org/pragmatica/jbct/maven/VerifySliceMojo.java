package org.pragmatica.jbct.maven;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.jar.Manifest;

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

    private final List<String> errors = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog()
            .info("Skipping slice verification");
            return;
        }
        getLog()
        .info("Validating slice configuration...");
        checkManifestEntries();
        checkSliceApiProperties();
        // Report results
        for (var warning : warnings) {
            getLog()
            .warn(warning);
        }
        for (var error : errors) {
            getLog()
            .error(error);
        }
        if (!errors.isEmpty()) {
            throw new MojoFailureException(
            "Slice validation failed with " + errors.size() + " error(s)");
        }
        if (failOnWarning && !warnings.isEmpty()) {
            throw new MojoFailureException(
            "Slice validation failed with " + warnings.size() + " warning(s)");
        }
        getLog()
        .info("Slice validation passed");
    }

    private void checkManifestEntries() {
        var manifestFile = new File(project.getBuild()
                                           .getOutputDirectory(),
                                    "META-INF/MANIFEST.MF");
        if (!manifestFile.exists()) {
            warnings.add("MANIFEST.MF not found - will be created during packaging");
            return;
        }
        try (var input = new FileInputStream(manifestFile)) {
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

    private void checkSliceApiProperties() {
        var propsFile = new File(project.getBuild()
                                        .getOutputDirectory(),
                                 "META-INF/slice-api.properties");
        if (!propsFile.exists()) {
            errors.add("slice-api.properties not found. " + "Ensure annotation processor is configured.");
            return;
        }
        try (var input = new FileInputStream(propsFile)) {
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
}
