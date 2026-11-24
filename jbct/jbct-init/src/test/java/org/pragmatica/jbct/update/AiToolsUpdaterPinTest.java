package org.pragmatica.jbct.update;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.pragmatica.jbct.init.AiToolsOutcome;
import org.pragmatica.jbct.init.AiToolsRef;
import org.pragmatica.jbct.init.AiToolsRef.AiToolsRefError;
import org.pragmatica.jbct.init.FakeGitHub;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.jbct.init.ResultViews.causeOf;
import static org.pragmatica.jbct.init.ResultViews.checkText;
import static org.pragmatica.jbct.init.ResultViews.installedCount;
import static org.pragmatica.jbct.init.ResultViews.isEmptyOutcome;
import static org.pragmatica.jbct.init.ResultViews.refOf;

/// #2363: `jbct update` moves the AI tools to the tag of the running build, records the ref, and never falls back
/// to `main`. The network is the injected [FakeGitHub].
class AiToolsUpdaterPinTest {
    // A file name no real ~/.claude carries, so the global-skip rule never hides it.
    private static final String FILE = "skills/zz-pin-2363/SKILL.md";
    private static final String TAG_A = "ai-tools-v7.8.9";
    private static final String TAG_B = "ai-tools-v7.8.10";

    @Test
    void update_fetchesAtTheTagDerivedFromTheVersion_andRecordsIt(@TempDir Path dir) throws Exception {
        var github = FakeGitHub.fakeGitHub().withRef(TAG_A, "sha-a", Map.of(FILE, "tools at A"));

        var outcome = update(dir, github, "7.8.9", false);

        assertThat(refOf(outcome)).isEqualTo(TAG_A);
        assertThat(github.requests()).contains("https://api.github.com/repos/siy/coding-technology/commits/" + TAG_A);
        assertThat(github.requested("/main")).as("never main").isFalse();
        assertThat(Files.readString(dir.resolve(".claude").resolve(FILE))).isEqualTo("tools at A");
        assertThat(Files.readString(dir.resolve(".claude/.ai-tools-version"))).as("recorded ref").isEqualTo(TAG_A + "@sha-a");
    }

    @Test
    void update_missingTag_failsByName_withTypedCause_andWritesNothing(@TempDir Path dir) {
        var github = FakeGitHub.fakeGitHub().withRef("main", "sha-main", Map.of(FILE, "tip of main"));

        var cause = causeOf(update(dir, github, "7.8.9", false));

        assertThat(cause).isInstanceOf(AiToolsRefError.TagNotFound.class);
        assertThat(cause.message()).contains(TAG_A);
        assertThat(dir.resolve(".claude")).as("no fallback to main").doesNotExist();
    }

    @Test
    void update_snapshotBuild_isRefusedBeforeAnyRequest(@TempDir Path dir) {
        var github = FakeGitHub.fakeGitHub();

        var cause = causeOf(update(dir, github, "7.8.9-SNAPSHOT", false));

        assertThat(cause).isInstanceOf(AiToolsRefError.UnreleasedBuild.class);
        assertThat(github.requests()).isEmpty();
    }

    @Test
    void update_toANewVersion_movesToItsTag_evenWhenTheCommitIsTheSame(@TempDir Path dir) throws Exception {
        update(dir, FakeGitHub.fakeGitHub().withRef(TAG_A, "sha-same", Map.of(FILE, "tools at A")), "7.8.9", false);
        var github = FakeGitHub.fakeGitHub().withRef(TAG_B, "sha-same", Map.of(FILE, "tools at B"));

        var outcome = update(dir, github, "7.8.10", false);

        assertThat(refOf(outcome)).isEqualTo(TAG_B);
        assertThat(installedCount(outcome)).isEqualTo(1);
        assertThat(Files.readString(dir.resolve(".claude").resolve(FILE))).isEqualTo("tools at B");
        assertThat(Files.readString(dir.resolve(".claude/.ai-tools-version"))).isEqualTo(TAG_B + "@sha-same");
    }

    @Test
    void update_sameRefAndCommit_isUpToDate_andForceReinstalls(@TempDir Path dir) {
        var tools = Map.of(FILE, "tools at A");

        update(dir, FakeGitHub.fakeGitHub().withRef(TAG_A, "sha-a", tools), "7.8.9", false);
        var github = FakeGitHub.fakeGitHub().withRef(TAG_A, "sha-a", tools);
        var upToDate = update(dir, github, "7.8.9", false);
        var forced = update(dir, github, "7.8.9", true);

        assertThat(isEmptyOutcome(upToDate)).isTrue();
        assertThat(refOf(upToDate)).isEqualTo(TAG_A);
        assertThat(installedCount(forced)).isEqualTo(1);
    }

    @Test
    void checkForUpdate_reportsTheRefRecordWhenBehind_andNothingWhenCurrent(@TempDir Path dir) {
        var github = FakeGitHub.fakeGitHub().withRef(TAG_A, "sha-a", Map.of(FILE, "tools at A"));

        var before = check(dir, github, "7.8.9");
        update(dir, github, "7.8.9", false);
        var after = check(dir, github, "7.8.9");

        assertThat(checkText(before)).isEqualTo(TAG_A + "@sha-a");
        assertThat(checkText(after)).isEqualTo("none");
    }

    @Test
    void checkForUpdate_missingTag_failsByName(@TempDir Path dir) {
        var cause = causeOf(check(dir, FakeGitHub.fakeGitHub(), "7.8.9"));

        assertThat(cause).isInstanceOf(AiToolsRefError.TagNotFound.class);
    }

    private static Result<AiToolsOutcome> update(Path dir, FakeGitHub github, String version, boolean force) {
        return AiToolsUpdater.aiToolsUpdater(dir, github, AiToolsRef.aiToolsRef(version)).update(force);
    }

    private static Result<Option<String>> check(Path dir, FakeGitHub github, String version) {
        return AiToolsUpdater.aiToolsUpdater(dir, github, AiToolsRef.aiToolsRef(version)).checkForUpdate();
    }
}
