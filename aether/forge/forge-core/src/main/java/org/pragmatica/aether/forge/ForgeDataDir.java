// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import java.nio.file.Path;
import java.util.List;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.io.FileOps;

import static org.pragmatica.lang.Option.option;


/// #718 — WHERE a Forge run keeps its durable state, and WHO is allowed to reuse it.
///
/// THE DEFECT THIS CLOSES. Before this, every Forge on a host wrote to one machine-wide
/// `~/.aether/forge-data`, derived from nothing but the user's home directory. Two projects were
/// therefore the same project as far as durable state was concerned, and the tutorial
/// (`aether/docs/getting-started.md`) documented neither the path nor the sharing. On 2026-09-11 an
/// agent following that tutorial verbatim in a scratch project ran `./run-forge.sh` and destroyed
/// 32,167 files of an unrelated investigation's state; 25 survived. Nothing was done wrong — the
/// documented procedure was followed exactly, which is why this is a code defect and not a user
/// error.
///
/// TWO MECHANISMS, AND THE GUARANTEE IS DIFFERENT FOR EACH — a single "Forge runs are isolated" claim
/// would be an overclaim, so state them separately:
///
///   1. SCOPE. When a run names a config file (`--config forge.toml` — every `run-forge.sh`, every
///      example, every tutorial step), the data dir defaults to `<that file's directory>/.aether/forge-data`.
///      Two projects then CANNOT collide, because the path is a function of the project. This is a
///      structural guarantee, not a check.
///   2. OWNERSHIP. A run that is about to reuse a POPULATED directory it cannot prove it owns refuses
///      to start. This covers what scope cannot: an operator pointing two projects at one directory
///      through [#DATA_DIR_ENV], and config-less runs, which have no project to be scoped by and so
///      still share `AETHER_HOME`/user-home state. Here the guarantee is a check, with a
///      time-of-check/time-of-use window, exactly as in the sibling [ForgePortPreflight].
///
/// WHY `AETHER_HOME` IS NOT THE SCOPING KEY, THOUGH IT ALREADY EXISTED. It is OVERLOADED: `install.sh`
/// and `upgrade.sh` read it as the INSTALL directory (`INSTALL_DIR="${AETHER_HOME:-$HOME/.aether}"`),
/// so a user who exports it to put `$AETHER_HOME/bin` on their `PATH` — the documented reason to set
/// it — would silently re-share forge state across every project on the host. Scoping on a variable
/// that means something else re-opens this defect through the back door. `AETHER_HOME` is therefore
/// kept, honoured, and DEMOTED: it names the data dir only for config-less runs, which preserves the
/// behaviour #515 documented for the case where it is unambiguous. [#DATA_DIR_ENV] is the new,
/// single-meaning override for anyone who wants to place the directory by hand.
///
/// WHY NOT A FRESH DIRECTORY PER RUN. #515 deliberately made this base dir restart-stable so a
/// `stop()`→`start()` reuses the same per-node dirs and the stream WAL survives — that IS the
/// crash-durability property Forge advertises. A timestamped run dir would trade a silent collision
/// for a silent loss of durability, so reuse stays the default and is made VISIBLE instead of
/// prevented.
///
/// WHAT THIS DOES NOT DO. It never deletes, moves or adopts anything. An existing machine-wide
/// `~/.aether/forge-data` is simply no longer read by project runs; it is left on disk untouched.
/// Corruption of the state itself is a different layer (#1012 / the snapshot-prune path) and is not
/// addressed here.
public sealed interface ForgeDataDir {
    /// Single-meaning override for the data dir. Unlike `AETHER_HOME` this names one thing only.
    String DATA_DIR_ENV = "AETHER_FORGE_DATA";

    /// Install-directory variable, honoured for config-less runs only. See the type documentation.
    String AETHER_HOME_ENV = "AETHER_HOME";

    /// Records which project owns a populated data dir. Not state itself, so its presence alone never
    /// makes a directory count as populated.
    String MARKER_FILE = ".forge-owner";

    String PROJECT_SUBDIR = ".aether";
    String DATA_SUBDIR = "forge-data";

    /// Which rule picked the directory. Carried into the startup line so an operator reading a
    /// surprising path learns WHY it was chosen, not merely what it is.
    enum Source {
        EXPLICIT("the " + DATA_DIR_ENV + " environment variable"),
        PROJECT("this project (the directory holding the --config file)"),
        AETHER_HOME("the " + AETHER_HOME_ENV + " environment variable (no --config given)"),
        USER_HOME("the user-home fallback (no --config and no " + AETHER_HOME_ENV + ")");

        private final String description;

        Source(String description) {
            this.description = description;
        }

        public String description() {
            return description;
        }
    }

    /// A resolved data dir together with the project that claims it.
    ///
    /// @param dataDir directory the cluster's per-node state is written under
    /// @param owner   project this run belongs to — the `--config` file's directory, else the working directory
    /// @param source  rule that picked `dataDir`
    record Location(Path dataDir, Path owner, Source source) {}

    /// What inspecting the resolved dir found. Both cases are reportable; only the failures of
    /// [#inspect] stop a run.
    sealed interface Verdict {
        Path dataDir();

        /// The line an operator sees at startup, before any node exists.
        String description();

        /// Nothing durable is present, so this run starts from empty.
        record Fresh(Path dataDir, Source source) implements Verdict {
            @Override
            public String description() {
                return "Forge data dir: " + dataDir
                     + " (empty; chosen by " + source.description() + ")";
            }
        }

        /// Populated state this project already owns. Announced rather than refused: restart-stable
        /// reuse is the #515 crash-durability property, and the entry count is stated so an operator
        /// who did not expect to inherit anything can see that they are about to.
        record Reused(Path dataDir, Source source, int entries) implements Verdict {
            @Override
            public String description() {
                return "Forge data dir: " + dataDir
                     + " (REUSING " + entries
                     + " existing entr" + (entries == 1 ? "y" : "ies")
                     + " owned by this project; chosen by " + source.description()
                     + "). Nodes resume from this state; it is not cleared.";
            }
        }
    }

    /// Resolve the data dir for a run, from the environment and the `--config` path.
    ///
    /// @param forgeConfig `--config` file, absent when the run named none
    static Location location(Option<Path> forgeConfig) {
        return location(forgeConfig, option(System.getenv(DATA_DIR_ENV)), option(System.getenv(AETHER_HOME_ENV)));
    }

    /// TEST SEAM — the same resolution with the two environment variables supplied directly, so the
    /// precedence can be pinned without mutating the JVM's environment.
    static Location location(Option<Path> forgeConfig, Option<String> explicitDir, Option<String> aetherHome) {
        return explicitLocation(explicitDir, forgeConfig).orElse(() -> projectLocation(forgeConfig))
                                                         .or(() -> homeLocation(forgeConfig, aetherHome));
    }

    /// Inspect the resolved dir before anything is created.
    ///
    /// @return the verdict to announce, or a failure naming why this run must not reuse the directory
    static Result<Verdict> inspect(Location location) {
        return FileOps.isDirectory(location.dataDir())
               ? FileOps.list(location.dataDir())
                        .flatMap(entries -> classify(location, stateEntryCount(entries)))
               : Result.success(new Verdict.Fresh(location.dataDir(), location.source()));
    }

    /// Create the directory and record this project as its owner. Run after [#inspect] has approved.
    static Result<Path> claim(Location location) {
        return FileOps.createDirectories(location.dataDir())
                      .flatMap(dir -> writeMarker(dir, location.owner()));
    }

    private static Option<Location> explicitLocation(Option<String> explicitDir, Option<Path> forgeConfig) {
        return explicitDir.filter(Verify.Is::present)
                          .map(dir -> new Location(absolute(Path.of(dir)),
                                                   ownerOf(forgeConfig),
                                                   Source.EXPLICIT));
    }

    private static Option<Location> projectLocation(Option<Path> forgeConfig) {
        return forgeConfig.map(ForgeDataDir::configDir)
                          .map(projectDir -> new Location(projectDir.resolve(PROJECT_SUBDIR)
                                                                    .resolve(DATA_SUBDIR),
                                                          projectDir,
                                                          Source.PROJECT));
    }

    private static Location homeLocation(Option<Path> forgeConfig, Option<String> aetherHome) {
        return aetherHome.filter(Verify.Is::present)
                         .map(home -> new Location(absolute(Path.of(home, DATA_SUBDIR)),
                                                   ownerOf(forgeConfig),
                                                   Source.AETHER_HOME))
                         .or(() -> userHomeLocation(forgeConfig));
    }

    private static Location userHomeLocation(Option<Path> forgeConfig) {
        return new Location(absolute(Path.of(System.getProperty("user.home"), PROJECT_SUBDIR, DATA_SUBDIR)),
                            ownerOf(forgeConfig),
                            Source.USER_HOME);
    }

    /// The project a run belongs to: the `--config` file's directory, else the working directory. Always
    /// defined, so a config-less run (the container entrypoint, an ad-hoc launch) still has a stable
    /// identity to be checked against across restarts.
    private static Path ownerOf(Option<Path> forgeConfig) {
        return forgeConfig.map(ForgeDataDir::configDir)
                          .or(() -> absolute(Path.of(System.getProperty("user.dir"))));
    }

    private static Path configDir(Path configFile) {
        return option(absolute(configFile).getParent()).or(() -> absolute(configFile));
    }

    private static Path absolute(Path path) {
        return path.toAbsolutePath()
                   .normalize();
    }

    private static Result<Verdict> classify(Location location, int stateEntries) {
        return stateEntries == 0
               ? Result.success(new Verdict.Fresh(location.dataDir(), location.source()))
               : judgeOwner(location, stateEntries, recordedOwner(location.dataDir()));
    }

    private static Result<Verdict> judgeOwner(Location location, int entries, Option<Path> recorded) {
        return recorded.map(owner -> ownerVerdict(location, entries, owner))
                       .or(() -> ForgeDataError.unownedState(location, entries).result());
    }

    private static Result<Verdict> ownerVerdict(Location location, int entries, Path recordedOwner) {
        return recordedOwner.equals(location.owner())
               ? Result.success(new Verdict.Reused(location.dataDir(), location.source(), entries))
               : ForgeDataError.foreignState(location, recordedOwner, entries).result();
    }

    /// The marker is bookkeeping, not durable node state, so a directory holding only a marker is still
    /// fresh. Without this, claiming a directory would make the very next run see it as populated.
    private static int stateEntryCount(List<Path> entries) {
        return (int) entries.stream()
                            .filter(entry -> !MARKER_FILE.equals(entry.getFileName()
                                                                      .toString()))
                            .count();
    }

    private static Option<Path> recordedOwner(Path dataDir) {
        return FileOps.readString(dataDir.resolve(MARKER_FILE))
                      .option()
                      .map(String::trim)
                      .filter(Verify.Is::present)
                      .map(Path::of);
    }

    private static Result<Path> writeMarker(Path dataDir, Path owner) {
        return FileOps.writeString(dataDir.resolve(MARKER_FILE), owner + System.lineSeparator())
                      .map(_ -> dataDir);
    }

    /// Refusals. Both messages state that NOTHING has been written or deleted yet, because the failure
    /// this class exists to prevent was discovered only by noticing files were already gone.
    sealed interface ForgeDataError extends Cause {
        /// Populated state owned by a DIFFERENT project. Naming both projects is the point: the
        /// operator is the only party who knows which one is the mistake.
        record ForeignState(Path dataDir, Path recordedOwner, Path owner, int entries) implements ForgeDataError {
            @Override
            public String message() {
                return "Refusing to start: the Forge data dir " + dataDir
                     + " already holds " + entries
                     + " entr" + (entries == 1 ? "y" : "ies")
                     + " of durable state belonging to a DIFFERENT project (" + recordedOwner
                     + "), but this run belongs to " + owner
                     + ". Starting would make two projects share one cluster's durable state. "
                     + "Nothing has been written or deleted — no node was started. "
                     + "Either run from " + recordedOwner
                     + ", or point this run elsewhere with " + DATA_DIR_ENV
                     + "=<dir>, or move " + dataDir
                     + " aside yourself if you no longer need it.";
            }
        }

        /// Populated state with no owner recorded — state predating this check, or written by hand.
        /// This is the exact shape that destroyed the #718 reproducer, so it refuses rather than
        /// adopting: adopting silently is what the whole class exists to stop.
        record UnownedState(Path dataDir, Path owner, int entries) implements ForgeDataError {
            @Override
            public String message() {
                return "Refusing to start: the Forge data dir " + dataDir
                     + " already holds " + entries
                     + " entr" + (entries == 1 ? "y" : "ies")
                     + " of durable state, and no " + MARKER_FILE
                     + " records which project owns it — so this run cannot tell whether the state is "
                     + "its own or something else's. This run belongs to " + owner
                     + ". Nothing has been written or deleted — no node was started. "
                     + "If the state is yours, adopt it by writing the owning project's path into "
                     + dataDir.resolve(MARKER_FILE)
                     + "; otherwise point this run elsewhere with " + DATA_DIR_ENV
                     + "=<dir>, or move " + dataDir
                     + " aside.";
            }
        }

        static ForgeDataError foreignState(Location location, Path recordedOwner, int entries) {
            return new ForeignState(location.dataDir(), recordedOwner, location.owner(), entries);
        }

        static ForgeDataError unownedState(Location location, int entries) {
            return new UnownedState(location.dataDir(), location.owner(), entries);
        }
    }

    record unused() implements ForgeDataDir {}
}
