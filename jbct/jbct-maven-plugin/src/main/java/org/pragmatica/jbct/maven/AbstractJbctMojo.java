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
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;


/// Base class for JBCT Maven mojos with common configuration parameters.
public abstract class AbstractJbctMojo extends AbstractMojo {
    private static final String MODE_PROPERTY = "jbct.mode";

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    protected MavenProject project;

    /// Read only for the mode override (C7): `jbct.mode` is deliberately NOT a `@Parameter(property = ...)`, because
    /// Maven resolves such a property from the pom's `<properties>` and from system properties as well, and only a
    /// command-line user property may override `jbct.toml`.
    @Parameter(defaultValue = "${session}", readonly = true)
    protected MavenSession session;

    @Parameter(property = "jbct.sourceDirectory", defaultValue = "${project.build.sourceDirectory}")
    protected File sourceDirectory;

    @Parameter(property = "jbct.testSourceDirectory", defaultValue = "${project.build.testSourceDirectory}")
    protected File testSourceDirectory;

    @Parameter(property = "jbct.skip", defaultValue = "false")
    protected boolean skip;

    /// Load JBCT configuration from project directory.
    ///
    /// A layer that cannot be used (syntax error, unreadable file, wrong-typed key) fails the goal
    /// with a message naming the file and the offending key or line (#1455); the build log shows it.
    @Contract
    protected JbctConfig loadConfig() throws MojoExecutionException {
        var projectDir = project.getBasedir().toPath();
        var explicitMode = explicitMode();
        var loaded = ConfigLoader.loadForSourceRoot(Option.none(),
                                                    projectDir)
                                 .flatMap(config -> config.overrideMode(explicitMode));
        var refusal = loaded.fold(cause -> Option.some(cause.message()),
                                  _ -> Option.<String> none());

        if (refusal.isPresent()) {
            throw new MojoExecutionException(refusal.unwrap());
        }

        return loaded.unwrap();
    }

    /// The mode given on the invocation (C7): the session's user property `jbct.mode`, which a command-line `-D` and a
    /// `-D` line of `.mvn/maven.config` both populate. A `<jbct.mode>` in the pom's `<properties>`, and a `jbct.mode`
    /// JVM system property (`MAVEN_OPTS`), override nothing: each is ignored with a warning naming `jbct.toml`.
    private Option<String> explicitMode() {
        var userMode = Option.option(session)
                             .map(MavenSession::getUserProperties)
                             .flatMap(properties -> Option.option(properties.getProperty(MODE_PROPERTY)));

        warnIgnoredModeSource("<" + MODE_PROPERTY + "> in the pom's <properties>",
                              Option.option(project.getProperties()).flatMap(properties -> Option.option(properties.getProperty(MODE_PROPERTY))));
        warnIgnoredModeSource("The " + MODE_PROPERTY + " system property",
                              userMode.isPresent()
                              ? Option.<String> none()
                              : Option.option(System.getProperty(MODE_PROPERTY)));

        return userMode;
    }

    @Contract
    private void warnIgnoredModeSource(String source, Option<String> value) {
        value.onPresent(raw -> getLog().warn(source
                                            + " (" + raw
                                            + ") does not set the JBCT mode and is ignored:"
                                            + " set [lint] mode in jbct.toml, or pass -D" + MODE_PROPERTY
                                            + " on the command line to override it for this run."));
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
            getLog().warn("JBCT " + goalName
                         + " examined NOTHING in " + project.getArtifactId()
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
                              .filter(path -> path.toString()
                                                  .endsWith(".java"))
                              .count();
        } catch (IOException _) {
            // Counting is only ever used to make a message more honest; a failure here must not
            // turn a reporting path into a build failure.
            return 0;
        }
    }
}
