package org.pragmatica.scriptgate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.pragmatica.scriptgate.ScriptGate.executed;
import static org.pragmatica.scriptgate.ScriptGate.given;
import static org.assertj.core.api.Assertions.assertThat;


/// #952 - `examples/*/start-postgres.sh`, run for real against a stubbed container runtime.
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
    void setUp(@TempDir Path tempDir) {
        state = tempDir.resolve("state");
        stubDir = tempDir.resolve("stub");
        given(ScriptRunner.writeExecutable(stubDir.resolve("docker"), STUB_RUNTIME).mapToUnit());
        given(image(PG18_ENV, PG18_VOLUMES));
        given(containerState("true", "0"));
        given(write("ready", "yes"));
        given(write("logs", CONTAINER_LOG));
    }

    /// The ticket's condition, and its acceptance criterion: a container that is already dead must
    /// produce a non-zero exit and a message naming the database.
    @Test
    void deadContainer_failsNamingTheDatabaseAndNeverClaimsReady() {
        given(containerState("false", "1"));
        given(write("ready", "no"));
        var execution = executed(runScript(Map.of()));

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
    void containerThatNeverAnswers_failsOnTheTimeoutAndCountsItsProbes() {
        given(write("ready", "no"));
        var execution = executed(runScript(Map.of("PG_READY_TIMEOUT", "2")));

        assertThat(execution.exitCode()).as(execution.output()).isNotZero();
        assertThat(execution.output()).contains("did not become ready within 2s")
                  .contains("probe(s)")
                  .doesNotContain("PostgreSQL ready");
    }

    /// The already-running branch used to skip the readiness check entirely - a second blind path to
    /// the same false "ready". A container that is up is not a database that answers.
    @Test
    void alreadyRunningContainer_isStillProbed() {
        given(write("ps-output", "forge-postgres\n"));
        given(write("ready", "no"));
        var execution = executed(runScript(Map.of("PG_READY_TIMEOUT", "2")));

        assertThat(execution.output()).as("the already-running branch must have been taken").contains("already running");
        assertThat(execution.exitCode()).as(execution.output()).isNotZero();
        assertThat(execution.output()).contains("did not become ready within 2s");
    }

    /// Positive control. Same script, same stub, opposite answer from `pg_isready` - without it the
    /// failures above could be a script that cannot succeed at all.
    @Test
    void readyContainer_succeedsAndSaysHowItKnows() {
        var execution = executed(runScript(Map.of()));

        assertThat(execution.exitCode()).as(execution.output()).isZero();
        assertThat(execution.output()).contains("PostgreSQL ready")
                  .contains("verified by pg_isready")
                  .contains("probe(s)");
    }

    /// The mount is read from the IMAGE. This and [#dataMount_followsAPre18Image] expect mutually
    /// exclusive paths from the same code, so neither hardcoded constant can satisfy both - which is
    /// the difference between probing the derivation and restating it.
    @Test
    void dataMount_followsAPostgres18Image() {
        var execution = executed(runScript(Map.of()));

        assertThat(execution.exitCode()).as(execution.output()).isZero();
        assertThat(runArguments()).contains("-v forge-pgdata:/var/lib/postgresql ")
                  .doesNotContain("/var/lib/postgresql/data");
    }

    @Test
    void dataMount_followsAPre18Image() {
        given(image(PRE18_ENV, PRE18_VOLUMES));
        var execution = executed(runScript(Map.of()));

        assertThat(execution.exitCode()).as(execution.output()).isZero();
        assertThat(runArguments()).contains("-v forge-pgdata:/var/lib/postgresql/data ");
    }

    /// An image that declares neither is the case where guessing would silently discard every row,
    /// so the script must refuse rather than pick a path.
    @Test
    void imageDeclaringNoDataPath_refusesRatherThanGuessing() {
        given(image("PATH=/usr/local/bin\n", ""));
        var execution = executed(runScript(Map.of()));

        assertThat(execution.exitCode()).as(execution.output()).isNotZero();
        assertThat(execution.output()).contains("could not read the data directory").contains("Refusing to guess");
    }

    private Result<Unit> image(String environmentEntries, String declaredVolumes) {
        return write("pgdata-env", environmentEntries).flatMap(ignored -> write("volumes", declaredVolumes));
    }

    private Result<Unit> containerState(String running, String exitCode) {
        return write("running", running + "\n").flatMap(ignored -> write("exit-code", exitCode + "\n"));
    }

    private Result<Unit> write(String name, String content) {
        return Result.lift(() -> Files.createDirectories(state))
                     .flatMap(directory -> Result.lift(() -> Files.writeString(directory.resolve(name),
                                                                               content)))
                     .mapToUnit();
    }

    private String runArguments() {
        return Result.lift(() -> Files.readString(state.resolve("run-args"))).or("<no run recorded>");
    }

    private Result<ScriptRunner.Execution> runScript(Map<String, String> extraEnvironment) {
        var exampleDir = ScriptRunner.repoRoot().resolve("examples/pricing-engine");
        var environment = new HashMap<String, String>();

        environment.put("PATH", stubDir + ":" + System.getenv("PATH"));
        environment.put("STUB_STATE", state.toString());
        environment.putAll(extraEnvironment);

        return ScriptRunner.run(exampleDir, environment, List.of("./start-postgres.sh"));
    }
}
