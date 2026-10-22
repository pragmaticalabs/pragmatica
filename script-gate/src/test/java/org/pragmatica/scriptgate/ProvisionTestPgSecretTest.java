package org.pragmatica.scriptgate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.pragmatica.scriptgate.ScriptGate.executed;
import static org.pragmatica.scriptgate.ScriptGate.given;
import static org.assertj.core.api.Assertions.assertThat;


/// #2385 - `tools/provision-test-pg.sh` must keep the database password out of every command's argv (what `ps`
/// shows, locally and in the VM's `docker run`) and out of `bash -x` output. The password used to sit in a shell
/// variable and travel as arguments: `jq --arg user_data`, `curl -d`, the smoke test's `ssh ... docker exec -e
/// PGPASSWORD=...`, and every function call that passed it, each of which a trace prints.
///
/// The fresh-provision path runs under `bash -x` with the script's real function text (everything before its main
/// flow) sourced, against stubs for `curl`, `ssh` and `ssh-keygen` and a recording wrapper around the real `jq`.
/// Every stub appends its argv to one file. `openssl` is real, so the password is genuinely random and is read back
/// from the env file the run wrote. The single override is `wait_pg_port`, the TCP probe of the new VM's port 5432,
/// replaced by a no-op so no test binds or reaches a real port; it never sees the password.
///
/// Positive controls pair every absence: the password reaches the env file and the server request, the trace is on,
/// and every stub ran - an absence measured on a run that never generated or sent a password would prove nothing.
class ProvisionTestPgSecretTest {
    private static final String MAIN_FLOW = "# Main flow";
    private static final Pattern PASSWORD_LINE = Pattern.compile("^export PG_PASSWORD=([0-9a-f]{32})$",
                                                                 Pattern.MULTILINE);
    private static final Pattern PASSWORD_FILE = Pattern.compile("pw_file=(\\S+/pg-password)");

    /// Records argv, then answers the calls a fresh provision makes. The POST body is kept whether it came as an
    /// argument (`-d`) or on stdin (`--data-binary @-`), so the server request can be inspected either way.
    private static final String CURL = """
                                       #!/bin/bash
                                       printf '%s\\n' "curl $*" >> "$STUB_STATE/argv"
                                       out=""; method=GET; url=""; posted=""
                                       while [ $# -gt 0 ]; do
                                         case "$1" in
                                           -o) out="$2"; shift 2 ;;
                                           -X) method="$2"; shift 2 ;;
                                           -d) posted="$2"; shift 2 ;;
                                           --data-binary) [ "$2" = "@-" ] && posted=$(cat); shift 2 ;;
                                           -w|-H) shift 2 ;;
                                           -*) shift ;;
                                           *) url="$1"; shift ;;
                                         esac
                                       done
                                       case "$method $url" in
                                         POST*/servers) printf '%s' "$posted" > "$STUB_STATE/server-request.json"
                                                        body='{"server":{"id":42,"public_net":{"ipv4":{"ip":"192.0.2.10"}}}}' ;;
                                         GET*/servers/42) body='{"server":{"status":"running"}}' ;;
                                         GET*/servers?*) body='{"servers":[]}' ;;
                                         GET*/ssh_keys?*) body='{"ssh_keys":[{"id":7}]}' ;;
                                         *) body='{}' ;;
                                       esac
                                       printf '%s' "$body" > "$out"
                                       printf '200'
                                       """;

    private static final String SSH = """
                                      #!/bin/bash
                                      printf '%s\\n' "ssh $*" >> "$STUB_STATE/argv"
                                      echo 1
                                      """;

    private static final String SSH_KEYGEN = """
                                             #!/bin/bash
                                             printf '%s\\n' "ssh-keygen $*" >> "$STUB_STATE/argv"
                                             echo "256 MD5:aa:bb:cc:dd stub (ED25519)"
                                             """;

    private static final String JQ = """
                                     #!/bin/bash
                                     printf '%%s\\n' "jq $*" >> "$STUB_STATE/argv"
                                     exec %s "$@"
                                     """;

    private static final String PROVISION = "f=$1; set --; source \"$f\"; wait_pg_port() { :; }; provision_fresh";

    private Path state;
    private Path stubDir;
    private Path envFile;
    private Path sshKey;
    private Path functions;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        ScriptGate.requiredTool("openssl");
        state = tempDir.resolve("state");
        stubDir = tempDir.resolve("stub");
        envFile = tempDir.resolve("aether-test-pg.env");
        sshKey = tempDir.resolve("id_stub");
        functions = tempDir.resolve("functions.sh");
        given(ScriptRunner.writeExecutable(stubDir.resolve("curl"), CURL).mapToUnit());
        given(ScriptRunner.writeExecutable(stubDir.resolve("ssh"), SSH).mapToUnit());
        given(ScriptRunner.writeExecutable(stubDir.resolve("ssh-keygen"), SSH_KEYGEN).mapToUnit());
        given(ScriptRunner.writeExecutable(stubDir.resolve("jq"), JQ.formatted(ScriptGate.requiredTool("jq")))
                          .mapToUnit());
        given(Result.lift(() -> Files.createDirectories(state)).mapToUnit());
        given(Result.lift(() -> Files.writeString(sshKey.resolveSibling("id_stub.pub"), "ssh-ed25519 AAAASTUB stub\n"))
                    .mapToUnit());
        given(Result.lift(() -> Files.writeString(functions, functionsBeforeMainFlow())).mapToUnit());
    }

    @Test
    void freshProvision_keepsPasswordOutOfTraceAndArgv_whileEnvFileAndVmReceiveIt() {
        var execution = executed(runTraced());
        var password = passwordFromEnvFile();

        assertThat(execution.exitCode()).as(execution.output()).isZero();
        assertThat(password).as("the run wrote a generated password to the env file").isNotEmpty();
        assertThat(execution.output()).as("bash -x tracing reached the password step")
                  .contains("+ build_cloud_init")
                  .contains("openssl rand -hex 16");
        assertThat(read("argv")).as("every stub recorded its argv")
                  .contains("curl ")
                  .contains("ssh ")
                  .contains("jq ")
                  .doesNotContain(password);
        assertThat(execution.output()).as("traced and printed output").doesNotContain(password);
    }

    @Test
    void freshProvision_sendsPasswordToVmOnlyInRootOnlyEnvFile() {
        var execution = executed(runTraced());
        var password = passwordFromEnvFile();
        var request = read("server-request.json");

        assertThat(execution.exitCode()).as(execution.output()).isZero();
        assertThat(password).isNotEmpty();
        assertThat(request).as("the VM receives the password, once, in its env file")
                  .contains("POSTGRES_PASSWORD=" + password)
                  .containsOnlyOnce(password)
                  .contains("permissions: '0600'")
                  .contains("--env-file /root/aether-pg.env")
                  .doesNotContain("-e POSTGRES_PASSWORD");
        assertThat(read("argv")).as("the smoke test sends no password over ssh")
                  .contains("docker exec aether-pg psql")
                  .doesNotContain("PGPASSWORD");
    }

    @Test
    void freshProvision_removesThePasswordWorkFile() {
        var execution = executed(runTraced());
        var passwordFile = PASSWORD_FILE.matcher(execution.output())
                                        .results()
                                        .map(match -> match.group(1))
                                        .findFirst();

        assertThat(execution.exitCode()).as(execution.output()).isZero();
        assertThat(passwordFile).as("the trace names the password file").isPresent();
        assertThat(passwordFile.map(Path::of).filter(Files::exists)).as("the password file outlives the run").isEmpty();
    }

    private String passwordFromEnvFile() {
        return Option.from(PASSWORD_LINE.matcher(Result.lift(() -> Files.readString(envFile)).or(""))
                                        .results()
                                        .map(match -> match.group(1))
                                        .findFirst())
                     .or("");
    }

    private String read(String name) {
        return Result.lift(() -> Files.readString(state.resolve(name))).or("");
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
        environment.put("AETHER_SSH_KEY", sshKey.toString());
        environment.put("PG_ENV_FILE", envFile.toString());

        return environment;
    }

    private Result<ScriptRunner.Execution> runTraced() {
        return ScriptRunner.run(ScriptRunner.repoRoot(),
                                environment(),
                                List.of("bash", "-x", "-c", PROVISION, "_", functions.toString()));
    }
}
