// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.pragmatica.aether.config.ConfigLoader;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// #2052: `Main` used to turn a GIVEN config file that failed to load or validate into `Option.empty()` plus one ERROR line, and boot on
/// defaults, dropping the operator's TLS, port, peers and secret settings. A file named by `--config=` must now load and validate or the
/// node REFUSES to start: exit code 65, a FATAL line on stderr naming the file and the cause. No `--config=` still boots on defaults.
///
/// Real `Main` in a child JVM (the refusal is a process exit). The child has no `AETHER_CLUSTER_NAME`, so a run that gets PAST the config
/// stage stops at the very next gate with exit 1 and that gate's message: that is the control proving a config was not refused.
/// Positive controls first: the fixtures are checked in-process (valid loads, invalid does not), so a green here is not a fixture error.
class MainConfigGivenBootTest {
    private static final int CONFIG_REFUSED = 65;
    private static final int NEXT_GATE_EXIT = 1;
    private static final String NEXT_GATE = "AETHER_CLUSTER_NAME is not set";
    private static final long DEADLINE_SECONDS = 90;
    private static final String VALID = "[cluster]\nenvironment = \"docker\"\n";
    private static final String MALFORMED = "[cluster\nnodes = \n";
    private static final String FAILS_VALIDATION = "[cluster]\nenvironment = \"docker\"\nnodes = 2\n";

    private static final String JWT_WITHOUT_JWKS = "[cluster]\nenvironment = \"docker\"\n\n[app-http]\nenabled = true\nsecurity_mode = \"jwt\"\n";
    private static final String JWT_WITH_JWKS = JWT_WITHOUT_JWKS + "jwks_url = \"https://issuer.example/.well-known/jwks.json\"\n";

    private record Run(int exit, String out, String err) {}

    @Test
    void fixtures_areWhatTheTestsClaim() {
        assertThat(ConfigLoader.loadFromString(VALID).isSuccess()).as("CONTROL: the valid fixture loads").isTrue();
        assertThat(ConfigLoader.loadFromString(MALFORMED).isFailure()).as("CONTROL: the malformed fixture does not parse").isTrue();
        assertThat(ConfigLoader.loadFromString(FAILS_VALIDATION).isFailure()).as("CONTROL: the invalid fixture fails validation").isTrue();
    }

    @Test
    void aGivenConfigThatDoesNotParse_refusesToStart_withExit65_andNamesFileAndCauseOnStderr(@TempDir Path dir) throws Exception {
        var file = write(dir, "malformed.toml", MALFORMED);
        var run = boot(dir, "--config=" + file);

        assertRefused(run, file.toString());
        assertThat(run.err()).as("the cause is named").containsIgnoringCase("could not be loaded or validated");
    }

    @Test
    void aGivenConfigThatFailsValidation_refusesToStart(@TempDir Path dir) throws Exception {
        var file = write(dir, "invalid.toml", FAILS_VALIDATION);

        assertRefused(boot(dir, "--config=" + file), file.toString());
    }

    /// #909: `security_mode = "jwt"` on an enabled server with no `jwks_url` is a contradiction the node cannot enforce. It fails validation
    /// with a message naming the missing setting, so the given file refuses the boot (exit 65). The same file WITH a `jwks_url` is not refused.
    @Test
    void aGivenConfigWithJwtButNoJwksUrl_refusesToStart_namingTheMissingSetting(@TempDir Path dir) throws Exception {
        assertThat(ConfigLoader.loadFromString(JWT_WITH_JWKS).isSuccess()).as("CONTROL: the same file with jwks_url loads").isTrue();
        assertThat(ConfigLoader.loadFromString(JWT_WITHOUT_JWKS).isFailure()).as("CONTROL: without jwks_url it does not").isTrue();
        var file = write(dir, "jwt-no-jwks.toml", JWT_WITHOUT_JWKS);
        var run = boot(dir, "--config=" + file);

        assertRefused(run, file.toString());
        assertThat(run.err()).contains("jwks_url");
        assertPastConfigStage(boot(dir, "--config=" + write(dir, "jwt-jwks.toml", JWT_WITH_JWKS)));
    }

    @Test
    void aGivenConfigThatDoesNotExist_refusesToStart_insteadOfBootingOnDefaults(@TempDir Path dir) throws Exception {
        var missing = dir.resolve("absent.toml");

        assertRefused(boot(dir, "--config=" + missing), missing.toString());
    }

    /// The operator-facing MESSAGE is the point: each guard names its own cause, so these are pinned by text (the blank and the
    /// regular-file checks are otherwise redundant with each other for the exit code).
    @Test
    void aGivenConfigThatIsADirectory_refusesWithTheNotARegularFileMessage(@TempDir Path dir) throws Exception {
        var run = boot(dir, "--config=" + dir);

        assertRefused(run, dir.toString());
        assertThat(run.err()).contains("does not exist or is not a regular file");
    }

    @Test
    void anEmptyConfigArgument_refusesWithTheEmptyPathMessage(@TempDir Path dir) throws Exception {
        var run = boot(dir, "--config=");

        assertRefused(run, "--config=");
        assertThat(run.err()).contains("was given with an empty path");
    }

    @Test
    void aMissingFile_refusesWithTheSameNotARegularFileMessage(@TempDir Path dir) throws Exception {
        assertThat(boot(dir, "--config=" + dir.resolve("absent2.toml")).err()).contains("does not exist or is not a regular file");
    }

    /// The deliberate half: nothing given, defaults apply, the boot goes on to the next gate.
    @Test
    void noConfigArgument_stillGetsPastTheConfigStage(@TempDir Path dir) throws Exception {
        assertPastConfigStage(boot(dir));
    }

    @Test
    void aValidGivenConfig_isNotRefused(@TempDir Path dir) throws Exception {
        assertPastConfigStage(boot(dir, "--config=" + write(dir, "valid.toml", VALID)));
    }

    /// #828 — the node reads its cluster secret from the file `AETHER_CLUSTER_SECRET_FILE` names, and REFUSES to start when that
    /// file and `AETHER_CLUSTER_SECRET` are both set to different values: the node cannot know which one is meant, and a wrong guess
    /// derives its certificates from the wrong secret. The refusal names the variables and never a value.
    @Test
    void aSecretFileAndADifferingSecretVariable_refuseToStart_withExit65_namingNoValue(@TempDir Path dir) throws Exception {
        var file = write(dir, "secret", "file-sentinel-1a2b");
        var run = boot(dir, java.util.Map.of("AETHER_CLUSTER_SECRET_FILE", file.toString(), "AETHER_CLUSTER_SECRET", "plain-sentinel-3c4d"));

        assertRefused(run, "AETHER_CLUSTER_SECRET_FILE");
        assertThat(run.out() + run.err()).doesNotContain("file-sentinel-1a2b").doesNotContain("plain-sentinel-3c4d");
    }

    @Test
    void anUnreadableSecretFile_refusesToStart_namingThePath(@TempDir Path dir) throws Exception {
        var missing = dir.resolve("no-such-secret");

        assertRefused(boot(dir, java.util.Map.of("AETHER_CLUSTER_SECRET_FILE", missing.toString())), missing.toString());
    }

    @Test
    void aSecretFileAlone_andAnEqualPair_getPastTheConfigStage(@TempDir Path dir) throws Exception {
        var file = write(dir, "secret", "file-sentinel-1a2b\n");

        assertPastConfigStage(boot(dir, java.util.Map.of("AETHER_CLUSTER_SECRET_FILE", file.toString())));
        assertPastConfigStage(boot(dir, java.util.Map.of("AETHER_CLUSTER_SECRET_FILE", file.toString(), "AETHER_CLUSTER_SECRET", "file-sentinel-1a2b")));
    }

    @Test
    void resolveConfig_isPure_noneForNoArgument_failureForEveryBadArgument(@TempDir Path dir) throws Exception {
        assertThat(Main.resolveConfig(Option.none()).map(Option::isEmpty).or(false)).as("no argument is no configuration, not a failure").isTrue();

        for (var bad : List.of("", "  ", dir.resolve("absent.toml").toString(), dir.toString(), "/tmp/with\0nul")) {
            assertThat(Main.resolveConfig(Option.some(bad)).isFailure()).as("given: '" + bad.replace("\0", "\\0") + "'").isTrue();
        }

        assertThat(Main.resolveConfig(Option.some(write(dir, "ok.toml", VALID).toString())).map(Option::isPresent).or(false)).isTrue();
    }

    private static void assertRefused(Run run, String named) {
        assertThat(run.exit()).as("exit code; stderr:\n%s\nstdout:\n%s", run.err(), run.out()).isEqualTo(CONFIG_REFUSED);
        assertThat(run.err()).as("the operator-visible signal: a FATAL line on stderr").contains("FATAL: refusing to start").contains(named);
        assertThat(run.out() + run.err()).as("it must not have gone on to a later gate").doesNotContain(NEXT_GATE);
    }

    private static void assertPastConfigStage(Run run) {
        assertThat(run.exit()).as("stdout:\n%s\nstderr:\n%s", run.out(), run.err()).isEqualTo(NEXT_GATE_EXIT);
        assertThat(run.out() + run.err()).as("stopped at the NEXT gate, so the config stage let it through").contains(NEXT_GATE);
        assertThat(run.err()).doesNotContain("refusing to start");
    }

    private static Path write(Path dir, String name, String content) throws Exception {
        return Files.writeString(dir.resolve(name), content);
    }

    private static Run boot(Path work, String... args) throws Exception {
        return boot(work, java.util.Map.of(), args);
    }

    private static Run boot(Path work, java.util.Map<String, String> env, String... args) throws Exception {
        var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var command = new ArrayList<>(List.of(java, "-Xmx256m", "-cp", System.getProperty("java.class.path"), Main.class.getName()));

        command.addAll(List.of(args));
        var dir = Files.createTempDirectory(work, "main-config-boot");
        var out = dir.resolve("out.txt");
        var err = dir.resolve("err.txt");
        var builder = new ProcessBuilder(command).redirectOutput(out.toFile()).redirectError(err.toFile());

        builder.environment().remove("AETHER_CLUSTER_NAME");
        builder.environment().remove("AETHER_CLUSTER_SECRET");
        builder.environment().remove("AETHER_CLUSTER_SECRET_FILE");
        builder.environment().putAll(env);
        var child = builder.start();

        if (!child.waitFor(DEADLINE_SECONDS, TimeUnit.SECONDS)) {
            child.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
            return new Run(-1, Files.readString(out), Files.readString(err) + "\n[TIMED OUT: the child kept running]");
        }

        return new Run(child.exitValue(), Files.readString(out), Files.readString(err));
    }
}
