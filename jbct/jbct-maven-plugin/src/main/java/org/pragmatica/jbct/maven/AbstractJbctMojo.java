package org.pragmatica.jbct.maven;

import org.pragmatica.jbct.config.ConfigLoader;
import org.pragmatica.jbct.config.JbctConfig;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.shared.FileCollector;
import org.pragmatica.lang.Option;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

/**
 * Base class for JBCT Maven mojos with common configuration parameters.
 */
public abstract class AbstractJbctMojo extends AbstractMojo {
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    protected MavenProject project;

    @Parameter(property = "jbct.sourceDirectory", defaultValue = "${project.build.sourceDirectory}")
    protected File sourceDirectory;

    @Parameter(property = "jbct.testSourceDirectory", defaultValue = "${project.build.testSourceDirectory}")
    protected File testSourceDirectory;

    @Parameter(property = "jbct.skip", defaultValue = "false")
    protected boolean skip;

    @Parameter(property = "jbct.includeTests", defaultValue = "false")
    protected boolean includeTests;

    /**
     * Load JBCT configuration from project directory.
     */
    protected JbctConfig loadConfig() {
        var projectDir = project.getBasedir()
                                .toPath();
        return ConfigLoader.load(Option.none(), Option.option(projectDir));
    }

    /**
     * Create lint context from configuration.
     */
    protected LintContext createLintContext(JbctConfig config) {
        return LintContext.fromConfig(config);
    }

    /**
     * Collect Java files from source directories.
     */
    protected List<Path> collectJavaFiles() {
        return FileCollector.collectFromDirectories(Option.option(sourceDirectory)
                                                          .map(File::toPath),
                                                    Option.option(testSourceDirectory)
                                                          .map(File::toPath),
                                                    includeTests,
                                                    msg -> getLog().warn(msg));
    }

    /**
     * Check if this mojo should be skipped.
     */
    protected boolean shouldSkip(String goalName) {
        if (skip) {
            getLog().info("Skipping JBCT " + goalName);
            return true;
        }
        return false;
    }
}
