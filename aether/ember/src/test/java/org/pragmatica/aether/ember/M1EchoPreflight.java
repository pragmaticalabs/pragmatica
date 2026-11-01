// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.TreeMap;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

import org.pragmatica.aether.slice.repository.maven.MavenLocalRepoLocator;

/// S6 pre-flight (#2512): is the echo slice the node will resolve from the local Maven repository the one built from THIS tree?
///
/// `echo-slice` is a declared test dependency of Ember, so a reactor build compiles it first; but the slice jar the nodes load is made at
/// `package` and installed at `install`, so a `test`-phase run never refreshes it, and a seeded `.m2-local` can hold another branch's jar
/// (memory `seeded-m2-carries-foreign-branch-jars`). The comparison is by CONTENT, never mtime: every `.class` and slice manifest in the
/// installed jar must be byte-equal to the file `echo-slice` compiled into `target/classes`, and the two sets must be the same set.
final class M1EchoPreflight {
    private static final String ARTIFACT_PATH = "org/pragmatica-lite/aether/test/echo-slice-echo-service";

    private M1EchoPreflight() {}

    /// `ok` is true only when the jar and the compiled classes hold the same entries with the same bytes. `digest` is the SHA-256 of the sorted
    /// (name, bytes) pairs of the compared set (the jar's side), so two runs can be compared by one string.
    record Outcome(boolean ok, int compared, String digest, String detail) {}

    static Path installedJar(String version) {
        return Path.of(MavenLocalRepoLocator.findLocalRepository(), ARTIFACT_PATH, version, "echo-slice-echo-service-" + version + ".jar");
    }

    static Path headClasses() {
        return Path.of(System.getProperty("m1.echoClasses", "../e2e-tests/echo-slice/target/classes")).toAbsolutePath().normalize();
    }

    static Outcome check(String version) throws IOException {
        var jar = installedJar(version);

        if (!Files.isRegularFile(jar)) {
            return new Outcome(false, 0, "", "installed slice jar missing: " + jar + " (run: mvn -pl aether/e2e-tests/echo-slice install)");
        }
        return compare(jar, headClasses());
    }

    static Outcome compare(Path jarPath, Path classes) throws IOException {
        if (!Files.isDirectory(classes)) {
            return new Outcome(false, 0, "", "HEAD build output missing: " + classes + " (build echo-slice from this tree first)");
        }
        var installed = new TreeMap<String, byte[]>();

        try (var jar = new JarFile(jarPath.toFile())) {
            for (var entries = jar.entries(); entries.hasMoreElements(); ) {
                var entry = entries.nextElement();

                if (relevant(entry.getName())) {
                    try (var in = jar.getInputStream(entry)) {
                        installed.put(entry.getName(), in.readAllBytes());
                    }
                }
            }
        }
        var head = new TreeMap<String, byte[]>();

        try (var walk = Files.walk(classes)) {
            for (var file : walk.filter(Files::isRegularFile).toList()) {
                var name = classes.relativize(file).toString().replace('\\', '/');

                if (relevant(name)) {
                    head.put(name, Files.readAllBytes(file));
                }
            }
        }
        if (installed.isEmpty() || head.isEmpty()) {
            return new Outcome(false, 0, "", "nothing to compare: installed=" + installed.size() + " head=" + head.size());
        }
        var problems = problems(installed, head);

        return new Outcome(problems.isEmpty(), installed.size(), digest(installed), problems.isEmpty() ? "same content as " + classes : problems);
    }

    private static boolean relevant(String name) {
        return name.endsWith(".class") || name.startsWith("META-INF/slice/") && name.endsWith(".manifest");
    }

    private static String problems(TreeMap<String, byte[]> installed, TreeMap<String, byte[]> head) {
        var out = new java.util.ArrayList<String>();

        installed.forEach((name, bytes) -> {
            if (!head.containsKey(name)) {
                out.add("only in installed jar: " + name);
            } else if (!Arrays.equals(bytes, head.get(name))) {
                out.add("content differs: " + name);
            }
        });
        head.keySet().stream().filter(name -> !installed.containsKey(name)).forEach(name -> out.add("only in HEAD build: " + name));
        return out.stream().limit(10).collect(Collectors.joining("; "));
    }

    private static String digest(TreeMap<String, byte[]> entries) {
        try {
            var sha = MessageDigest.getInstance("SHA-256");

            entries.forEach((name, bytes) -> {
                sha.update(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                sha.update((byte) 0);
                sha.update(bytes);
            });
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
