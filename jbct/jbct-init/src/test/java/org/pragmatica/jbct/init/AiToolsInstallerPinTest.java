package org.pragmatica.jbct.init;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.pragmatica.http.HttpClientError;
import org.pragmatica.jbct.init.AiToolsRef.AiToolsRefError;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// #2363: `jbct init` installs the AI tools at the tag of the running build, records the ref, and never falls back
/// to `main`. The network is the injected [FakeGitHub]; the cache is a temp directory.
class AiToolsInstallerPinTest {
    // A file name no real ~/.claude carries, so the global-skip rule never hides it.
    private static final String FILE = "skills/zz-pin-2363/SKILL.md";
    private static final String TAG_A = "ai-tools-v7.8.9";
    private static final String TAG_B = "ai-tools-v7.8.10";

    @Test
    void install_fetchesAtTheTagDerivedFromTheVersion_andRecordsIt(@TempDir Path dir) throws Exception {
        var github = FakeGitHub.fakeGitHub().withRef(TAG_A, "sha-a", Map.of(FILE, "tools at A"));

        var outcome = install(dir, github, "7.8.9");

        assertThat(outcome.fold(Cause::message, AiToolsOutcome::ref)).as("outcome ref").isEqualTo(TAG_A);
        assertThat(github.requests()).contains("https://api.github.com/repos/siy/coding-technology/commits/" + TAG_A);
        assertThat(github.requested("/main")).as("never main").isFalse();
        assertThat(Files.readString(dir.resolve("project/.claude").resolve(FILE))).isEqualTo("tools at A");
        assertThat(Files.readString(dir.resolve("cache/.ref"))).as("recorded ref").isEqualTo(TAG_A + "@sha-a");
    }

    @Test
    void install_missingTag_failsByName_withTypedCause_andInstallsNothing(@TempDir Path dir) {
        var github = FakeGitHub.fakeGitHub().withRef("main", "sha-main", Map.of(FILE, "tip of main"));

        var cause = install(dir, github, "7.8.9").fold(failure -> failure, _ -> null);

        assertThat(cause).isInstanceOf(AiToolsRefError.TagNotFound.class);
        assertThat(cause.message()).contains(TAG_A);
        assertThat(dir.resolve("project/.claude").resolve(FILE)).as("no fallback to main").doesNotExist();
    }

    @Test
    void install_missingTag_isNotPapereredOverByAnExistingCache(@TempDir Path dir) {
        install(dir, FakeGitHub.fakeGitHub().withRef(TAG_A, "sha-a", Map.of(FILE, "tools at A")), "7.8.9");

        var cause = install(dir, FakeGitHub.fakeGitHub(), "7.8.9").fold(failure -> failure, _ -> null);

        assertThat(cause).isInstanceOf(AiToolsRefError.TagNotFound.class);
    }

    @Test
    void install_snapshotBuild_isRefusedBeforeAnyRequest(@TempDir Path dir) {
        var github = FakeGitHub.fakeGitHub();

        var cause = install(dir, github, "7.8.9-SNAPSHOT").fold(failure -> failure, _ -> null);

        assertThat(cause).isInstanceOf(AiToolsRefError.UnreleasedBuild.class);
        assertThat(cause.message()).contains("ai-tools-v7.8.9-SNAPSHOT");
        assertThat(github.requests()).isEmpty();
    }

    @Test
    void install_offline_reusesTheCacheOfThisRef_butNeverOneOfAnotherRef(@TempDir Path dir) {
        install(dir, FakeGitHub.fakeGitHub().withRef(TAG_A, "sha-a", Map.of(FILE, "tools at A")), "7.8.9");

        var sameRef = install(dir, FakeGitHub.offlineGitHub(), "7.8.9");
        var otherRef = install(dir, FakeGitHub.offlineGitHub(), "7.8.10");

        assertThat(sameRef.fold(Cause::message, AiToolsOutcome::ref)).isEqualTo(TAG_A);
        assertThat(otherRef.fold(failure -> failure, _ -> null)).isInstanceOf(HttpClientError.ConnectionFailed.class);
    }

    @Test
    void install_newVersion_movesTheCacheToItsTag_evenWhenTheCommitIsTheSame(@TempDir Path dir) throws Exception {
        install(dir, FakeGitHub.fakeGitHub().withRef(TAG_A, "sha-same", Map.of(FILE, "tools at A")), "7.8.9");
        var github = FakeGitHub.fakeGitHub().withRef(TAG_B, "sha-same", Map.of(FILE, "tools at B"));

        var outcome = install(dir, github, "7.8.10");

        assertThat(outcome.fold(Cause::message, AiToolsOutcome::ref)).isEqualTo(TAG_B);
        assertThat(github.requested("git/trees/sha-same")).as("the other ref's cache is refreshed").isTrue();
        assertThat(Files.readString(dir.resolve("cache/.ref"))).isEqualTo(TAG_B + "@sha-same");
    }

    @Test
    void install_cacheOfTheSameRefAndCommit_isNotDownloadedAgain(@TempDir Path dir) {
        install(dir, FakeGitHub.fakeGitHub().withRef(TAG_A, "sha-a", Map.of(FILE, "tools at A")), "7.8.9");
        var github = FakeGitHub.fakeGitHub().withRef(TAG_A, "sha-a", Map.of(FILE, "tools at A"));

        install(dir, github, "7.8.9");

        assertThat(github.requested("git/trees")).isFalse();
        assertThat(github.requested("raw.githubusercontent.com")).isFalse();
    }

    private static Result<AiToolsOutcome> install(Path dir, FakeGitHub github, String version) {
        return AiToolsInstaller.aiToolsInstaller(dir.resolve("project"), github, dir.resolve("cache"), AiToolsRef.aiToolsRef(version))
                               .install();
    }
}
