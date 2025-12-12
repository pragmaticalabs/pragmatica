package org.pragmatica.jbct.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.pragmatica.jbct.format.JbctFormatter;
import org.pragmatica.jbct.shared.SourceFile;
import org.pragmatica.jbct.shared.SourceRoot;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Maven goal for checking if Java source files are formatted according to JBCT style.
 * Does not modify files - only reports violations.
 */
@Mojo(name = "format-check", defaultPhase = LifecyclePhase.VERIFY)
public class FormatCheckMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(property = "jbct.sourceDirectory", defaultValue = "${project.build.sourceDirectory}")
    private File sourceDirectory;

    @Parameter(property = "jbct.testSourceDirectory", defaultValue = "${project.build.testSourceDirectory}")
    private File testSourceDirectory;

    @Parameter(property = "jbct.includes", defaultValue = "**/*.java")
    private List<String> includes;

    @Parameter(property = "jbct.excludes")
    private List<String> excludes;

    @Parameter(property = "jbct.skip", defaultValue = "false")
    private boolean skip;

    @Parameter(property = "jbct.includeTests", defaultValue = "true")
    private boolean includeTests;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Skipping JBCT format check");
            return;
        }

        var formatter = JbctFormatter.jbctFormatter();
        var filesToProcess = collectJavaFiles();

        if (filesToProcess.isEmpty()) {
            getLog().info("No Java files found.");
            return;
        }

        getLog().info("Checking format of " + filesToProcess.size() + " Java file(s)");

        var needsFormatting = new ArrayList<Path>();
        var errors = new AtomicInteger(0);

        for (var file : filesToProcess) {
            checkFile(file, formatter, needsFormatting, errors);
        }

        if (!needsFormatting.isEmpty()) {
            getLog().error("The following files are not properly formatted:");
            for (var file : needsFormatting) {
                getLog().error("  " + file);
            }
            throw new MojoFailureException(needsFormatting.size() + " file(s) are not properly formatted. Run 'mvn jbct:format' to fix.");
        }

        if (errors.get() > 0) {
            throw new MojoFailureException("Format check failed for " + errors.get() + " file(s)");
        }

        getLog().info("All files are properly formatted.");
    }

    private List<Path> collectJavaFiles() {
        var files = new ArrayList<Path>();

        if (sourceDirectory != null && sourceDirectory.exists()) {
            collectFromDirectory(sourceDirectory.toPath(), files);
        }

        if (includeTests && testSourceDirectory != null && testSourceDirectory.exists()) {
            collectFromDirectory(testSourceDirectory.toPath(), files);
        }

        return files;
    }

    private void collectFromDirectory(Path directory, List<Path> files) {
        SourceRoot.sourceRoot(directory)
                .flatMap(SourceRoot::findJavaFiles)
                .onSuccess(files::addAll)
                .onFailure(cause -> getLog().warn("Error scanning " + directory + ": " + cause.message()));
    }

    private void checkFile(Path file, JbctFormatter formatter, List<Path> needsFormatting, AtomicInteger errors) {
        SourceFile.sourceFile(file)
                .flatMap(formatter::isFormatted)
                .onSuccess(isFormatted -> {
                    if (!isFormatted) {
                        needsFormatting.add(file);
                    }
                })
                .onFailure(cause -> {
                    errors.incrementAndGet();
                    getLog().error("Error checking " + file + ": " + cause.message());
                });
    }
}
