/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package org.pragmatica.testlogging;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// Stops the raw-log4j2-configuration class from growing. `Configuration.addLogger` is a `putIfAbsent`, so a test
/// that registers a `LoggerConfig` and does not take it out again silently kills the capture of every later test in
/// the same JVM that uses the same logger name. [LogCapture] is the one place that does this and always restores.
///
/// Every `src/test` source outside this module that still touches the configuration by hand is listed in
/// `raw-log4j-allowlist.txt`. The list may only shrink: a new raw site fails here, and so does a listed file that no
/// longer matches (delete its entry, which is the point).
class LogCaptureUsageGuardTest {
    private static final Pattern RAW = Pattern.compile("addLogger\\(|new LoggerConfig\\(|addAppender\\(|extends AbstractAppender|new AbstractAppender\\(");
    private static final Set<String> SKIPPED_DIRS = Set.of("target", ".git", ".m2-local", "node_modules");
    private static final String ALLOW_LIST = "/raw-log4j-allowlist.txt";

    @Test
    void rawLog4jConfigurationManipulation_isOnlyInTheAllowList() throws IOException {
        var allowed = allowList();
        var matching = matchingSources(repositoryRoot());
        var unlisted = new TreeSet<>(matching);

        unlisted.removeAll(allowed);
        assertThat(unlisted).as("raw log4j2 configuration manipulation in test sources: use LogCapture instead").isEmpty();
    }

    @Test
    void allowList_hasNoEntryThatNoLongerMatches() throws IOException {
        var stale = new TreeSet<>(allowList());

        stale.removeAll(matchingSources(repositoryRoot()));
        assertThat(stale).as("allow-list entries that no longer contain raw log4j2 manipulation: delete them").isEmpty();
    }

    @Test
    void theScan_findsTheKnownRawSites() throws IOException {
        assertThat(matchingSources(repositoryRoot())).as("control: the scan reaches the repository's test sources")
                                                     .containsAll(allowList());
    }

    private static Path repositoryRoot() {
        var root = Path.of("").toAbsolutePath();

        while (root != null && !Files.exists(root.resolve("test-logging/pom.xml"))) {
            root = root.getParent();
        }
        if (root == null) {
            throw new AssertionError("repository root (the directory holding test-logging/pom.xml) not found above " + Path.of("").toAbsolutePath());
        }
        return root;
    }

    private static Set<String> allowList() throws IOException {
        try (var in = LogCaptureUsageGuardTest.class.getResourceAsStream(ALLOW_LIST)) {
            assertThat(in).as("allow-list resource " + ALLOW_LIST).isNotNull();
            return new String(in.readAllBytes()).lines()
                                                .map(String::strip)
                                                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                                                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        }
    }

    /// Repository-relative paths of every `src/test` java file, outside the `test-logging` module, that matches.
    private static Set<String> matchingSources(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(path -> path.toString().endsWith(".java"))
                        .filter(path -> isTestSource(root.relativize(path)))
                        .filter(LogCaptureUsageGuardTest::matches)
                        .map(path -> root.relativize(path).toString().replace('\\', '/'))
                        .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        }
    }

    private static boolean isTestSource(Path relative) {
        var parts = List.copyOf(toList(relative));

        return parts.contains("test")
               && parts.indexOf("src") >= 0
               && parts.indexOf("src") + 1 < parts.size()
               && "test".equals(parts.get(parts.indexOf("src") + 1))
               && parts.stream().noneMatch(SKIPPED_DIRS::contains)
               && !"test-logging".equals(parts.getFirst());
    }

    private static List<String> toList(Path path) {
        var names = new java.util.ArrayList<String>();

        path.forEach(name -> names.add(name.toString()));
        return names;
    }

    private static boolean matches(Path path) {
        try {
            return RAW.matcher(Files.readString(path)).find();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
