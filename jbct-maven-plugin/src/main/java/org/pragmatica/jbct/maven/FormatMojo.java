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
 * Maven goal for formatting Java source files according to JBCT style.
 */
@Mojo(name = "format", defaultPhase = LifecyclePhase.PROCESS_SOURCES)
public class FormatMojo extends AbstractMojo {

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
            getLog().info("Skipping JBCT format");
            return;
        }

        var formatter = JbctFormatter.jbctFormatter();
        var filesToProcess = collectJavaFiles();

        if (filesToProcess.isEmpty()) {
            getLog().info("No Java files found.");
            return;
        }

        getLog().info("Formatting " + filesToProcess.size() + " Java file(s)");

        var formatted = new AtomicInteger(0);
        var unchanged = new AtomicInteger(0);
        var errors = new AtomicInteger(0);

        for (var file : filesToProcess) {
            processFile(file, formatter, formatted, unchanged, errors);
        }

        getLog().info("Formatted: " + formatted.get() + ", Unchanged: " + unchanged.get() + ", Errors: " + errors.get());

        if (errors.get() > 0) {
            throw new MojoFailureException("Formatting failed for " + errors.get() + " file(s)");
        }
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

    private void processFile(Path file, JbctFormatter formatter, AtomicInteger formatted, AtomicInteger unchanged, AtomicInteger errors) {
        SourceFile.sourceFile(file)
                .flatMap(source -> formatter.isFormatted(source)
                        .flatMap(isFormatted -> {
                            if (isFormatted) {
                                unchanged.incrementAndGet();
                                return org.pragmatica.lang.Result.success(source);
                            }

                            return formatter.format(source)
                                    .flatMap(formattedSource -> formattedSource.write()
                                            .map(written -> {
                                                formatted.incrementAndGet();
                                                getLog().debug("Formatted: " + file);
                                                return written;
                                            }));
                        }))
                .onFailure(cause -> {
                    errors.incrementAndGet();
                    getLog().error("Error formatting " + file + ": " + cause.message());
                });
    }
}
