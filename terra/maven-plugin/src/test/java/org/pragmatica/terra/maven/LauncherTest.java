// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.terra.maven;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/// Runs the generated Unix launcher template (`bin/terra`) against a stub `java` that prints its argv, one
/// `<arg>` per line, so the quoting and ordering of the JVM-options hook (#2804) are pinned on the real shell.
class LauncherTest {
    private static final String MAIN = "org.pragmatica.terra.launcher.TerraMain";

    @TempDir Path directory;

    private Path launcher;
    private Path javaHome;

    @BeforeEach void install() throws IOException {
        Assumptions.assumeFalse(System.getProperty("os.name").toLowerCase().contains("win"), "Unix launcher");
        var bin = Files.createDirectories(directory.resolve("dist/bin"));
        launcher = bin.resolve("terra");
        try (var input = Distribution.class.getResourceAsStream("/launchers/terra")) {
            Files.copy(java.util.Objects.requireNonNull(input, "Missing launcher template"), launcher);
        }
        javaHome = Files.createDirectories(directory.resolve("stub-jdk"));
        var stub = Files.createDirectories(javaHome.resolve("bin")).resolve("java");
        Files.writeString(stub, "#!/bin/sh\nfor a in \"$@\"; do printf '<%s>\\n' \"$a\"; done\n");
        stub.toFile().setExecutable(true, false);
    }

    @Test void launch_javaOptsUnset_keepsOnlyEnablePreviewBeforeClasspath() throws Exception {
        assertThat(beforeClasspath(run(javaHome, null))).containsExactly("--enable-preview");
    }

    @Test void launch_javaOptsEmpty_addsNoArgument() throws Exception {
        assertThat(beforeClasspath(run(javaHome, ""))).containsExactly("--enable-preview");
    }

    @Test void launch_javaOptsBlank_addsNoArgument() throws Exception {
        assertThat(beforeClasspath(run(javaHome, "  \t "))).containsExactly("--enable-preview");
    }

    @Test void launch_javaOptsWithSeveralOptions_passesEachBeforeClasspathInOrder() throws Exception {
        var argv = run(javaHome, "-Xmx512m -XX:+UseZGC\t-Dsample.key=value");
        assertThat(beforeClasspath(argv)).containsExactly("--enable-preview", "-Xmx512m", "-XX:+UseZGC", "-Dsample.key=value");
        assertThat(argv.get(argv.indexOf("-cp") + 2)).isEqualTo(MAIN);
    }

    @Test void launch_javaOptsWithPaddingAndRepeatedSpaces_dropsEmptyFields() throws Exception {
        assertThat(beforeClasspath(run(javaHome, "   -Xms64m    -Xmx128m  "))).containsExactly("--enable-preview", "-Xms64m", "-Xmx128m");
    }

    @Test void launch_javaOptsWithGlobCharacters_staysLiteral() throws Exception {
        Files.createFile(directory.resolve("dist/bin/-Dsample.pattern=matched"));
        Files.createFile(directory.resolve("dist/bin/-Dsample.other=a.txt"));
        var argv = run(javaHome, "-Dsample.pattern=* -Dsample.other=?.txt");
        assertThat(beforeClasspath(argv)).containsExactly("--enable-preview", "-Dsample.pattern=*", "-Dsample.other=?.txt");
    }

    @Test void launch_javaOptsWithQuotes_arePassedVerbatimNotParsed() throws Exception {
        assertThat(beforeClasspath(run(javaHome, "-Dsample.text=\"a b\""))).containsExactly("--enable-preview", "-Dsample.text=\"a", "b\"");
    }

    @Test void launch_javaOptsAndArguments_keepsArgumentsIntactAfterApplicationDirectory() throws Exception {
        var argv = run(javaHome, "-Xmx512m", "--check", "two words", "*", "");
        var afterMain = argv.subList(argv.indexOf(MAIN) + 1, argv.size());
        assertThat(afterMain.get(0)).endsWith("/application");
        assertThat(afterMain.subList(1, afterMain.size())).containsExactly("--check", "two words", "*", "");
    }

    @Test void launch_classpathIsLibraryWildcardOfDistribution_independentOfJavaOpts() throws Exception {
        var argv = run(javaHome, "-Xmx512m");
        assertThat(argv.get(argv.indexOf("-cp") + 1)).endsWith("/dist/lib/*");
    }

    @Test void launch_javaOptsReachRealJvm_heapLimitAppearsInFlagsFinal() throws Exception {
        var jdk = Path.of(System.getProperty("java.home"));
        var output = runRaw(jdk, "-Xmx64m -XX:+PrintFlagsFinal");
        assertThat(output.lines().filter(line -> line.contains(" MaxHeapSize ")).toList())
            .hasSize(1)
            .allSatisfy(line -> assertThat(line).containsPattern("= 67108864\\b"));
    }

    @Test void windowsLauncher_javaOptsExpandedBetweenEnablePreviewAndClasspath() throws Exception {
        try (var input = Distribution.class.getResourceAsStream("/launchers/terra.cmd")) {
            var text = new String(java.util.Objects.requireNonNull(input, "Missing launcher template").readAllBytes(), StandardCharsets.UTF_8);
            assertThat(text).contains("--enable-preview %JAVA_OPTS% -cp ");
        }
    }

    private List<String> beforeClasspath(List<String> argv) {
        return argv.subList(0, argv.indexOf("-cp"));
    }

    private List<String> run(Path home, String javaOpts, String... arguments) throws Exception {
        var lines = new ArrayList<String>();
        runRaw(home, javaOpts, arguments).lines().forEach(line -> lines.add(line.substring(1, line.length() - 1)));
        return lines;
    }

    private String runRaw(Path home, String javaOpts, String... arguments) throws Exception {
        var command = new ArrayList<String>(List.of("sh", launcher.toString()));
        command.addAll(List.of(arguments));
        var builder = new ProcessBuilder(command).directory(launcher.getParent().toFile()).redirectErrorStream(true);
        builder.environment().put("JAVA_HOME", home.toString());
        builder.environment().remove("JAVA_OPTS");
        if (javaOpts != null) {
            builder.environment().put("JAVA_OPTS", javaOpts);
        }
        var process = builder.start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        return output;
    }
}
