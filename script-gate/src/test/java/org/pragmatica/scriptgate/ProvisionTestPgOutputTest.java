package org.pragmatica.scriptgate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.pragmatica.scriptgate.ScriptGate.executed;
import static org.pragmatica.scriptgate.ScriptGate.given;
import static org.assertj.core.api.Assertions.assertThat;


/// `tools/provision-test-pg.sh` must not print `PG_PASSWORD`, the `PG_URL` that embeds it, or any other
/// env-file line outside the allow-list. The `--print-only` and idempotent-reuse paths run for real against a
/// stubbed Hetzner API and a fake env file. The fresh-provision path is pinned by sourcing the script's real
/// function text (everything before its main flow) and calling `write_env_file`, the only fresh-path emitter,
/// so no VM is needed. The env file itself must stay untouched and sourceable for the consumers.
class ProvisionTestPgOutputTest {
    private static final String SECRET = "fake-secret-0123456789abcdef";
    private static final String MAIN_FLOW = "# Main flow";

    private static final String ENV_BODY = """
                                           # PG VM 42  IP=192.0.2.10  provisioned=2026-01-01T00:00:00Z
                                           export PG_URL=postgres://aether:%s@192.0.2.10:5432/aether_forge
                                           export PG_USER=aether
                                           export PG_PASSWORD=%s
                                           export PG_HOST=192.0.2.10
                                           export PG_DB=aether_forge
                                           """.formatted(SECRET, SECRET);

    /// Forms a deny-list anchored on the canonical line would miss.
    private static final String FOREIGN_BODY = """
                                               PG_PASSWORD=%s
                                                 export PG_PASSWORD=%s
                                               export PG_PASSWORD="%s"
                                               export PG_URL='postgres://aether:%s@192.0.2.10/db'
                                               PG_URL=postgres://aether:%s@192.0.2.10/db
                                               export PG_TOKEN=%s
                                               # note: password is %s
                                               export PG_DB=aether_forge; echo %s
                                               export PG_HOST="%s"
                                               PG_USER=%s
                                               export PG_USER=aether
                                               export PG_HOST=192.0.2.10
                                               """.formatted(SECRET,
                                                             SECRET,
                                                             SECRET,
                                                             SECRET,
                                                             SECRET,
                                                             SECRET,
                                                             SECRET,
                                                             SECRET,
                                                             SECRET,
                                                             SECRET);

    /// A prefix before an otherwise allowed key: only the `^` anchor keeps these out. PG_PORT is not emitted by
    /// the writer, so the allow-list entry is pinned here by a canonical line instead.
    private static final String PREFIXED_BODY = "x=" + SECRET
                                              + "; export PG_HOST=h2\nfoo export PG_DB=d\nexport PG_PORT=5432\n";

    private static final String NO_ALLOWED_LINES = "# only a comment\nexport PG_PASSWORD=" + SECRET + "\n";
    private static final String INERT = "#!/bin/bash\nexit 0\n";

    private Path state;
    private Path stubDir;
    private Path envFile;
    private Path functions;
    /// #2385: `write_env_file` takes the path of the password file, never the password itself.
    private Path passwordFile;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        ScriptGate.requiredTool("jq");
        state = tempDir.resolve("state");
        stubDir = tempDir.resolve("stub");
        envFile = tempDir.resolve("aether-test-pg.env");
        functions = tempDir.resolve("functions.sh");
        passwordFile = tempDir.resolve("pg-password");
        given(ScriptRunner.writeExecutable(stubDir.resolve("curl"), HetznerCurlStub.SCRIPT).mapToUnit());
        given(ScriptRunner.writeExecutable(stubDir.resolve("ssh"), INERT).mapToUnit());
        given(ScriptRunner.writeExecutable(stubDir.resolve("ssh-keygen"), INERT).mapToUnit());
        given(Result.lift(() -> Files.createDirectories(state))
                    .flatMap(directory -> Result.lift(() -> Files.writeString(directory.resolve("servers.json"),
                                                                              HetznerCurlStub.SERVERS)))
                    .mapToUnit());
        given(Result.lift(() -> Files.writeString(envFile, ENV_BODY)).mapToUnit());
    }

    @Test
    void printOnly_omitsPasswordAndUrl_keepsAllowListedLines() {
        var execution = executed(runScript("--print-only"));

        assertThat(execution.exitCode()).as(execution.output()).isZero();
        assertNoCredentials(execution.output());
        assertThat(execution.output()).contains("export PG_HOST=192.0.2.10")
                  .contains("export PG_USER=aether")
                  .contains("are in " + envFile);
    }

    @Test
    void reuse_omitsPasswordAndUrl_keepsAllowListedLines() {
        var execution = executed(runScript());

        assertThat(execution.exitCode()).as(execution.output()).isZero();
        assertThat(execution.output()).contains("reusing existing VM")
                  .doesNotContain(SECRET)
                  .doesNotContain("PG_PASSWORD=")
                  .doesNotContain("PG_URL=")
                  .contains("export PG_HOST=192.0.2.10");
    }

    @Test
    void printOnly_printsOnlyCanonicalAllowListedLines_forForeignEnvFile() {
        given(Result.lift(() -> Files.writeString(envFile, FOREIGN_BODY + PREFIXED_BODY)).mapToUnit());
        var execution = executed(runScript("--print-only"));

        assertThat(execution.exitCode()).as(execution.output()).isZero();
        assertThat(execution.output()).doesNotContain(SECRET)
                  .contains("export PG_USER=aether")
                  .contains("export PG_HOST=192.0.2.10")
                  .contains("export PG_PORT=5432");
        assertThat(execution.output()).doesNotContain("PG_DB=").doesNotContain("PG_HOST=h2");
    }

    @Test
    void printOnly_exitsZeroAndPrintsPointer_whenNoLineIsAllowListed() {
        given(Result.lift(() -> Files.writeString(envFile, NO_ALLOWED_LINES)).mapToUnit());
        var execution = executed(runScript("--print-only"));

        assertThat(execution.exitCode()).as(execution.output()).isZero();
        assertThat(execution.output()).doesNotContain(SECRET).contains("are in " + envFile);
    }

    @Test
    void freshProvision_writeEnvFile_omitsPasswordAndUrl_butFileCarriesThem() {
        given(Result.lift(() -> Files.writeString(functions, functionsBeforeMainFlow())).mapToUnit());
        given(Result.lift(() -> Files.writeString(passwordFile, SECRET)).mapToUnit());
        var execution = executed(runCommand("f=$1; p=$2; set --; source \"$f\"; write_env_file 42 192.0.2.10 \"$p\"",
                                            functions.toString(),
                                            passwordFile.toString()));

        assertThat(execution.exitCode()).as(execution.output()).isZero();
        assertNoCredentials(execution.output());
        assertThat(execution.output()).contains("export PG_HOST=192.0.2.10")
                  .contains("export PG_USER=aether")
                  .contains("export PG_DB=aether_forge");
        assertThat(fileContent()).contains("export PG_PASSWORD=" + SECRET)
                  .contains("export PG_URL=postgres://aether:" + SECRET + "@192.0.2.10:5432/aether_forge");
    }

    @Test
    void envFile_staysIntactAndSourceable() {
        executed(runScript("--print-only"));
        assertThat(fileContent()).isEqualTo(ENV_BODY);
    }

    private static void assertNoCredentials(String output) {
        assertThat(output).doesNotContain(SECRET).doesNotContain("PG_PASSWORD=").doesNotContain("PG_URL=");
    }

    private String fileContent() {
        return Result.lift(() -> Files.readString(envFile)).or("");
    }

    private String functionsBeforeMainFlow() {
        var script = Result.lift(() -> Files.readString(ScriptRunner.repoRoot().resolve("tools/provision-test-pg.sh"))).or("");

        return script.substring(0, script.indexOf(MAIN_FLOW));
    }

    private Map<String, String> environment() {
        var environment = new HashMap<String, String>();

        environment.put("PATH", stubDir + ":" + System.getenv("PATH"));
        environment.put("STUB_STATE", state.toString());
        environment.put("HCLOUD_TOKEN", "stub-token-not-real");
        environment.put("AETHER_SSH_KEY", "/nonexistent/key");
        environment.put("PG_ENV_FILE", envFile.toString());

        return environment;
    }

    private Result<ScriptRunner.Execution> runScript(String... flags) {
        return ScriptRunner.run(ScriptRunner.repoRoot(),
                                environment(),
                                Stream.concat(Stream.of("tools/provision-test-pg.sh"), Stream.of(flags)).toList());
    }

    private Result<ScriptRunner.Execution> runCommand(String command, String... arguments) {
        return ScriptRunner.run(ScriptRunner.repoRoot(),
                                environment(),
                                Stream.concat(Stream.of("bash", "-c", command, "_"), Stream.of(arguments)).toList());
    }
}
