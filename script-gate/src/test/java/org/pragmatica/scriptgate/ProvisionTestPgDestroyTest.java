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

    private static final String STUB_CURL = """
                                            #!/bin/bash
                                            out=""; method=GET; url=""
                                            while [ $# -gt 0 ]; do
                                              case "$1" in
                                                -o) out="$2"; shift 2 ;;
                                                -X) method="$2"; shift 2 ;;
                                                -H|-d|-w) shift 2 ;;
                                                -*) shift ;;
                                                *) url="$1"; shift ;;
                                              esac
                                            done
                                            echo "$method $url" >> "$STUB_STATE/calls"
                                            case "$method $url" in
                                              GET*/servers?*) cat "$STUB_STATE/servers.json" > "$out" ;;
                                              GET*/ssh_keys?*) cat "$STUB_STATE/ssh_keys.json" > "$out" ;;
                                              *) : > "$out" ;;
                                            esac
                                            printf '200'
                                            """;

    private static final String SERVERS = """
                                          {"servers":[{"id":42,"name":"aether-test-pg-1","public_net":{"ipv4":{"ip":"192.0.2.10"}}}]}
                                          """;

    private static final String SSH_KEYS = """
                                           {"ssh_keys":[{"id":7,"name":"aether-test-pg-key"}]}
                                           """;

    private Path state;
    private Path stubDir;
    private Path envFile;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        state = tempDir.resolve("state");
        stubDir = tempDir.resolve("stub");
        envFile = tempDir.resolve("aether-test-pg.env");
        given(ScriptRunner.writeExecutable(stubDir.resolve("curl"), STUB_CURL).mapToUnit());
        given(write("servers.json", SERVERS));
        given(write("ssh_keys.json", SSH_KEYS));
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

        return ScriptRunner.run(ScriptRunner.repoRoot(), environment, command(extraEnvironment, flag));
    }

    /// The test JVM may itself carry `AETHER_SSH_KEY`; `env -u` is what makes "unset" true for the
    /// script, since a process builder can add variables but a map of additions cannot remove one.
    private List<String> command(Map<String, String> extraEnvironment, String flag) {
        return extraEnvironment.containsKey("AETHER_SSH_KEY")
               ? List.of("tools/provision-test-pg.sh", flag)
               : List.of("env", "-u", "AETHER_SSH_KEY", "tools/provision-test-pg.sh", flag);
    }
}
