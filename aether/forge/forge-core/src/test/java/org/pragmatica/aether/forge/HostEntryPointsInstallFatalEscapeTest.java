// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/// #2652 (KVStore dispatcher design 8.1, "Who installs what"): every production host installs the fatal-escape handler
/// as the first statement of its `main`. The hosts are pinned from source, the way `MainFatalEscapeInstallTest` pins
/// `Main` and `NodeLauncherExitOnOomPinnedTest` pins the launchers, because a `main` that boots a node cannot be run
/// from a unit test.
///
/// - `Main` and the Forge launcher install; the install is before any other statement of `main`. Mutation: delete the
///   Forge install call (RED).
/// - Ember has no `main`: it runs inside the test JVM, where the JUnit extension (`FatalEscapeRecorder`) is the only
///   installer (design 8.1 "One installer per JVM"), so there is nothing for Ember to install.
/// - `AetherCli` and `AetherUp` are named in the design as buffer-only (nobody installs).
/// - The set of `main`s under `aether/**/src/main` is pinned, so a new launcher must be classified here, installer or
///   buffer-only, instead of silently shipping without a handler. Mutation: add a `main` to a new class (RED).
class HostEntryPointsInstallFatalEscapeTest {
    private static final String MAIN = "aether/node/src/main/java/org/pragmatica/aether/Main.java";
    private static final String FORGE = "aether/forge/forge-core/src/main/java/org/pragmatica/aether/forge/ForgeServer.java";
    private static final Set<String> INSTALLERS = Set.of("Main.java", "ForgeServer.java");
    private static final Set<String> BUFFER_ONLY = Set.of("AetherCli.java", "AetherUp.java");

    @Test
    void forgeLauncherMain_installsTheFatalEscapeRelay_beforeAnythingElse() throws Exception {
        var source = Files.readString(repositoryRoot().resolve(FORGE));
        var main = source.indexOf("public static void main(String[] args) {");
        var install = source.indexOf("Main.installHostFatalEscapeRelay(", main);
        var firstOther = source.indexOf("hasFlag(args, \"--help\"", main);

        assertThat(main).as("control: ForgeServer has a main").isNotNegative();
        assertThat(firstOther).as("control: main reads its flags").isGreaterThan(main);
        assertThat(install).as("main installs the fatal-escape relay").isGreaterThan(main);
        assertThat(install).as("the install is before main's other work").isLessThan(firstOther);
    }

    @Test
    void mainMethodsUnderAether_areExactlyTheClassifiedHosts() throws Exception {
        var found = mainFiles().stream().map(path -> path.getFileName().toString()).collect(Collectors.toSet());

        assertThat(found).as("control: the scan reaches the known hosts").contains("Main.java", "ForgeServer.java");
        assertThat(found).as("every main is an installer or named buffer-only")
                         .allMatch(name -> INSTALLERS.contains(name) || BUFFER_ONLY.contains(name));
    }

    @Test
    void everyInstallerMain_callsTheFatalEscapeInstall() throws Exception {
        var installers = mainFiles().stream().filter(path -> INSTALLERS.contains(path.getFileName().toString())).toList();

        assertThat(installers).as("control: both installers found").hasSize(INSTALLERS.size());
        for (var path : installers) {
            assertThat(Files.readString(path)).as(path.getFileName() + " installs the relay").contains("FatalEscapeRelay(");
        }
    }

    private static Set<Path> mainFiles() throws Exception {
        try (var files = Files.walk(repositoryRoot().resolve("aether"))) {
            return files.filter(path -> path.toString().contains("/src/main/java/"))
                        .filter(path -> path.toString().endsWith(".java"))
                        .filter(HostEntryPointsInstallFatalEscapeTest::declaresMain)
                        .collect(Collectors.toSet());
        }
    }

    private static boolean declaresMain(Path path) {
        try {
            return Files.readString(path).contains("static void main(");
        } catch (IOException e) {
            return fail("cannot read " + path + ": " + e.getMessage());
        }
    }

    private static Path repositoryRoot() throws Exception {
        var current = Path.of(HostEntryPointsInstallFatalEscapeTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());

        while (current != null && !Files.isRegularFile(current.resolve(MAIN))) {
            current = current.getParent();
        }

        return current == null ? fail("repository root not found above the test's code source") : current;
    }
}
