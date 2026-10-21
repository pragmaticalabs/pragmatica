package org.pragmatica.scriptgate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Stream;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.pragmatica.scriptgate.ScriptGate.executed;
import static org.pragmatica.scriptgate.ScriptGate.given;
import static org.assertj.core.api.Assertions.assertThat;

/// `tools/provision-test-pg.sh` must not print `PG_PASSWORD` or the `PG_URL` that embeds it. Runs the
/// `--print-only` and idempotent-reuse paths for real against a stubbed Hetzner API and a fake env file;
/// the fresh-provision path prints through the same `print_env_summary`. The consumer contract is
/// pinned by the last test: the env file itself is untouched and still sourceable.
class ProvisionTestPgOutputTest {
    private static final String SECRET = "fake-secret-0123456789abcdef";
    private static final String ENV_BODY = """
                                           # PG VM 42  IP=192.0.2.10  provisioned=2026-01-01T00:00:00Z
                                           export PG_URL=postgres://aether:%s@192.0.2.10:5432/aether_forge
                                           export PG_USER=aether
                                           export PG_PASSWORD=%s
                                           export PG_HOST=192.0.2.10
                                           export PG_DB=aether_forge
                                           """.formatted(SECRET, SECRET);
    private static final String INERT = "#!/bin/bash\nexit 0\n";

    private Path state;
    private Path stubDir;
    private Path envFile;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        ScriptGate.requiredTool("jq");
        state = tempDir.resolve("state");
        stubDir = tempDir.resolve("stub");
        envFile = tempDir.resolve("aether-test-pg.env");
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
    void printOnly_omitsPasswordAndUrl_keepsNonSecretLines() {
        var execution = executed(runScript("--print-only"));

        assertThat(execution.exitCode()).as(execution.output()).isZero();
        assertThat(execution.output()).doesNotContain(SECRET)
                  .doesNotContain("PG_PASSWORD=")
                  .doesNotContain("PG_URL=")
                  .contains("export PG_HOST=192.0.2.10")
                  .contains("export PG_USER=aether")
                  .contains("are in " + envFile);
    }

    @Test
    void reuse_omitsPasswordAndUrl_keepsNonSecretLines() {
        var execution = executed(runScript());

        assertThat(execution.exitCode()).as(execution.output()).isZero();
        assertThat(execution.output()).contains("reusing existing VM")
                  .doesNotContain(SECRET)
                  .doesNotContain("PG_PASSWORD=")
                  .doesNotContain("PG_URL=")
                  .contains("export PG_HOST=192.0.2.10");
    }

    @Test
    void envFile_staysIntactAndSourceable() {
        executed(runScript("--print-only"));

        assertThat(Result.lift(() -> Files.readString(envFile)).or("")).isEqualTo(ENV_BODY);
    }

    private Result<ScriptRunner.Execution> runScript(String... flags) {
        var environment = new HashMap<String, String>();

        environment.put("PATH", stubDir + ":" + System.getenv("PATH"));
        environment.put("STUB_STATE", state.toString());
        environment.put("HCLOUD_TOKEN", "stub-token-not-real");
        environment.put("AETHER_SSH_KEY", "/nonexistent/key");
        environment.put("PG_ENV_FILE", envFile.toString());

        return ScriptRunner.run(ScriptRunner.repoRoot(), environment, command(flags));
    }

    private static List<String> command(String... flags) {
        return Stream.concat(Stream.of("tools/provision-test-pg.sh"), Stream.of(flags)).toList();
    }
}
