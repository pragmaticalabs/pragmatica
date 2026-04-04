package org.pragmatica.jbct.format.flow;

import org.junit.jupiter.api.Test;
import org.pragmatica.jbct.shared.SourceFile;
import org.pragmatica.jbct.format.FormatterConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

class FlowIdempotencyDebugTest {

    @Test
    @SuppressWarnings("JBCT-PAT-01")
    void debugIdempotency() throws IOException {
        var config = FormatterConfig.defaultConfig();
        var formatter = FlowFormatter.flowFormatter(config);
        var root = Path.of("").toAbsolutePath();
        var projectRoot = root;
        while (projectRoot != null && !Files.exists(projectRoot.resolve("jbct/jbct-format/pom.xml"))) {
            projectRoot = projectRoot.getParent();
        }

        // Pick 5 diverse files
        var testFiles = Files.walk(projectRoot)
                             .filter(p -> p.toString().endsWith(".java"))
                             .filter(p -> !p.toString().contains("/target/"))
                             .filter(p -> !p.toString().contains("Java25Parser.java"))
                             .filter(p -> { try { return Files.size(p) < 1_000_000; } catch (Exception e) { return true; } })
                             .sorted()
                             .limit(2000)
                             .toList();

        int nonIdempotent = 0;
        for (var file : testFiles) {
            var source = SourceFile.sourceFile(file).unwrap();
            var pass1Result = formatter.format(source);
            if (pass1Result.isFailure()) {
                System.out.println("FAIL: " + projectRoot.relativize(file) + " — format error");
                continue;
            }
            var pass1 = pass1Result.unwrap();
            var pass2Result = formatter.format(pass1);
            if (pass2Result.isFailure()) {
                var relPath = projectRoot.relativize(file).toString();
                // Write first-pass output to temp for inspection
                java.nio.file.Files.writeString(Path.of("/tmp/flow-bad-output.java"), pass1.content());
                java.nio.file.Files.writeString(Path.of("/tmp/flow-bad-file.txt"),
                    relPath + "\nError: " + pass2Result + "\n",
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
                System.out.println("FAIL: " + relPath + " — second pass error");
                nonIdempotent++;
                continue;
            }
            var pass2 = pass2Result.unwrap();
            if (!pass1.content().equals(pass2.content())) {
                nonIdempotent++;
                var relPath = projectRoot.relativize(file).toString();
                // Write to temp file since surefire swallows stdout/stderr
                java.nio.file.Files.writeString(Path.of("/tmp/flow-non-idempotent.txt"),
                    relPath + "\n", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                System.out.println("\n=== NON-IDEMPOTENT: " + relPath + " ===");
                var lines1 = pass1.content().split("\n", -1);
                var lines2 = pass2.content().split("\n", -1);
                int diffCount = 0;
                for (int i = 0; i < Math.min(lines1.length, lines2.length); i++) {
                    if (!lines1[i].equals(lines2[i])) {
                        diffCount++;
                        if (diffCount <= 3) {
                            System.out.println("  Line " + (i + 1) + ":");
                            System.out.println("    pass1: [" + lines1[i] + "]");
                            System.out.println("    pass2: [" + lines2[i] + "]");
                        }
                    }
                }
                if (lines1.length != lines2.length) {
                    System.out.println("  Line count differs: pass1=" + lines1.length + " pass2=" + lines2.length);
                }
                System.out.println("  Total different lines: " + diffCount);
            }
        }
        System.out.println("\nNon-idempotent: " + nonIdempotent + "/" + testFiles.size());
    }
}
