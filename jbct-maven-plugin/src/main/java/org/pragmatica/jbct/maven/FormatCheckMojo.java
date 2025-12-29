package org.pragmatica.jbct.maven;

import org.pragmatica.jbct.format.JbctFormatter;
import org.pragmatica.jbct.shared.SourceFile;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Maven goal for checking if Java source files are formatted according to JBCT style.
 * Does not modify files - only reports violations.
 */
@Mojo(name = "format-check", defaultPhase = LifecyclePhase.VERIFY)
public class FormatCheckMojo extends AbstractJbctMojo {
    @Parameter(property = "jbct.includeTests", defaultValue = "true")
    protected boolean includeTests;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (shouldSkip("format check")) {
            return;
        }
        var config = loadConfig();
        var formatter = JbctFormatter.jbctFormatter(config.formatter());
        var filesToProcess = collectJavaFiles();
        if (filesToProcess.isEmpty()) {
            getLog()
            .info("No Java files found.");
            return;
        }
        getLog()
        .info("Checking format of " + filesToProcess.size() + " Java file(s)");
        var needsFormatting = new ArrayList<Path>();
        var errors = new AtomicInteger(0);
        for (var file : filesToProcess) {
            checkFile(file, formatter, needsFormatting, errors);
        }
        if (!needsFormatting.isEmpty()) {
            getLog()
            .error("The following files are not properly formatted:");
            for (var file : needsFormatting) {
                getLog()
                .error("  " + file);
            }
            throw new MojoFailureException(needsFormatting.size()
                                           + " file(s) are not properly formatted. Run 'mvn jbct:format' to fix.");
        }
        if (errors.get() > 0) {
            throw new MojoFailureException("Format check failed for " + errors.get() + " file(s)");
        }
        getLog()
        .info("All files are properly formatted.");
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
                                 getLog()
                                 .error("Error checking " + file + ": " + cause.message());
                             });
    }
}
