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


/// #2193 - `tools/provision-test-pg.sh --destroy`, run for real against a stubbed Hetzner API.
///
/// The script's own comment exempted `--destroy` from `AETHER_SSH_KEY`, but its condition exempted
/// only `--print-only`, so a teardown with the variable unset exited 1 and deleted nothing - after
/// the PG firewall had already been removed, which left the VM on 5432 with no perimeter. Only `curl`
/// is replaced, by a stub on PATH that records every call; no test here can reach a real cloud API,
/// and the recorded calls are how each test proves the stub, not the network, answered.
///
/// Every refusal-shaped assertion is paired with a positive control driving the same fixture to
/// success, so "refused for the reason under test" cannot be confused with "the fixture never worked".
class ProvisionTestPgDestroyTest {
    private static final String KEY_MISSING = "AETHER_SSH_KEY is not set";
    /// The script's preflight demands `ssh` and `ssh-keygen` on PATH, but neither `--destroy` nor the
    /// refusal before the first API call ever runs them; stubbed so a worker image without openssh cannot
    /// change a verdict. `jq` is different - the script parses every API answer with it - so it stays real.
    private static final String INERT = "#!/bin/bash\nexit 0\n";

    private Path state;
    private Path stubDir;
    private Path envFile;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        state = tempDir.resolve("state");
        stubDir = tempDir.resolve("stub");
        envFile = tempDir.resolve("aether-test-pg.env");
        given(ScriptRunner.writeExecutable(stubDir.resolve("curl"), HetznerCurlStub.SCRIPT).mapToUnit());
        given(ScriptRunner.writeExecutable(stubDir.resolve("ssh"), INERT).mapToUnit());
        given(ScriptRunner.writeExecutable(stubDir.resolve("ssh-keygen"), INERT).mapToUnit());
        given(write("servers.json", HetznerCurlStub.SERVERS));
        given(write("ssh_keys.json", HetznerCurlStub.SSH_KEYS));
        given(Result.lift(() -> Files.writeString(envFile, "export PG_URL=stub\n")).mapToUnit());
    }

    /// The ticket's acceptance criterion: key unset, token present, the labelled VM is deleted.
    @Test
    void destroy_deletesTheLabelledVm_withoutAnSshKey() {
        var execution = executed(runScript(Map.of(), "--destroy"));

        assertThat(execution.exitCode()).as(execution.output()).isZero();
        assertThat(execution.output()).doesNotContain(KEY_MISSING);
        assertThat(calls()).contains("DELETE https://api.hetzner.cloud/v1/servers/42")
                  .contains("DELETE https://api.hetzner.cloud/v1/ssh_keys/7");
        assertThat(envFile).doesNotExist();
    }

    /// Positive control. Same fixture, key set: proves the stub drives the destroy path to
    /// completion, so the test above cannot be passing on a fixture that never worked.
    @Test
    void destroy_deletesTheLabelledVm_withAnSshKey() {
        var execution = executed(runScript(Map.of("AETHER_SSH_KEY", "/nonexistent/key"), "--destroy"));

        assertThat(execution.exitCode()).as(execution.output()).isZero();
        assertThat(calls()).contains("DELETE https://api.hetzner.cloud/v1/servers/42");
    }

    /// The guard is narrowed, not removed: a provisioning run without a key still refuses, before
    /// any API call.
    @Test
    void provision_refusesWithoutAnSshKey_beforeTouchingTheApi() {
        var execution = executed(runScript(Map.of(), "--fresh"));

        assertThat(execution.exitCode()).as(execution.output()).isEqualTo(1);
        assertThat(execution.output()).contains(KEY_MISSING);
        assertThat(calls()).isEmpty();
    }

    /// #2273: a refused delete is a failed teardown that names the resource, and the other deletes are still attempted.
    @Test
    void destroy_failsNamingTheVm_whenTheServerDeleteIsRefused() {
        given(write("fail-delete", "/servers/42\n"));
        var execution = executed(runScript(Map.of(), "--destroy"));

        assertThat(execution.exitCode()).as(execution.output()).isEqualTo(2);
        assertThat(execution.output()).contains("could not delete server 42 (aether-test-pg-1)")
                  .contains("destroy completed with 1 failure(s)")
                  .doesNotContain("destroy complete\n");
        assertThat(calls()).as("the ssh key delete was still attempted")
                  .contains("DELETE https://api.hetzner.cloud/v1/ssh_keys/7");
    }

    @Test
    void destroy_failsNamingTheSshKey_whenTheKeyDeleteIsRefused() {
        given(write("fail-delete", "/ssh_keys/7\n"));
        var execution = executed(runScript(Map.of(), "--destroy"));

        assertThat(execution.exitCode()).as(execution.output()).isEqualTo(2);
        assertThat(execution.output()).contains("could not delete ssh_key 7 (aether-test-pg-key)")
                  .contains("destroy completed with 1 failure(s)");
        assertThat(calls()).as("the server delete succeeded first")
                  .contains("DELETE https://api.hetzner.cloud/v1/servers/42");
    }

    private Result<Unit> write(String name, String content) {
        return Result.lift(() -> Files.createDirectories(state))
                     .flatMap(directory -> Result.lift(() -> Files.writeString(directory.resolve(name),
                                                                               content)))
                     .mapToUnit();
    }

    private String calls() {
        return Result.lift(() -> Files.readString(state.resolve("calls"))).or("");
    }

    private Result<ScriptRunner.Execution> runScript(Map<String, String> extraEnvironment, String flag) {
        var environment = new HashMap<String, String>();

        environment.put("PATH", stubDir + ":" + System.getenv("PATH"));
        environment.put("STUB_STATE", state.toString());
        environment.put("HCLOUD_TOKEN", "stub-token-not-real");
        environment.put("PG_ENV_FILE", envFile.toString());
        environment.putAll(extraEnvironment);

        return ScriptRunner.run(ScriptRunner.repoRoot(), environment, List.of("tools/provision-test-pg.sh", flag));
    }
}
