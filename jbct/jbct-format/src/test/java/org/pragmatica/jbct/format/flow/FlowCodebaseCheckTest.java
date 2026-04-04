package org.pragmatica.jbct.format.flow;

import org.junit.jupiter.api.Test;
import org.pragmatica.jbct.shared.SourceFile;
import org.pragmatica.jbct.format.FormatterConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class FlowCodebaseCheckTest {

    @Test
    @SuppressWarnings("JBCT-PAT-01")
    void formatEntireCodebase_noErrorsAndIdempotent() throws IOException {
        var config = FormatterConfig.defaultConfig();
        var formatter = FlowFormatter.flowFormatter(config);
        var root = Path.of("").toAbsolutePath();

        // Walk up to find project root (has pom.xml with <artifactId>pragmatica</artifactId>)
        var projectRoot = root;
        while (projectRoot != null && !Files.exists(projectRoot.resolve("jbct/jbct-format/pom.xml"))) {
            projectRoot = projectRoot.getParent();
        }
        assertThat(projectRoot).isNotNull();

        var files = Files.walk(projectRoot)
                         .filter(p -> p.toString().endsWith(".java"))
                         .filter(p -> !p.toString().contains("/target/"))
                         .filter(p -> !p.toString().contains("Java25Parser.java"))
                         .filter(p -> { try { return Files.size(p) < 1_000_000; } catch (Exception e) { return true; } })
                         .sorted()
                         .toList();

        int total = 0, unchanged = 0, formatted = 0, errors = 0, nonIdempotent = 0;
        var errorFiles = new ArrayList<String>();
        var changedFiles = new ArrayList<String>();
        var nonIdempotentFiles = new ArrayList<String>();

        for (var file : files) {
            total++;
            try {
                var source = SourceFile.sourceFile(file).unwrap();
                var result = formatter.format(source);
                if (result.isFailure()) {
                    errors++;
                    errorFiles.add(projectRoot.relativize(file) + ": format failed");
                    continue;
                }
                var formattedSource = result.unwrap();
                if (formattedSource.content().equals(source.content())) {
                    unchanged++;
                } else {
                    formatted++;
                    changedFiles.add(projectRoot.relativize(file).toString());
                }
                // Idempotency check
                var second = formatter.format(formattedSource);
                if (second.isFailure()) {
                    nonIdempotent++;
                    nonIdempotentFiles.add(projectRoot.relativize(file) + ": second pass failed");
                } else if (!second.unwrap().content().equals(formattedSource.content())) {
                    nonIdempotent++;
                    nonIdempotentFiles.add(projectRoot.relativize(file).toString());
                }
            } catch (Exception e) {
                errors++;
                errorFiles.add(projectRoot.relativize(file) + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }

        System.out.println("=== Flow Formatter Codebase Check ===");
        System.out.println("Total files:      " + total);
        System.out.println("Unchanged:        " + unchanged);
        System.out.println("Would reformat:   " + formatted);
        System.out.println("Errors:           " + errors);
        System.out.println("Non-idempotent:   " + nonIdempotent);

        if (!errorFiles.isEmpty()) {
            System.out.println("\n--- ERRORS (first 30) ---");
            errorFiles.stream().limit(30).forEach(System.out::println);
        }
        if (!changedFiles.isEmpty()) {
            System.out.println("\n--- WOULD REFORMAT (first 50) ---");
            changedFiles.stream().limit(50).forEach(System.out::println);
        }
        if (!nonIdempotentFiles.isEmpty()) {
            System.out.println("\n--- NON-IDEMPOTENT (first 30) ---");
            nonIdempotentFiles.stream().limit(30).forEach(System.out::println);
            // Show diff for first non-idempotent file
            final var pRoot = projectRoot;
            files.stream()
                .filter(f -> {
                    try {
                        var s = SourceFile.sourceFile(f).unwrap();
                        var r1 = formatter.format(s);
                        if (r1.isFailure()) return false;
                        var r2 = formatter.format(r1.unwrap());
                        return r2.isSuccess() && !r2.unwrap().content().equals(r1.unwrap().content());
                    } catch (Exception e) { return false; }
                })
                .findFirst()
                .ifPresent(f -> {
                    try {
                        var s = SourceFile.sourceFile(f).unwrap();
                        var p1 = formatter.format(s).unwrap().content();
                        var p2 = formatter.format(SourceFile.sourceFile(f, p1)).unwrap().content();
                        var l1 = p1.split("\n", -1);
                        var l2 = p2.split("\n", -1);
                        System.out.println("\n--- DIFF in " + pRoot.relativize(f) + " ---");
                        int shown = 0;
                        for (int i = 0; i < Math.min(l1.length, l2.length) && shown < 5; i++) {
                            if (!l1[i].equals(l2[i])) {
                                System.out.println("L" + (i+1) + " pass1: [" + l1[i] + "]");
                                System.out.println("L" + (i+1) + " pass2: [" + l2[i] + "]");
                                shown++;
                            }
                        }
                        if (l1.length != l2.length) System.out.println("Lines: " + l1.length + " vs " + l2.length);
                    } catch (Exception e) { System.out.println("Diff error: " + e); }
                });
        }

        assertThat(errors).as("Files with format errors").isZero();
        assertThat(nonIdempotent).as("Non-idempotent files").isZero();
    }
}
