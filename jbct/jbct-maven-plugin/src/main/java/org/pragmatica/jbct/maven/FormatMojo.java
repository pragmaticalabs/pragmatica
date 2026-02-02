package org.pragmatica.jbct.maven;

import org.pragmatica.jbct.format.JbctFormatter;
import org.pragmatica.jbct.shared.SourceFile;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Maven goal for formatting Java source files according to JBCT style.
 */
@Mojo(name = "format", defaultPhase = LifecyclePhase.PROCESS_SOURCES)
public class FormatMojo extends AbstractJbctMojo {
    @Parameter(property = "jbct.includeTests", defaultValue = "true")
    protected boolean includeTests;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (shouldSkip("format")) {
            return;
        }
        var config = loadConfig();
        var formatter = JbctFormatter.jbctFormatter(config.formatter());
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
        getLog()
        .info("Formatted: " + formatted.get() + ", Unchanged: " + unchanged.get() + ", Errors: " + errors.get());
        if (errors.get() > 0) {
            throw new MojoFailureException("Formatting failed for " + errors.get() + " file(s)");
        }
    }

    private void processFile(Path file,
                             JbctFormatter formatter,
                             AtomicInteger formatted,
                             AtomicInteger unchanged,
                             AtomicInteger errors) {
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
                                                                                                                               getLog()
        .debug("Formatted: " + file);
                                                                                                                               return written;
                                                                                                                           }));
                                                       }))
                  .onFailure(cause -> {
                                 errors.incrementAndGet();
                                 getLog().error("Error formatting " + file + ": " + cause.message());
                             });
    }
}
