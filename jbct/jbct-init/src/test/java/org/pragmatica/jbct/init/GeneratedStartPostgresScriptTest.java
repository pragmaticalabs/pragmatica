package org.pragmatica.jbct.init;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// #952 — the `start-postgres.sh` this scaffold GENERATES, executed rather than read.
///
/// The four checked-in examples were fixed directly, but every new project gets its copy from
/// [SliceProjectInitializer], and the template carried the same readiness loop with no failure
/// branch. Fixing only the examples would have left the defect shipping to every new user.
///
/// The generated script is run against a stubbed container runtime, because the property under test
/// is behavioural: whether the script can still report success when nothing is listening. Asserting
/// only a non-zero exit would be too weak here - under `set -e` the OLD script also exited non-zero
/// for a dead container, incidentally, when its `psql` schema step failed, with nothing in the
/// output naming the database. What changed is that the failure is now diagnostic, so these tests
/// assert the diagnostic.
class GeneratedStartPostgresScriptTest {
    private static final String STUB_RUNTIME = """
                                               #!/bin/bash
                                               STATE="$STUB_STATE"
                                               cmd="$1"; shift
                                               case "$cmd" in
                                                 image)
                                                   shift
                                                   if [ "$1" = "--format" ]; then
                                                     case "$2" in
                                                       *Config.Env*) printf 'PGDATA=/var/lib/postgresql/data\\n' ;;
                                                       *Config.Volumes*) printf '/var/lib/postgresql/data\\n' ;;
                                                     esac
                                                   fi
                                                   exit 0
                                                   ;;
                                                 ps) exit 0 ;;
                                                 run) printf '%s\\n' "$*" > "$STATE/run-args"; echo stub-id; exit 0 ;;
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
                                                 logs) printf 'FATAL: could not create shared memory segment\\n'; exit 0 ;;
                                                 *) exit 0 ;;
                                               esac
                                               """;

    @TempDir
    Path tempDir;

    private Path projectDir;
    private Path state;
    private Path stubDir;

    @BeforeEach
    void setUp() throws IOException {
        projectDir = tempDir.resolve("demo");
        state = Files.createDirectories(tempDir.resolve("state"));
        stubDir = tempDir.resolve("stub");

        SliceProjectInitializer.sliceProjectInitializer(projectDir, "org.example", "demo")
                               .flatMap(SliceProjectInitializer::initialize)
                               .onFailure(cause -> {throw new IllegalStateException(cause.message());});

        writeExecutable(stubDir.resolve("docker"), STUB_RUNTIME);
        Files.writeString(state.resolve("ready"), "yes");
        containerState("true", "0");
    }

    @Test
    void generatedScript_failsNamingTheDatabaseWhenTheContainerIsDead() throws Exception {
        containerState("false", "1");
        Files.writeString(state.resolve("ready"), "no");

        var execution = runGeneratedScript(Map.of());

        assertThat(execution.exitCode()).as(execution.output()).isNotZero();
        assertThat(execution.output()).contains("PostgreSQL database 'forge' is NOT available")
                                      .contains("exit code 1")
                                      .contains("readiness probe(s)")
                                      .contains("could not create shared memory segment");
        // The old template's success banner. It must be unreachable with a dead container.
        assertThat(execution.output()).doesNotContain("PostgreSQL running on port");
    }

    @Test
    void generatedScript_failsOnTheTimeoutWhenNothingAnswers() throws Exception {
        Files.writeString(state.resolve("ready"), "no");

        var execution = runGeneratedScript(Map.of("PG_READY_TIMEOUT", "2"));

        assertThat(execution.exitCode()).as(execution.output()).isNotZero();
        assertThat(execution.output()).contains("did not become ready within 2s")
                                      .contains("probe(s)")
                                      .doesNotContain("PostgreSQL running on port");
    }

    /// Positive control: the same generated script, the same stub, `pg_isready` answering. Without
    /// it the two failures above would be consistent with a script that cannot succeed at all.
    @Test
    void generatedScript_succeedsAndDerivesTheDataMountFromTheImage() throws Exception {
        var execution = runGeneratedScript(Map.of());

        assertThat(execution.exitCode()).as(execution.output()).isZero();
        assertThat(execution.output()).contains("PostgreSQL ready after")
                                      .contains("probe(s)")
                                      .contains("PostgreSQL running on port");
        assertThat(Files.readString(state.resolve("run-args"))).contains("-v demo-pgdata:/var/lib/postgresql/data");
    }

    private void containerState(String running, String exitCode) throws IOException {
        Files.writeString(state.resolve("running"), running + "\n");
        Files.writeString(state.resolve("exit-code"), exitCode + "\n");
    }

    private Execution runGeneratedScript(Map<String, String> extraEnvironment) throws IOException, InterruptedException {
        var captured = Files.createTempFile("generated-start-postgres-", ".out");
        var builder = new ProcessBuilder(List.of("./start-postgres.sh")).directory(projectDir.toFile())
                                                                       .redirectErrorStream(true)
                                                                       .redirectOutput(captured.toFile());
        var environment = new HashMap<String, String>();

        environment.put("PATH", stubDir + ":" + System.getenv("PATH"));
        environment.put("STUB_STATE", state.toString());
        environment.putAll(extraEnvironment);
        builder.environment().putAll(environment);

        var process = builder.start();
        var finished = process.waitFor(60, TimeUnit.SECONDS);

        if (!finished) {
            process.destroyForcibly().waitFor();
        }

        var output = Files.readString(captured);

        Files.deleteIfExists(captured);

        return new Execution(finished ? process.exitValue() : -1, output);
    }

    private static void writeExecutable(Path target, String content) throws IOException {
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    private record Execution(int exitCode, String output) {}
}
