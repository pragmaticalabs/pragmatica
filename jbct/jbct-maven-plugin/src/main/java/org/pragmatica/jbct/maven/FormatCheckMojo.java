package org.pragmatica.jbct.maven;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.jbct.format.JbctFormatter;
import org.pragmatica.jbct.shared.SourceFile;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;


/// Maven goal for checking if Java source files are formatted according to JBCT style.
/// Does not modify files - only reports violations.
@Mojo(name = "format-check", defaultPhase = LifecyclePhase.VERIFY)
public class FormatCheckMojo extends AbstractJbctMojo {
    /// Whether `src/test/java` is collected alongside `src/main/java`.
    ///
    /// Declared per goal: there is no inherited field to shadow, which is what made this parameter
    /// inert for the format-family goals (#624). The default is `false` for every goal — test
    /// sources have never been in the gate, so honouring the value this parameter USED to claim
    /// would newly admit them wholesale; that is a policy change, deliberately not bundled with the
    /// mechanism fix. Set `-Djbct.includeTests=true` to opt in.
    @Parameter(property = "jbct.includeTests", defaultValue = "false")
    protected boolean includeTests;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (shouldSkip("format check")) {
            return;
        }

        var config = loadConfig();
        var formatter = JbctFormatter.jbctFormatter(config.formatter());
        var filesToProcess = collectJavaFiles(config.files(), includeTests);

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
            var fileList = new StringBuilder();

            for (var file : needsFormatting) {
                getLog().error("  " + file);
                fileList.append("\n  ").append(file);
            }

            throw new MojoFailureException(needsFormatting.size()
                                          + " file(s) are not properly formatted:" + fileList
                                          + "\nRun 'mvn jbct:format' to fix.");
        }

        if (errors.get() > 0) {
            throw new MojoFailureException("Format check failed for " + errors.get() + " file(s)");
        }

        getLog().info("All files are properly formatted.");
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
