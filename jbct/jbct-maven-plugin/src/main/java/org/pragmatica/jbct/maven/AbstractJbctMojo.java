package org.pragmatica.jbct.maven;

import java.io.File;
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
}
