// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.deadsurface;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// #519's corpus discovery: every module declared in `aether/pom.xml`'s default `<modules>` list —
/// followed through aggregator poms down to their children — read by its compiled production output (`<module>/target/classes`) — NOT by reading this JVM's own
/// classpath.
///
/// A classpath read only ever sees `aether/node`'s own dependency closure. That is wrong for this
/// check: a config record's real, sole consumer can live in a module `node` does not depend on at all
/// (`aether/cli` depends on `aether-config` directly, as a sibling of `node`, not through it) — a
/// classpath-based corpus would false-DEAD every accessor of that record the moment `node` itself
/// stopped calling it, which is exactly the disqualifying failure mode this gate exists to avoid.
/// Reading `aether/pom.xml`'s own module list has no such blind spot: it is the actual reactor
/// definition, not a proxy for it.
///
/// (That paragraph was written when the gate was mounted in `aether/node`. Since the move to
/// `aether/dead-surface-gate` this module test-depends on every module it scans, so its classpath no
/// longer has the blind spot — but the classpath still must not be the corpus, for a second reason
/// the commissioning test's positive controls depend on: it carries test classes and third-party jars,
/// and "called only from a test" has to read as DEAD.)
///
/// Deliberately scoped to the `<modules>` blocks reachable from `aether/pom.xml` outside any `<profile>`
/// — not a filesystem walk under `aether/`, and not the whole monorepo. An aggregator pom has no classes
/// of its own, so stopping at it would silently drop its children (27 modules once: `environment/*`,
/// `resource/*`, `forge/*`, `pg-tools/*`), and a config accessor read only there would false-DEAD.
/// Two things outside that walk are NOT modules of the default reactor and must not be treated as ones:
/// profile-gated modules (`e2e-tests`, `cloud-tests`, `forge-tests`, activated only via profiles), and stray `pom.xml`
/// files elsewhere under `aether/` that aren't reactor modules at all (`aether/tests/blueprints/*` are
/// built by a separate script step, not this reactor, and don't depend on `aether-config` — confirmed
/// by grep). Sweeping the whole monorepo root was tried and rejected for the same reason: `aether-config`
/// is BSL-licensed and consumed only from inside `aether/**` (no `pom.xml` under `jbct/`,
/// `integrations/`, `examples/`, or `testing/` depends on it), so including them cannot find a live
/// caller — it can only force an unrelated full-monorepo build before every run of this test.
///
/// This does carry one real precondition, made loud rather than silent: a module that has never been
/// compiled in this working copy contributes nothing to the sweep. [#missingProductionOutput] exists
/// so the gate fails with a build instruction instead of silently scanning an incomplete corpus.
final class ReactorRoots {
    private static final Pattern MODULE_TAG = Pattern.compile("<module>([^<]+)</module>");
    private static final Pattern XML_COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern PROFILES_BLOCK = Pattern.compile("<profiles>.*?</profiles>", Pattern.DOTALL);

    private ReactorRoots() {}

    /// This class's own `target/test-classes` directory is three path segments below the `aether/`
    /// reactor root (`<root>/aether/dead-surface-gate/target/test-classes`) — walking up from it is
    /// deterministic regardless of the working directory `mvn` was invoked from, unlike `user.dir`.
    /// The count survived the move from `aether/node` unchanged because the gate's module, like every
    /// module of this reactor, is a direct child directory of `aether/`; mount it any deeper and this
    /// self-check fires.
    static Path aetherRoot() {
        var testClasses = codeSourceLocation();
        var root = testClasses.getParent().getParent().getParent();

        if (root == null || !"aether".equals(String.valueOf(root.getFileName()))) {
            throw new IllegalStateException("ReactorRoots.aetherRoot() computed " + root +
                                             " from " + testClasses + ", whose name is not \"aether\" — " +
                                             "the three-levels-up assumption (target/test-classes -> " +
                                             "dead-surface-gate -> aether) no longer holds; fix the walk-up count.");
        }

        return root;
    }

    private static Path codeSourceLocation() {
        try {
            return Path.of(ReactorRoots.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /// The corpus modules: every non-aggregator module reachable from `aether/pom.xml`'s default
    /// `<modules>` block, following nested `<modules>` blocks transitively (`environment/*`,
    /// `resource/*`, `resource/services/*`, `forge/*`, `pg-tools/*`). An aggregator (`packaging=pom`
    /// with children) has no classes of its own, so it is not itself returned — its children are.
    ///
    /// Only the top-level `<modules>` of each pom is read: `<profiles>` are stripped first, so a
    /// profile-gated module (`e2e-tests`, `cloud-tests` in `aether/pom.xml`; `forge-tests` under
    /// `forge/pom.xml`) is never part of the corpus — it is not built by the default reactor, so its
    /// `target/classes` would not exist at gate time.
    static List<Path> declaredModules() {
        return declaredModules(aetherRoot());
    }

    /// [#declaredModules()] against an explicit `aether/`-shaped root, so the corpus walk can be
    /// exercised on a fixture tree.
    static List<Path> declaredModules(Path aetherRoot) {
        var leaves = new ArrayList<Path>();
        var topLevel = moduleDirectories(aetherRoot);

        if (topLevel.isEmpty()) {
            throw new IllegalStateException("No <modules> block found in " + aetherRoot.resolve("pom.xml") +
                                             " — has the reactor declaration moved or changed shape? " +
                                             "Update this parser.");
        }

        collectLeaves(topLevel, leaves);
        return leaves;
    }

    private static void collectLeaves(List<Path> modules, List<Path> leaves) {
        for (var module : modules) {
            var children = moduleDirectories(module);

            if (children.isEmpty()) {
                leaves.add(module);
            } else {
                collectLeaves(children, leaves);
            }
        }
    }

    /// The directories named by `<module>` tags in `directory/pom.xml`'s own `<modules>` block (empty
    /// when the pom declares none). A declared module without a `pom.xml` fails loudly: silently
    /// skipping it would shrink the corpus, which is the false-DEAD failure mode.
    private static List<Path> moduleDirectories(Path directory) {
        var pomXml = directory.resolve("pom.xml");

        if (!Files.isRegularFile(pomXml)) {
            throw new IllegalStateException("Declared module " + directory + " has no pom.xml — the corpus " +
                                             "walk cannot read it.");
        }

        var content = withoutProfiles(readString(pomXml));
        var modulesStart = content.indexOf("<modules>");
        var modulesEnd = content.indexOf("</modules>", modulesStart);

        if (modulesStart < 0 || modulesEnd < 0) {
            return List.of();
        }

        var modules = new ArrayList<Path>();
        Matcher matcher = MODULE_TAG.matcher(content.substring(modulesStart, modulesEnd));

        while (matcher.find()) {
            modules.add(directory.resolve(matcher.group(1).trim()));
        }

        return modules;
    }

    private static String withoutProfiles(String pom) {
        return PROFILES_BLOCK.matcher(XML_COMMENT.matcher(pom).replaceAll("")).replaceAll("");
    }

    private static String readString(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /// Every declared module's `target/classes` directory that actually exists — one per module
    /// compiled at least once since its last `clean`.
    static List<Path> productionRoots() {
        return productionRoots(aetherRoot());
    }

    static List<Path> productionRoots(Path aetherRoot) {
        return declaredModules(aetherRoot).stream()
                                          .map(module -> module.resolve("target/classes"))
                                          .filter(Files::isDirectory)
                                          .toList();
    }

    /// Declared modules with production Java sources (`src/main/java`) whose `target/classes` is
    /// missing — an incomplete corpus, which the permanent gate must refuse to trust rather than
    /// silently scan (main's condition: false-DEAD is the disqualifying failure mode, and a module that
    /// was never compiled is indistinguishable, from inside the sweep, from a module with zero live
    /// callers).
    static List<Path> missingProductionOutput() {
        return missingProductionOutput(aetherRoot());
    }

    static List<Path> missingProductionOutput(Path aetherRoot) {
        return declaredModules(aetherRoot).stream()
                                          .filter(module -> Files.isDirectory(module.resolve("src/main/java")))
                                          .filter(module -> !Files.isDirectory(module.resolve("target/classes")))
                                          .toList();
    }
}
