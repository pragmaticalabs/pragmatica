// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deadsurface;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// #519's corpus discovery: every module declared in `aether/pom.xml`'s default `<modules>` list, read
/// by its compiled production output (`<module>/target/classes`) — NOT by reading this JVM's own
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
/// Deliberately scoped to that one `<modules>` block (the first in the file, before any `<profile>`) —
/// not a filesystem walk under `aether/`, and not the whole monorepo. Two things outside that list are
/// NOT modules of the default reactor and must not be treated as ones: profile-gated modules further
/// down the same file (`e2e-tests`, `cloud-tests`, activated only via `-Pwith-e2e`), and stray `pom.xml`
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

    private ReactorRoots() {}

    /// This class's own `target/test-classes` directory is three path segments below the `aether/`
    /// reactor root (`<root>/aether/node/target/test-classes`) — walking up from it is deterministic
    /// regardless of the working directory `mvn` was invoked from, unlike `user.dir`.
    static Path aetherRoot() {
        var testClasses = codeSourceLocation();
        var root = testClasses.getParent().getParent().getParent();

        if (root == null || !"aether".equals(String.valueOf(root.getFileName()))) {
            throw new IllegalStateException("ReactorRoots.aetherRoot() computed " + root +
                                             " from " + testClasses + ", whose name is not \"aether\" — " +
                                             "the three-levels-up assumption (target/test-classes -> node -> " +
                                             "aether) no longer holds; fix the walk-up count.");
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

    /// The module paths (relative to `aether/`) declared in `aether/pom.xml`'s default `<modules>`
    /// block — the first `<modules>...</modules>` occurrence in the file, which is the top-level
    /// project's own list; the two profile-gated `<modules>` blocks further down (`e2e-tests`,
    /// `cloud-tests`) sit inside `<profile>` elements and are deliberately not read here.
    static List<Path> declaredModules() {
        var pomXml = aetherRoot().resolve("pom.xml");
        String content;

        try {
            content = Files.readString(pomXml);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        var modulesStart = content.indexOf("<modules>");
        var modulesEnd = content.indexOf("</modules>", modulesStart);

        if (modulesStart < 0 || modulesEnd < 0) {
            throw new IllegalStateException("No <modules> block found in " + pomXml + " — has the reactor " +
                                             "declaration moved or changed shape? Update this parser.");
        }

        var block = content.substring(modulesStart, modulesEnd);
        var modules = new java.util.ArrayList<Path>();
        Matcher matcher = MODULE_TAG.matcher(block);

        while (matcher.find()) {
            modules.add(aetherRoot().resolve(matcher.group(1)));
        }

        return modules;
    }

    /// Every declared module's `target/classes` directory that actually exists — one per module
    /// compiled at least once since its last `clean`.
    static List<Path> productionRoots() {
        return declaredModules().stream()
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
        return declaredModules().stream()
                                 .filter(module -> Files.isDirectory(module.resolve("src/main/java")))
                                 .filter(module -> !Files.isDirectory(module.resolve("target/classes")))
                                 .toList();
    }
}
