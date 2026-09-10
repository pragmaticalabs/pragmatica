package org.pragmatica.scriptgate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// #952 — `examples/*/start-postgres.sh`, run for real against a stubbed container runtime.
///
/// The script is the one the README's Quick Start invokes; only `docker` is replaced, by a stub on
/// PATH whose answers are read from files. That is what makes the ticket's exact condition - a
/// container that died 295ms after start - reproducible without a race, and it is the condition
/// under which the old script printed `PostgreSQL ready` and exited 0.
///
/// Reading the script's TEXT would prove nothing here: the old loop contains the string
/// "PostgreSQL ready" in the same place the fixed one does. What separates them is whether the
/// script can still print it when nothing is listening, so every test drives the script and reads
/// what it emitted.
///
/// Only `pricing-engine` is executed. [ScriptGateFixtureTest#startPostgresScripts_areIdenticalAcrossEveryExample]
/// pins the four copies byte-for-byte, which is what lets one run speak for all four.
class StartPostgresScriptTest {
    private static final String PG18_ENV = "PATH=/usr/local/bin\nPGDATA=/var/lib/postgresql/18/docker\nLANG=en_US.utf8\n";
    private static final String PG18_VOLUMES = "/var/lib/postgresql\n";
    private static final String PRE18_ENV = "PATH=/usr/local/bin\nPGDATA=/var/lib/postgresql/data\n";
    private static final String PRE18_VOLUMES = "/var/lib/postgresql/data\n";
    private static final String CONTAINER_LOG = "FATAL:  data directory has wrong ownership\n";

    private static final String STUB_RUNTIME = """
                                               #!/bin/bash
                                               STATE="$STUB_STATE"
                                               echo "$*" >> "$STATE/calls"
                                               cmd="$1"; shift
                                               case "$cmd" in
                                                 image)
                                                   shift
                                                   if [ "$1" = "--format" ]; then
                                                     case "$2" in
                                                       *Config.Env*) cat "$STATE/pgdata-env" ;;
                                                       *Config.Volumes*) cat "$STATE/volumes" ;;
                                                     esac
                                                   fi
                                                   exit 0
                                                   ;;
                                                 ps) cat "$STATE/ps-output" 2>/dev/null; exit 0 ;;
                                                 run) printf '%s\\n' "$*" > "$STATE/run-args"; echo stub-container-id; exit 0 ;;
                                                 inspect)
                                                   if [ "$1" = "--format" ]; then
                                                     case "$2" in
                                                       *State.Running*) cat "$STATE/running" ;;
                                                       *State.ExitCode*) cat "$STATE/exit-code" ;;
                                                     esac
                                                   fi
                                                   exit 0
                                                   ;;
                                                 exec)
                                                   [ "$(cat "$STATE/ready")" = "yes" ] && exit 0
                                                   exit 1
                                                   ;;
                                                 logs) cat "$STATE/logs" 2>/dev/null; exit 0 ;;
                                                 *) exit 0 ;;
                                               esac
                                               """;

    private Path state;
    private Path stubDir;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws IOException {
        state = Files.createDirectories(tempDir.resolve("state"));
        stubDir = tempDir.resolve("stub");

        ScriptRunner.writeExecutable(stubDir.resolve("docker"), STUB_RUNTIME);

        image(PG18_ENV, PG18_VOLUMES);
        containerState("true", "0");
        Files.writeString(state.resolve("ready"), "yes");
        Files.writeString(state.resolve("logs"), CONTAINER_LOG);
    }

    /// The ticket's condition, and its acceptance criterion: a container that is already dead must
    /// produce a non-zero exit and a message naming the database.
    @Test
    void deadContainer_failsNamingTheDatabaseAndNeverClaimsReady() throws Exception {
        containerState("false", "1");
        Files.writeString(state.resolve("ready"), "no");

        var execution = runScript(Map.of());

        assertThat(execution.exitCode()).as(execution.output()).isNotZero();
        assertThat(execution.output()).contains("PostgreSQL database 'forge' is NOT available")
                                      .contains("is not running")
                                      .contains("exit code 1")
                                      .contains("readiness probe(s)")
                                      // The container's own words, not a guess about what went wrong.
                                      .contains("data directory has wrong ownership");
        // The precise false success #952 reported: this string, with a dead container behind it.
        assertThat(execution.output()).doesNotContain("PostgreSQL ready");
    }

    /// A loop counter running out is not a diagnosis. The bound has to be real and the message has
    /// to say how many probes were actually made.
    @Test
    void containerThatNeverAnswers_failsOnTheTimeoutAndCountsItsProbes() throws Exception {
        Files.writeString(state.resolve("ready"), "no");

        var execution = runScript(Map.of("PG_READY_TIMEOUT", "2"));

        assertThat(execution.exitCode()).as(execution.output()).isNotZero();
        assertThat(execution.output()).contains("did not become ready within 2s")
                                      .contains("probe(s)")
                                      .doesNotContain("PostgreSQL ready");
    }

    /// The already-running branch used to skip the readiness check entirely - a second blind path to
    /// the same false "ready". A container that is up is not a database that answers.
    @Test
    void alreadyRunningContainer_isStillProbed() throws Exception {
        Files.writeString(state.resolve("ps-output"), "forge-postgres\n");
        Files.writeString(state.resolve("ready"), "no");

        var execution = runScript(Map.of("PG_READY_TIMEOUT", "2"));

        assertThat(execution.output()).as("the already-running branch must have been taken")
                                      .contains("already running");
        assertThat(execution.exitCode()).as(execution.output()).isNotZero();
        assertThat(execution.output()).contains("did not become ready within 2s");
    }

    /// Positive control. Same script, same stub, opposite answer from `pg_isready` - without it the
    /// failures above could be a script that cannot succeed at all.
    @Test
    void readyContainer_succeedsAndSaysHowItKnows() throws Exception {
        var execution = runScript(Map.of());

        assertThat(execution.exitCode()).as(execution.output()).isZero();
        assertThat(execution.output()).contains("PostgreSQL ready")
                                      .contains("verified by pg_isready")
                                      .contains("probe(s)");
    }

    /// The mount is read from the IMAGE. This and [#dataMount_followsAPre18Image] expect mutually
    /// exclusive paths from the same code, so neither hardcoded constant can satisfy both - which is
    /// the difference between probing the derivation and restating it.
    @Test
    void dataMount_followsAPostgres18Image() throws Exception {
        var execution = runScript(Map.of());

        assertThat(execution.exitCode()).as(execution.output()).isZero();
        assertThat(runArguments()).contains("-v forge-pgdata:/var/lib/postgresql ")
                                  .doesNotContain("/var/lib/postgresql/data");
    }

    @Test
    void dataMount_followsAPre18Image() throws Exception {
        image(PRE18_ENV, PRE18_VOLUMES);

        var execution = runScript(Map.of());

        assertThat(execution.exitCode()).as(execution.output()).isZero();
        assertThat(runArguments()).contains("-v forge-pgdata:/var/lib/postgresql/data ");
    }

    /// An image that declares neither is the case where guessing would silently discard every row,
    /// so the script must refuse rather than pick a path.
    @Test
    void imageDeclaringNoDataPath_refusesRatherThanGuessing() throws Exception {
        image("PATH=/usr/local/bin\n", "");

        var execution = runScript(Map.of());

        assertThat(execution.exitCode()).as(execution.output()).isNotZero();
        assertThat(execution.output()).contains("could not read the data directory")
                                      .contains("Refusing to guess");
    }

    private void image(String environmentEntries, String declaredVolumes) throws IOException {
        Files.writeString(state.resolve("pgdata-env"), environmentEntries);
        Files.writeString(state.resolve("volumes"), declaredVolumes);
    }

    private void containerState(String running, String exitCode) throws IOException {
        Files.writeString(state.resolve("running"), running + "\n");
        Files.writeString(state.resolve("exit-code"), exitCode + "\n");
    }

    private String runArguments() throws IOException {
        return Files.readString(state.resolve("run-args"));
    }

    private ScriptRunner.Execution runScript(Map<String, String> extraEnvironment) throws IOException, InterruptedException {
        var exampleDir = ScriptRunner.repoRoot().resolve("examples/pricing-engine");
        var environment = new HashMap<String, String>();

        environment.put("PATH", stubDir + ":" + System.getenv("PATH"));
        environment.put("STUB_STATE", state.toString());
        environment.putAll(extraEnvironment);

        return ScriptRunner.run(exampleDir, environment, List.of("./start-postgres.sh"));
    }
}
