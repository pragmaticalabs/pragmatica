package org.pragmatica.jbct.maven;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.pragmatica.jbct.config.ConfigLoader;
import org.pragmatica.jbct.config.FilesConfig;
import org.pragmatica.jbct.config.JbctConfig;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.shared.FileCollector;
import org.pragmatica.lang.Option;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;


/// Base class for JBCT Maven mojos with common configuration parameters.
public abstract class AbstractJbctMojo extends AbstractMojo {
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    protected MavenProject project;

    @Parameter(property = "jbct.sourceDirectory", defaultValue = "${project.build.sourceDirectory}")
    protected File sourceDirectory;

    @Parameter(property = "jbct.testSourceDirectory", defaultValue = "${project.build.testSourceDirectory}")
    protected File testSourceDirectory;

    @Parameter(property = "jbct.skip", defaultValue = "false")
    protected boolean skip;

    /// Load JBCT configuration from project directory.
    protected JbctConfig loadConfig() {
        var projectDir = project.getBasedir().toPath();

        return ConfigLoader.load(Option.none(), Option.option(projectDir));
    }

    /// Create lint context from configuration.
    protected LintContext createLintContext(JbctConfig config) {
        return LintContext.fromConfig(config);
    }

    /// Collect Java files from source directories, applying file filters.
    ///
    /// `includeTests` is passed in rather than read from a field here, and this class deliberately
    /// declares no `includeTests` field. It used to: three subclasses that wanted a different
    /// default declared their own field of that name, which SHADOWED it, and since a field read is
    /// statically bound this method kept reading the parent's — so `jbct:process` and `jbct:format`
    /// never saw `src/test/java` and `-Djbct.includeTests=true` was inert for them (#624). With no
    /// field to shadow, each goal's declared default is the value that reaches collection.
    protected List<Path> collectJavaFiles(FilesConfig filesConfig, boolean includeTests) {
        return FileCollector.collectFromDirectories(Option.option(sourceDirectory).map(File::toPath),
                                                    Option.option(testSourceDirectory).map(File::toPath),
                                                    includeTests,
                                                    filesConfig,
                                                    msg -> getLog().info(msg));
    }

    /// Check if this mojo should be skipped.
    protected boolean shouldSkip(String goalName) {
        if (skip) {
            getLog().info("Skipping JBCT " + goalName);

            return true;
        }

        return false;
    }

    /// Report an empty file set HONESTLY, distinguishing "nothing to check" from "everything was
    /// excluded from checking" (#740).
    ///
    /// Both states used to render as `No Java files found.` at INFO, followed by a green build. For a
    /// test-only module under the default `includeTests=false` that sentence is simply untrue — the
    /// files exist and were skipped by policy — and a reader has no way to tell whether the
    /// instrument looked and found nothing or never looked at all. A gate that cannot fail must at
    /// least say so; that is the whole difference between a check that passed and a check that was
    /// never performed.
    ///
    /// Warns only when files were actually excluded, so aggregator modules and genuinely empty source
    /// trees stay quiet.
    protected void reportNothingToCheck(String goalName, boolean includeTests) {
        var excludedTests = includeTests
                            ? 0
                            : countJavaFiles(Option.option(testSourceDirectory).map(File::toPath));

        if (excludedTests > 0) {
            getLog().warn("JBCT " + goalName + " examined NOTHING in " + project.getArtifactId()
                         + ": no Java files under " + sourceDirectory
                         + ", and " + excludedTests
                         + " test file(s) under " + testSourceDirectory
                         + " were excluded because jbct.includeTests=false."
                         + " This module is NOT covered by this goal — a green result here is not evidence about those files."
                         + " Pass -Djbct.includeTests=true to examine them.");

            return;
        }

        getLog().info("No Java files found.");
    }

    private static int countJavaFiles(Option<Path> directory) {
        return directory.filter(Files::exists)
                        .map(AbstractJbctMojo::countJavaFilesIn)
                        .or(0);
    }

    private static int countJavaFilesIn(Path directory) {
        try (var paths = Files.walk(directory)) {
            return (int) paths.filter(Files::isRegularFile)
                              .filter(path -> path.toString().endsWith(".java"))
                              .count();
        } catch (IOException _) {
            // Counting is only ever used to make a message more honest; a failure here must not
            // turn a reporting path into a build failure.
            return 0;
        }
    }
}
