// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// #718 — two Forge runs from two different projects must not share durable state, and reuse of
/// populated state must never be silent.
///
/// The defect these pin destroyed 32,167 files of an unrelated investigation's durable state on
/// 2026-09-11. The tutorial was followed exactly; the data dir was derived from the user's home
/// directory alone, so "a different project" and "the same project" were indistinguishable.
///
/// The two properties are pinned SEPARATELY because they are earned by different mechanisms and hold
/// over different cases — scoping is structural and covers runs that name a config file, ownership is
/// a check and covers the rest. A single "runs are isolated" assertion would overclaim.
class ForgeDataDirTest {
    private static Path configIn(Path projectDir) throws IOException {
        var config = projectDir.resolve("forge.toml");

        Files.writeString(config, "[cluster]\n");

        return config;
    }

    private static void writeState(Path dataDir, String name) throws IOException {
        Files.createDirectories(dataDir);
        Files.writeString(dataDir.resolve(name), "durable\n");
    }

    /// Property 1 — scoping. Structural: the path is a function of the project, so collision is not
    /// merely detected, it is unrepresentable.
    @Nested
    class Scoping {
        /// THE test this change exists for. Against the previous implementation both projects resolved
        /// to one machine-wide `~/.aether/forge-data` and this assertion fails.
        @Test
        void location_differsBetweenTwoProjects(@TempDir Path root) throws IOException {
            var first = ForgeDataDir.location(some(configIn(Files.createDirectories(root.resolve("project-a")))),
                                              none(),
                                              none());
            var second = ForgeDataDir.location(some(configIn(Files.createDirectories(root.resolve("project-b")))),
                                               none(),
                                               none());

            assertThat(first.dataDir()).isNotEqualTo(second.dataDir());
            assertThat(first.owner()).isNotEqualTo(second.owner());
        }

        /// #515's crash-durability property: a restart of ONE project must land on the SAME dir, or the
        /// stream WAL it advertises cannot survive. Isolation must not be bought with a fresh dir per run.
        @Test
        void location_isStableAcrossRunsOfOneProject(@TempDir Path projectDir) throws IOException {
            var config = configIn(projectDir);

            assertThat(ForgeDataDir.location(some(config), none(), none())
                                   .dataDir()).isEqualTo(ForgeDataDir.location(some(config), none(), none())
                                                                     .dataDir());
        }

        @Test
        void location_isUnderTheProjectDirectory(@TempDir Path projectDir) throws IOException {
            var location = ForgeDataDir.location(some(configIn(projectDir)), none(), none());

            assertThat(location.dataDir()).isEqualTo(projectDir.toAbsolutePath()
                                                               .normalize()
                                                               .resolve(".aether")
                                                               .resolve("forge-data"));
            assertThat(location.source()).isEqualTo(ForgeDataDir.Source.PROJECT);
        }

        @Test
        void location_ownsTheProjectDirectory(@TempDir Path projectDir) throws IOException {
            assertThat(ForgeDataDir.location(some(configIn(projectDir)), none(), none())
                                   .owner()).isEqualTo(projectDir.toAbsolutePath()
                                                                 .normalize());
        }
    }

    /// Precedence. The load-bearing case is `AETHER_HOME` LOSING to the project, because that variable
    /// is overloaded — `install.sh` reads it as the install directory, so a user who exports it for
    /// `PATH` reasons would otherwise silently re-share state across every project on the host.
    @Nested
    class Precedence {
        @Test
        void location_prefersProjectOverAetherHome(@TempDir Path root) throws IOException {
            var projectDir = Files.createDirectories(root.resolve("project"));
            var aetherHome = root.resolve("aether-home");

            var location = ForgeDataDir.location(some(configIn(projectDir)),
                                                 none(),
                                                 some(aetherHome.toString()));

            assertThat(location.dataDir()).isEqualTo(projectDir.toAbsolutePath()
                                                               .normalize()
                                                               .resolve(".aether")
                                                               .resolve("forge-data"));
            assertThat(location.dataDir()).doesNotExist();
            assertThat(aetherHome).doesNotExist();
            assertThat(location.source()).isEqualTo(ForgeDataDir.Source.PROJECT);
        }

        @Test
        void location_prefersExplicitOverrideOverEverything(@TempDir Path root) throws IOException {
            var explicit = root.resolve("explicit-data");

            var location = ForgeDataDir.location(some(configIn(Files.createDirectories(root.resolve("project")))),
                                                 some(explicit.toString()),
                                                 some(root.resolve("aether-home")
                                                          .toString()));

            assertThat(location.dataDir()).isEqualTo(explicit.toAbsolutePath()
                                                             .normalize());
            assertThat(location.source()).isEqualTo(ForgeDataDir.Source.EXPLICIT);
        }

        /// A config-less run has no project to be scoped by, so #515's documented `$AETHER_HOME/forge-data`
        /// still applies — the container entrypoint takes this path.
        @Test
        void location_usesAetherHome_whenNoConfigGiven(@TempDir Path root) {
            var aetherHome = root.resolve("aether-home");

            var location = ForgeDataDir.location(none(), none(), some(aetherHome.toString()));

            assertThat(location.dataDir()).isEqualTo(aetherHome.resolve("forge-data")
                                                               .toAbsolutePath()
                                                               .normalize());
            assertThat(location.source()).isEqualTo(ForgeDataDir.Source.AETHER_HOME);
        }

        @Test
        void location_usesUserHome_whenNoConfigAndNoAetherHome() {
            var location = ForgeDataDir.location(none(), none(), none());

            assertThat(location.dataDir()).isEqualTo(Path.of(System.getProperty("user.home"),
                                                             ".aether",
                                                             "forge-data")
                                                         .toAbsolutePath()
                                                         .normalize());
            assertThat(location.source()).isEqualTo(ForgeDataDir.Source.USER_HOME);
        }

        /// An exported-but-empty variable is the shell's way of saying nothing, and must not resolve to
        /// a relative `forge-data` in the working directory.
        @Test
        void location_ignoresBlankEnvironmentValues() {
            var location = ForgeDataDir.location(none(), some("  "), some(""));

            assertThat(location.source()).isEqualTo(ForgeDataDir.Source.USER_HOME);
        }
    }

    /// Property 2 — ownership. Covers what scoping cannot: an explicit override aimed at one dir by two
    /// projects, and config-less runs.
    @Nested
    class OwnershipGuard {
        private ForgeDataDir.Location locationOf(Path dataDir, Path owner) {
            return new ForgeDataDir.Location(dataDir, owner, ForgeDataDir.Source.EXPLICIT);
        }

        @Test
        void inspect_isFresh_whenDirectoryAbsent(@TempDir Path root) {
            ForgeDataDir.inspect(locationOf(root.resolve("absent"), root))
                        .onFailure(cause -> fail("expected Fresh, refused: " + cause.message()))
                        .onSuccess(verdict -> assertThat(verdict).isInstanceOf(ForgeDataDir.Verdict.Fresh.class));
        }

        @Test
        void inspect_isFresh_whenDirectoryEmpty(@TempDir Path dataDir) {
            ForgeDataDir.inspect(locationOf(dataDir, dataDir))
                        .onFailure(cause -> fail("expected Fresh, refused: " + cause.message()))
                        .onSuccess(verdict -> assertThat(verdict).isInstanceOf(ForgeDataDir.Verdict.Fresh.class));
        }

        /// The marker is bookkeeping, not durable state. Without this, claiming a directory would make
        /// the very next run of the SAME project see it as populated.
        @Test
        void inspect_isFresh_whenOnlyTheMarkerIsPresent(@TempDir Path dataDir) throws IOException {
            Files.writeString(dataDir.resolve(ForgeDataDir.MARKER_FILE), dataDir + "\n");

            ForgeDataDir.inspect(locationOf(dataDir, dataDir))
                        .onFailure(cause -> fail("expected Fresh, refused: " + cause.message()))
                        .onSuccess(verdict -> assertThat(verdict).isInstanceOf(ForgeDataDir.Verdict.Fresh.class));
        }

        @Test
        void inspect_announcesReuse_whenStateIsOwnedByThisProject(@TempDir Path root) throws IOException {
            var dataDir = root.resolve("data");
            var owner = root.resolve("project");

            writeState(dataDir, "node-1");
            Files.writeString(dataDir.resolve(ForgeDataDir.MARKER_FILE), owner + "\n");

            ForgeDataDir.inspect(locationOf(dataDir, owner))
                        .onFailure(cause -> fail("expected Reused, refused: " + cause.message()))
                        .onSuccess(verdict -> assertThat(verdict.description()).contains("REUSING")
                                                                               .contains("1 existing entry")
                                                                               .contains("not cleared"));
        }

        /// The #718 shape exactly: populated state, no marker, so ownership cannot be established.
        /// Adopting it silently is what destroyed the reproducer.
        @Test
        void inspect_refuses_whenStateHasNoRecordedOwner(@TempDir Path root) throws IOException {
            var dataDir = root.resolve("data");

            writeState(dataDir, "node-1");

            ForgeDataDir.inspect(locationOf(dataDir, root.resolve("project")))
                        .onSuccess(verdict -> fail("expected a refusal, got: " + verdict.description()))
                        .onFailure(cause -> assertThat(cause).isInstanceOf(ForgeDataDir.ForgeDataError.UnownedState.class));
        }

        @Test
        void inspect_refuses_whenStateBelongsToAnotherProject(@TempDir Path root) throws IOException {
            var dataDir = root.resolve("data");

            writeState(dataDir, "node-1");
            Files.writeString(dataDir.resolve(ForgeDataDir.MARKER_FILE), root.resolve("other-project") + "\n");

            ForgeDataDir.inspect(locationOf(dataDir, root.resolve("this-project")))
                        .onSuccess(verdict -> fail("expected a refusal, got: " + verdict.description()))
                        .onFailure(cause -> assertThat(cause).isInstanceOf(ForgeDataDir.ForgeDataError.ForeignState.class));
        }

        /// Honesty pin. The refusal is read by someone who is about to wonder whether their files are
        /// already gone; it must answer that, and it must name the other project, because only the
        /// operator knows which of the two is the mistake.
        @Test
        void inspect_refusalNamesBothProjectsAndStatesNothingWasDeleted(@TempDir Path root) throws IOException {
            var dataDir = root.resolve("data");
            var other = root.resolve("other-project");
            var mine = root.resolve("this-project");

            writeState(dataDir, "node-1");
            Files.writeString(dataDir.resolve(ForgeDataDir.MARKER_FILE), other + "\n");

            ForgeDataDir.inspect(locationOf(dataDir, mine))
                        .onSuccess(verdict -> fail("expected a refusal, got: " + verdict.description()))
                        .onFailure(cause -> assertThat(cause.message()).contains(other.toString())
                                                                       .contains(mine.toString())
                                                                       .contains("Nothing has been written or deleted")
                                                                       .contains(ForgeDataDir.DATA_DIR_ENV));
        }

        @Test
        void inspect_unownedRefusalStatesNothingWasDeleted(@TempDir Path root) throws IOException {
            var dataDir = root.resolve("data");

            writeState(dataDir, "node-1");

            ForgeDataDir.inspect(locationOf(dataDir, root.resolve("project")))
                        .onSuccess(verdict -> fail("expected a refusal, got: " + verdict.description()))
                        .onFailure(cause -> assertThat(cause.message()).contains("Nothing has been written or deleted")
                                                                       .contains(ForgeDataDir.MARKER_FILE));
        }
    }

    /// Claiming is what makes the NEXT run's ownership check answerable.
    @Nested
    class Claiming {
        @Test
        void claim_createsTheDirectoryAndRecordsTheOwner(@TempDir Path root) {
            var dataDir = root.resolve("data");
            var owner = root.resolve("project");

            ForgeDataDir.claim(new ForgeDataDir.Location(dataDir, owner, ForgeDataDir.Source.PROJECT))
                        .onFailure(cause -> fail("claim failed: " + cause.message()))
                        .onSuccess(dir -> assertThat(dir.resolve(ForgeDataDir.MARKER_FILE)).exists()
                                                                                           .content()
                                                                                           .contains(owner.toString()));
        }

        /// Round trip: a project that claims a dir, writes state, and comes back is allowed in — and is
        /// told so.
        @Test
        void claim_makesTheNextInspectAnnounceReuse(@TempDir Path root) throws IOException {
            var dataDir = root.resolve("data");
            var owner = root.resolve("project");
            var location = new ForgeDataDir.Location(dataDir, owner, ForgeDataDir.Source.PROJECT);

            ForgeDataDir.claim(location)
                        .onFailure(cause -> fail("claim failed: " + cause.message()));
            writeState(dataDir, "node-1");

            ForgeDataDir.inspect(location)
                        .onFailure(cause -> fail("expected Reused, refused: " + cause.message()))
                        .onSuccess(verdict -> assertThat(verdict).isInstanceOf(ForgeDataDir.Verdict.Reused.class));
        }

        /// A second project arriving at a claimed dir is refused — the guard survives a real claim, not
        /// only a hand-written marker.
        @Test
        void claim_refusesASecondProjectAtTheSameDirectory(@TempDir Path root) throws IOException {
            var dataDir = root.resolve("data");

            ForgeDataDir.claim(new ForgeDataDir.Location(dataDir,
                                                         root.resolve("first"),
                                                         ForgeDataDir.Source.EXPLICIT))
                        .onFailure(cause -> fail("claim failed: " + cause.message()));
            writeState(dataDir, "node-1");

            ForgeDataDir.inspect(new ForgeDataDir.Location(dataDir,
                                                           root.resolve("second"),
                                                           ForgeDataDir.Source.EXPLICIT))
                        .onSuccess(verdict -> fail("expected a refusal, got: " + verdict.description()))
                        .onFailure(cause -> assertThat(cause).isInstanceOf(ForgeDataDir.ForgeDataError.ForeignState.class));
        }
    }

    /// The resolver reads the real environment in production; this pins that the no-argument form is
    /// wired to the same precedence rather than a second copy of it.
    @Test
    void location_publicFormAgreesWithTheSeam() {
        var viaEnv = ForgeDataDir.location(Option.<Path>none());
        var viaSeam = ForgeDataDir.location(none(),
                                            Option.option(System.getenv(ForgeDataDir.DATA_DIR_ENV)),
                                            Option.option(System.getenv(ForgeDataDir.AETHER_HOME_ENV)));

        assertThat(viaEnv.dataDir()).isEqualTo(viaSeam.dataDir());
        assertThat(viaEnv.source()).isEqualTo(viaSeam.source());
    }
}
