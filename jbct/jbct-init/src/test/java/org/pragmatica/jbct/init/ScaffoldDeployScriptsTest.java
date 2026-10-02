package org.pragmatica.jbct.init;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// #1778: the cluster's artifact repository is write-once and takes no SNAPSHOT, so the generated scripts must
/// not push the scaffold's `1.0.0-SNAPSHOT`. `deploy-test.sh` stamps a unique release version per push
/// (`<base>-<short git sha>` from a clean checkout, else `<base>-<UTC timestamp>`); `deploy-prod.sh` refuses a
/// SNAPSHOT with a clear message. The generated scripts are RUN here against stub `mvn`, `git` and `aether`
/// executables, so what is pinned is the behaviour, not the text.
class ScaffoldDeployScriptsTest {
    @TempDir
    Path tempDir;

    private Path project;
    private Path bin;
    private Path aetherLog;
    private Path mvnLog;

    @BeforeEach
    void generateProject() throws IOException {
        project = tempDir.resolve("my-slice");
        SliceProjectInitializer.sliceProjectInitializer(project, "org.example", "my-slice")
                               .flatMap(SliceProjectInitializer::initialize)
                               .onFailure(cause -> org.junit.jupiter.api.Assertions.fail(cause.message()));
        bin = Files.createDirectories(tempDir.resolve("bin"));
        aetherLog = tempDir.resolve("aether.log");
        mvnLog = tempDir.resolve("mvn.log");
        stub("aether", "echo \"$@\" >> '" + aetherLog + "'\n");
        stub("mvn", """
                    echo "$@" >> '%s'
                    case "$*" in
                        *help:evaluate*) echo "$STUB_VERSION" ;;
                        *versions:set*) echo "<!-- stamped -->" >> pom.xml ;;
                    esac
                    """.formatted(mvnLog));
    }

    @Test
    void deployTest_stampsTheShortGitSha_onACleanCheckout_andRestoresThePom() throws Exception {
        stub("git", """
                    case "$*" in
                        *--is-inside-work-tree*) echo true ;;
                        *status*) ;;
                        *rev-parse*--short=8*) echo abc12345 ;;
                    esac
                    """);
        var pomBefore = Files.readString(project.resolve("pom.xml"));

        var run = run("deploy-test.sh", "1.0.0-SNAPSHOT", "");

        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(Files.readAllLines(aetherLog)).contains("artifacts push org.example:my-slice:1.0.0-abc12345",
                                                           "blueprints deploy org.example:my-slice:1.0.0-abc12345 --wait");
        assertThat(Files.readString(mvnLog)).contains("versions:set -DnewVersion=1.0.0-abc12345");
        assertThat(Files.readString(project.resolve("pom.xml"))).as("the pom is restored").isEqualTo(pomBefore);
    }

    @Test
    void deployTest_stampsAUtcTimestamp_whenTheTreeIsNotAGitCheckout() throws Exception {
        stub("git", "exit 128\n");

        var run = run("deploy-test.sh", "2.1.0-SNAPSHOT", "");

        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(Files.readAllLines(aetherLog).getFirst()).matches("artifacts push org\\.example:my-slice:2\\.1\\.0-\\d{14}");
    }

    @Test
    void deployTest_stampsATimestamp_whenTheTreeIsDirty() throws Exception {
        stub("git", """
                    case "$*" in
                        *--is-inside-work-tree*) echo true ;;
                        *status*) echo " M pom.xml" ;;
                    esac
                    """);

        var run = run("deploy-test.sh", "1.0.0-SNAPSHOT", "");

        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(Files.readAllLines(aetherLog).getFirst()).matches("artifacts push org\\.example:my-slice:1\\.0\\.0-\\d{14}");
    }

    @Test
    void deployProd_refusesASnapshot_beforeAnyBuildOrPush() throws Exception {
        var run = run("deploy-prod.sh", "1.0.0-SNAPSHOT", "yes\n");

        assertThat(run.exit()).isNotZero();
        assertThat(run.output()).contains("1.0.0-SNAPSHOT").contains("release version").contains("versions:set");
        assertThat(Files.exists(aetherLog)).as("nothing was pushed").isFalse();
        assertThat(Files.readString(mvnLog)).as("nothing was built").doesNotContain("verify");
    }

    @Test
    void deployProd_pushesTheRealReleaseVersion() throws Exception {
        var run = run("deploy-prod.sh", "1.4.2", "yes\n");

        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(Files.readAllLines(aetherLog)).contains("artifacts push org.example:my-slice:1.4.2",
                                                           "blueprints deploy org.example:my-slice:1.4.2 --wait");
    }

    private void stub(String name, String body) throws IOException {
        var file = bin.resolve(name);

        Files.writeString(file, "#!/bin/bash\n" + body);
        assertThat(file.toFile().setExecutable(true)).isTrue();
    }

    private record Run(int exit, String output) {}

    private Run run(String script, String projectVersion, String stdin) throws Exception {
        var builder = new ProcessBuilder(List.of("bash", project.resolve(script).toString())).directory(project.toFile())
                                                                                              .redirectErrorStream(true);
        Map<String, String> env = builder.environment();

        env.put("PATH", bin + ":" + env.get("PATH"));
        env.put("STUB_VERSION", projectVersion);
        env.remove("DEPLOY_STAMP");

        var process = builder.start();

        process.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();

        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();

        return new Run(process.exitValue(), output);
    }
}
