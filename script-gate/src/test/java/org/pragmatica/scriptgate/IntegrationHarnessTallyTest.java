package org.pragmatica.scriptgate;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.pragmatica.scriptgate.ScriptGate.executed;
import static org.assertj.core.api.Assertions.assertThat;


/// The integration harness must never report a verdict its own log contradicts. The subjects are the REAL
/// `aether/tests/integration/lib/common.sh` and the REAL `test_checkpoint_driver_is_alive` from suite 02w, extracted
/// by name; only the cluster reads are stubbed.
class IntegrationHarnessTallyTest {
    private static final Path INTEGRATION = Path.of("aether", "tests", "integration");
    private static final String SUITE_02W = "suites/02w-entity-crash/test-entity-crash-durability.sh";

    /// #1511: a [FAIL] from a background worker, and one logged outside any test (cleanup), both count against
    /// the suite. Before, the latch was a shell variable, so the suite printed `PASSED: 2 FAILED: 0` and exited 0.
    @Test
    void failLoggedInABackgroundWorkerOrOutsideATest_countsAgainstTheSuite() {
        var execution = bash("""
            source lib/common.sh >/dev/null || { echo "common.sh failed to source"; exit 3; }
            t_ok() { log_pass "fine"; }
            t_worker() { ( log_fail "publisher worker: no endpoint" ) & wait; return 0; }
            run_test ok t_ok
            run_test worker t_worker
            log_fail "cleanup: poweron failed"
            print_summary
            """);

        assertThat(execution.exitCode()).as(execution.output()).isEqualTo(1);
        assertThat(execution.output()).contains("PASSED:  1")
                  .contains("FAILED:  2")
                  .contains("outside-a-test: cleanup: poweron failed");
    }

    /// #1512: `wait_for` evaluates its predicate in a fork, so the node count it collected never reached the caller
    /// and the PASS line read "across 0 node(s)". The count reported must be the one this shell holds.
    @Test
    void checkpointDriverAlive_reportsTheNodesItActuallyChecked() {
        var execution = bash(checkpointScenario("CHECKPOINT_HOSTING=2; CHECKPOINT_WRITES=3; CHECKPOINT_DETAIL='node-1=3w/0f'",
                                                ""));

        assertThat(execution.exitCode()).as(execution.output()).isZero();
        assertThat(execution.output()).contains("checkpoint driver alive across 2 node(s)");
    }

    /// #1512: an empty node set can never pass, whatever the wait reported.
    @Test
    void checkpointDriverAlive_emptyNodeSet_fails() {
        var execution = bash(checkpointScenario("CHECKPOINT_HOSTING=0; CHECKPOINT_WRITES=0; CHECKPOINT_DETAIL=''",
                                                "wait_for() { return 0; }"));

        assertThat(execution.exitCode()).as(execution.output()).isNotZero();
        assertThat(execution.output()).doesNotContain("[PASS]").contains("no node reported an entity keyspace");
    }

    private static String checkpointScenario(String collected, String waitForOverride) {
        return """
            source lib/common.sh >/dev/null || { echo "common.sh failed to source"; exit 3; }
            eval "$(sed -n '/^test_checkpoint_driver_is_alive()/,/^}/p' %s)"
            type test_checkpoint_driver_is_alive >/dev/null || { echo "extraction found nothing"; exit 3; }
            collect_checkpoints() { %s; }
            %s
            test_checkpoint_driver_is_alive
            """.formatted(SUITE_02W, collected, waitForOverride);
    }

    private static ScriptRunner.Execution bash(String script) {
        // lib/common.sh refuses to source without TARGET_HOST (`: "${TARGET_HOST:?...}"`). Setting it here keeps the
        // test independent of the caller's environment: it passed only on hosts that export TARGET_HOST.
        return executed(ScriptRunner.run(ScriptRunner.repoRoot().resolve(INTEGRATION),
                                         Map.of("TARGET_HOST", "127.0.0.1"),
                                         List.of("bash", "-c", "set -uo pipefail\n" + script)));
    }
}
