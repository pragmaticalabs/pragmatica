// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.api.routes;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.management.route.ManagementRoute;

import static org.assertj.core.api.Assertions.assertThat;


/// #525 structural guard: every declared [ManagementRoute] must be SERVED, or explicitly exempted
/// as prefix-handled. This is the regression sensor for the whole dead-route class, not for the six
/// instances that prompted it.
///
/// **Why registration and not grep.** The #525 sweep asked "is the enum constant referenced anywhere
/// under `aether/node/src/main`?" and concluded 181 of 188 routes were fine. That test has a false
/// negative it cannot see: `CLUSTER_MIGRATE` and `CLUSTER_MIGRATE_PLAN` ARE referenced there — in
/// `ManagementRoutePermissions`, which grants authorization for a handler that was never written. A
/// permission entry is not service. Both routes were CLI-reachable through a destructive
/// `aether cluster migrate` that POSTed into the void. This test keys on the single registration
/// funnel instead, so a mention in a permissions table, a comment, or a doc string cannot satisfy it.
///
/// **Why source text and not a live router.** Assembling the real router needs a booted node plus a
/// dozen collaborators (alert manager, trace store, scheduled-task registry, ...); a test that
/// duplicated `ManagementServer`'s wiring would drift out of sync and start passing vacuously. Every
/// handler in the codebase is registered through `ManagementRoutes.route(...)` — verified: that call
/// appears in `aether/node` and nowhere else — so scanning for that call is exact, and it stays exact
/// because `ManagementRouter` looks handlers up by `Route::name`, which `ManagementRoutes.route` sets
/// from the enum constant name.
///
/// **Known limitation, and it points the safe way.** The scan requires the route to appear LITERALLY
/// as `route(ManagementRoute.X)`. Registering through a helper that takes the route as a parameter
/// (`register(someRoute)`) is invisible to it, so such a route reports as unserved even though it is
/// served. That is a false POSITIVE — loud and immediately diagnosable — and the fix is to spell the
/// registration out literally, as `NotImplementedRoutes` does. The dangerous direction is the false
/// negative, and the scan has none: a mention in a permissions table, a comment, an import or a doc
/// string cannot match the call form.
class ManagementRouteCoverageTest {
    /// Routes deliberately NOT registered by name because a prefix handler claims them wholesale.
    ///
    /// `MavenProtocolRoutes` claims everything under `/repository/` and feeds it to the maven
    /// coordinate parser, ahead of the name-keyed router. These entries describe maven protocol
    /// operations whose coordinates are path segments, so there is no per-name handler to register.
    ///
    /// Adding an entry here is the ONLY way to silence this test, and
    /// `exemptRoutes_areAllUnderTheClaimingPrefix` checks that anything added is genuinely under the
    /// claiming prefix — so a route cannot be parked here to hide the fact that nobody serves it.
    private static final Set<ManagementRoute> PREFIX_HANDLED = EnumSet.of(ManagementRoute.ARTIFACT_GET,
                                                                          ManagementRoute.ARTIFACT_PUT,
                                                                          ManagementRoute.ARTIFACT_POST,
                                                                          ManagementRoute.ARTIFACT_DELETE,
                                                                          ManagementRoute.MAVEN_METADATA,
                                                                          ManagementRoute.REPOSITORY_ARTIFACTS_LIST);

    private static final String CLAIMING_PREFIX = "/repository";

    @Test
    void managementRoutes_allDeclaredEntries_haveAServerSideHandler() {
        var sources = readMainSources();
        var unserved = Arrays.stream(ManagementRoute.values())
                             .filter(route -> !PREFIX_HANDLED.contains(route))
                             .filter(route -> !isRegistered(route, sources))
                             .toList();

        assertThat(unserved)
                .withFailMessage("These ManagementRoute entries are DECLARED but no handler is registered for "
                                + "them, so the CLI and dashboard advertise a capability the server answers with "
                                + "a bare 404 (#525). Fix by either (a) registering a real handler via "
                                + "ManagementRoutes.route(ManagementRoute.X), (b) registering an honest 501 in "
                                + "NotImplementedRoutes naming what is missing, or (c) deleting the enum entry "
                                + "together with its CLI subcommand, dashboard call and docs. If the route IS "
                                + "registered but through a helper taking it as a parameter, spell the "
                                + "registration out literally as route(ManagementRoute.X) — this scan only sees "
                                + "the literal form. Do NOT add it to PREFIX_HANDLED unless a prefix handler "
                                + "genuinely claims its path. Unserved: %s",
                                 unserved)
                .isEmpty();
    }

    /// Anti-cheat for the exemption list: an exempt route must actually live under the prefix that
    /// claims it. Without this, silencing the guard would be as cheap as appending to `PREFIX_HANDLED`.
    @Test
    void exemptRoutes_areAllUnderTheClaimingPrefix() {
        var misfiled = PREFIX_HANDLED.stream()
                                     .filter(route -> !route.prefix().startsWith(CLAIMING_PREFIX))
                                     .toList();

        assertThat(misfiled)
                .withFailMessage("PREFIX_HANDLED exempts routes from the handler-registration guard on the "
                                + "grounds that MavenProtocolRoutes claims everything under %s. These entries "
                                + "are not under that prefix, so nothing claims them and the exemption is "
                                + "hiding a dead route: %s",
                                 CLAIMING_PREFIX,
                                 misfiled)
                .isEmpty();
    }

    /// Proves the scanner detects real registrations rather than matching everything. Without this a
    /// broken pattern (or an empty source read) would make the guard above pass vacuously.
    @Test
    void registrationScanner_findsKnownRegisteredRoute_andMissesUnregisteredName() {
        var sources = readMainSources();

        assertThat(sources).isNotEmpty();
        assertThat(isRegistered(ManagementRoute.NODE_ROUTES, sources)).isTrue();
        assertThat(sources.contains("route(ManagementRoute.THIS_ROUTE_DOES_NOT_EXIST)")).isFalse();
    }

    private static boolean isRegistered(ManagementRoute route, String sources) {
        return Pattern.compile("route\\(\\s*(?:ManagementRoute\\.)?" + route.name() + "\\s*\\)")
                      .matcher(sources)
                      .find();
    }

    /// Reads every main-source file of this module as one blob.
    ///
    /// The source root is derived from this class's own location on disk
    /// (`<module>/target/test-classes` → `<module>/src/main/java`) rather than from the working
    /// directory, so the guard behaves identically under Surefire, an IDE runner, and an aggregator
    /// build. An unreadable root fails loudly — a guard that quietly scans nothing always passes.
    private static String readMainSources() {
        var root = sourceRoot();

        assertThat(root).exists();

        try (var paths = Files.walk(root)) {
            return paths.filter(path -> path.toString().endsWith(".java"))
                        .map(ManagementRouteCoverageTest::readFile)
                        .collect(Collectors.joining("\n"));
        } catch (IOException e) {
            throw new AssertionError("Cannot walk main sources at " + root, e);
        }
    }

    private static String readFile(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new AssertionError("Cannot read " + path, e);
        }
    }

    private static Path sourceRoot() {
        try {
            var testClasses = Path.of(ManagementRouteCoverageTest.class.getProtectionDomain()
                                                                       .getCodeSource()
                                                                       .getLocation()
                                                                       .toURI());

            return testClasses.getParent()
                              .getParent()
                              .resolve("src/main/java");
        } catch (URISyntaxException e) {
            throw new AssertionError("Cannot locate module source root", e);
        }
    }
}
