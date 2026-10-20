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


/// #2273 - `tools/pg-firewall.sh destroy`, run for real against a stubbed Hetzner API.
///
/// The script threw away the response of the firewall delete and logged `destroyed` even on a 409 (firewall still
/// attached), leaving the firewall in place. Only `curl` is replaced; the recorded calls prove the stub answered.
/// The refusal tests are paired with a control driving the same fixture to a real delete.
class PgFirewallDestroyTest {
    private static final String DELETE_FIREWALL = "DELETE https://api.hetzner.cloud/v1/firewalls/99";

    private Path state;
    private Path stubDir;
    private Path bareDir;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        ScriptGate.requiredTool("jq");
        state = tempDir.resolve("state");
        stubDir = tempDir.resolve("stub");
        bareDir = tempDir.resolve("bare");
        given(ScriptRunner.writeExecutable(stubDir.resolve("curl"), HetznerCurlStub.SCRIPT).mapToUnit());
        given(write("servers.json", HetznerCurlStub.SERVERS));
        given(write("firewalls.json", HetznerCurlStub.FIREWALLS));
    }

    /// Control: the same fixture, no refusal.
    @Test
    void destroy_deletesTheFirewall_andSaysSo() {
        var execution = executed(runDestroy());

        assertThat(execution.exitCode()).as(execution.output()).isZero();
        assertThat(execution.output()).contains("destroyed firewall 'aether-pg-firewall' (id=99)");
        assertThat(calls()).contains(DELETE_FIREWALL);
    }

    @Test
    void destroy_failsNamingTheFirewall_whenTheDeleteIsRefused() {
        given(write("fail-delete", "/firewalls/99\n"));
        var execution = executed(runDestroy());

        assertThat(execution.exitCode()).as(execution.output()).isEqualTo(2);
        assertThat(execution.output()).contains("could not delete firewall 'aether-pg-firewall' (id=99)")
                  .contains("HTTP 409")
                  .doesNotContain("destroyed firewall");
        assertThat(calls()).as("the delete was really attempted").contains(DELETE_FIREWALL);
    }

    /// A refused detach is named; with the delete then refused too, the failure is the firewall's.
    @Test
    void destroy_namesARefusedDetach() {
        given(write("fail-post", "/remove_from_resources\n"));
        given(write("fail-delete", "/firewalls/99\n"));
        var execution = executed(runDestroy());

        assertThat(execution.exitCode()).as(execution.output()).isEqualTo(2);
        assertThat(execution.output()).contains("could not detach firewall 99 from server 42")
                  .contains("could not delete firewall 'aether-pg-firewall' (id=99)");
    }

    /// jq is how the script reads every API answer. Without it `destroy` used to log "no firewall ... to destroy"
    /// and exit 0, leaving a real firewall in place. Now it refuses, naming the tool, before any API call.
    @Test
    void destroy_refusesNamingJq_whenJqIsNotInstalled() {
        given(ScriptGate.linkTools(bareDir, List.of("bash")));
        var execution = executed(runDestroy(stubDir + ":" + bareDir));

        assertThat(execution.exitCode()).as(execution.output()).isEqualTo(1);
        assertThat(execution.output()).contains("missing dependency: jq")
                  .doesNotContain("no firewall named");
        assertThat(calls()).as("refused before any API call").isEmpty();
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

    private Result<ScriptRunner.Execution> runDestroy() {
        return runDestroy(stubDir + ":" + System.getenv("PATH"));
    }

    private Result<ScriptRunner.Execution> runDestroy(String path) {
        var environment = new HashMap<String, String>();

        environment.put("PATH", path);
        environment.put("STUB_STATE", state.toString());
        environment.put("HCLOUD_TOKEN", "stub-token-not-real");

        return ScriptRunner.runHermetic(ScriptRunner.repoRoot(), environment, List.of("tools/pg-firewall.sh", "destroy"));
    }
}
