package org.pragmatica.jbct.init;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;

import org.pragmatica.http.HttpClientError;
import org.pragmatica.jbct.init.AiToolsRef.AiToolsRefError;
import org.pragmatica.lang.Result;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.jbct.init.ResultViews.causeOf;
import static org.pragmatica.jbct.init.ResultViews.refOf;

/// #2363: `jbct init` installs the AI tools at the tag of the running build, records the ref, and never falls back
/// to `main`. The network is the injected [FakeGitHub]; the cache is a temp directory.
class AiToolsInstallerPinTest {
    // A file name no real ~/.claude carries, so the global-skip rule never hides it.
    private static final String FILE = "skills/zz-pin-2363/SKILL.md";
    // A cache counts as valid only with a skill and an agent in it (AiToolsInstaller.isCacheValid).
    private static final String AGENT = "agents/zz-pin-2363.md";
    // Present in one ref only, to tell a refreshed cache from one that kept what the new ref does not carry.
    private static final String ONLY_SKILL = "skills/zz-pin-2363-only/SKILL.md";
    private static final String ONLY_AGENT = "agents/zz-pin-2363-only.md";
    private static final String TAG_A = "ai-tools-v7.8.9";
    private static final String TAG_B = "ai-tools-v7.8.10";

    @Test
    void install_fetchesAtTheTagDerivedFromTheVersion_andRecordsIt(@TempDir Path dir) throws Exception {
        var github = FakeGitHub.fakeGitHub().withRef(TAG_A, "sha-a", Map.of(FILE, "tools at A", AGENT, "agent"));

        var outcome = install(dir, github, "7.8.9");

        assertThat(refOf(outcome)).as("outcome ref").isEqualTo(TAG_A);
        assertThat(github.requests()).contains("https://api.github.com/repos/siy/coding-technology/commits/" + TAG_A);
        assertThat(github.requested("/main")).as("never main").isFalse();
        assertThat(Files.readString(dir.resolve("project/.claude").resolve(FILE))).isEqualTo("tools at A");
        assertThat(Files.readString(dir.resolve("cache/.ref"))).as("recorded ref").isEqualTo(TAG_A + "@sha-a");
    }

    @Test
    void install_missingTag_failsByName_withTypedCause_andInstallsNothing(@TempDir Path dir) {
        var github = FakeGitHub.fakeGitHub().withRef("main", "sha-main", Map.of(FILE, "tip of main", AGENT, "agent"));

        var cause = causeOf(install(dir, github, "7.8.9"));

        assertThat(cause).isInstanceOf(AiToolsRefError.TagNotFound.class);
        assertThat(cause.message()).contains(TAG_A);
        assertThat(dir.resolve("project/.claude").resolve(FILE)).as("no fallback to main").doesNotExist();
    }

    @Test
    void install_missingTag_isNotPapereredOverByAnExistingCache(@TempDir Path dir) {
        install(dir, FakeGitHub.fakeGitHub().withRef(TAG_A, "sha-a", Map.of(FILE, "tools at A", AGENT, "agent")), "7.8.9");

        var cause = causeOf(install(dir, FakeGitHub.fakeGitHub(), "7.8.9"));

        assertThat(cause).isInstanceOf(AiToolsRefError.TagNotFound.class);
    }

    @Test
    void install_snapshotBuild_isRefusedBeforeAnyRequest(@TempDir Path dir) {
        var github = FakeGitHub.fakeGitHub();

        var cause = causeOf(install(dir, github, "7.8.9-SNAPSHOT"));

        assertThat(cause).isInstanceOf(AiToolsRefError.UnreleasedBuild.class);
        assertThat(cause.message()).contains("ai-tools-v7.8.9-SNAPSHOT");
        assertThat(github.requests()).isEmpty();
    }

    @Test
    void install_offline_reusesTheCacheOfThisRef_butNeverOneOfAnotherRef(@TempDir Path dir) {
        install(dir, FakeGitHub.fakeGitHub().withRef(TAG_A, "sha-a", Map.of(FILE, "tools at A", AGENT, "agent")), "7.8.9");

        var sameRef = install(dir, FakeGitHub.offlineGitHub(), "7.8.9");
        var otherRef = install(dir, FakeGitHub.offlineGitHub(), "7.8.10");

        assertThat(refOf(sameRef)).isEqualTo(TAG_A);
        assertThat(causeOf(otherRef)).isInstanceOf(HttpClientError.ConnectionFailed.class);
    }

    @Test
    void install_newVersion_movesTheCacheToItsTag_evenWhenTheCommitIsTheSame(@TempDir Path dir) throws Exception {
        install(dir, FakeGitHub.fakeGitHub().withRef(TAG_A, "sha-same", Map.of(FILE, "tools at A", AGENT, "agent")), "7.8.9");
        var github = FakeGitHub.fakeGitHub().withRef(TAG_B, "sha-same", Map.of(FILE, "tools at B", AGENT, "agent"));

        var outcome = install(dir, github, "7.8.10");

        assertThat(refOf(outcome)).isEqualTo(TAG_B);
        assertThat(github.requested("git/trees/sha-same")).as("the other ref's cache is refreshed").isTrue();
        assertThat(Files.readString(dir.resolve("cache/.ref"))).isEqualTo(TAG_B + "@sha-same");
    }

    @Test
    void install_cacheOfTheSameRefAndCommit_isNotDownloadedAgain(@TempDir Path dir) {
        install(dir, FakeGitHub.fakeGitHub().withRef(TAG_A, "sha-a", Map.of(FILE, "tools at A", AGENT, "agent")), "7.8.9");
        var github = FakeGitHub.fakeGitHub().withRef(TAG_A, "sha-a", Map.of(FILE, "tools at A", AGENT, "agent"));

        install(dir, github, "7.8.9");

        assertThat(github.requested("git/trees")).isFalse();
        assertThat(github.requested("raw.githubusercontent.com")).isFalse();
    }

    @Test
    void install_refresh_removesWhatTheNewRefDoesNotCarry_fromCacheAndFromTheProject(@TempDir Path dir) {
        install(dir, "first", FakeGitHub.fakeGitHub().withRef(TAG_A, "sha-a", withOnly("tools at A")), "7.8.9");
        var github = FakeGitHub.fakeGitHub().withRef(TAG_B, "sha-b", Map.of(FILE, "tools at B", AGENT, "agent"));

        install(dir, "second", github, "7.8.10");

        assertThat(dir.resolve("cache").resolve(ONLY_SKILL)).as("stale skill in the cache").doesNotExist();
        assertThat(dir.resolve("cache").resolve(ONLY_AGENT)).as("stale agent in the cache").doesNotExist();
        assertThat(dir.resolve("second/.claude").resolve(ONLY_SKILL)).as("stale skill in the project").doesNotExist();
        assertThat(dir.resolve("second/.claude").resolve(ONLY_AGENT)).as("stale agent in the project").doesNotExist();
    }

    @Test
    void install_legacyCacheWithoutRef_isCleared_beforeItIsFilled(@TempDir Path dir) throws Exception {
        var cache = dir.resolve("cache");

        Files.createDirectories(cache.resolve("skills/zz-pin-2363-only"));
        Files.createDirectories(cache.resolve("agents"));
        Files.writeString(cache.resolve(".sha"), "legacy-main-sha");
        Files.writeString(cache.resolve(ONLY_SKILL), "from main");
        Files.writeString(cache.resolve(ONLY_AGENT), "from main");

        install(dir, "project", FakeGitHub.fakeGitHub().withRef(TAG_A, "sha-a", Map.of(FILE, "tools at A", AGENT, "agent")), "7.8.9");

        assertThat(cache.resolve(ONLY_SKILL)).as("legacy skill in the cache").doesNotExist();
        assertThat(cache.resolve(ONLY_AGENT)).as("legacy agent in the cache").doesNotExist();
        assertThat(dir.resolve("project/.claude").resolve(ONLY_SKILL)).as("legacy skill in the project").doesNotExist();
        assertThat(dir.resolve("project/.claude").resolve(FILE)).exists();
    }

    @Test
    void install_refresh_removesALinkWithoutFollowingIt(@TempDir Path dir) throws Exception {
        install(dir, "first", FakeGitHub.fakeGitHub().withRef(TAG_A, "sha-a", withOnly("tools at A")), "7.8.9");
        var outside = Files.createDirectories(dir.resolve("outside"));
        var kept = Files.writeString(outside.resolve("keep.txt"), "not the cache's");
        var cacheSkills = dir.resolve("cache/skills");

        deleteTree(cacheSkills);
        Files.createSymbolicLink(cacheSkills, outside);
        install(dir, "second", FakeGitHub.fakeGitHub().withRef(TAG_B, "sha-b", Map.of(FILE, "tools at B", AGENT, "agent")), "7.8.10");

        assertThat(kept).as("target of a link in the cache").hasContent("not the cache's");
        assertThat(Files.isSymbolicLink(cacheSkills)).as("the link itself").isFalse();
        assertThat(dir.resolve("cache").resolve(FILE)).hasContent("tools at B");
    }

    @Test
    void install_failedRefresh_leavesNoStamp_andTheMixedCacheIsNotReusedOffline(@TempDir Path dir) throws Exception {
        install(dir, "first", FakeGitHub.fakeGitHub().withRef(TAG_A, "sha-a", withOnly("tools at A")), "7.8.9");
        var failing = FakeGitHub.fakeGitHub()
                                .withRef(TAG_B, "sha-b", Map.of(FILE, "tools at B", AGENT, "agent", ONLY_SKILL, "only at B"))
                                .withFailure("sha-b/ai-tools/" + ONLY_SKILL);

        var failed = install(dir, "second", failing, "7.8.10");
        var offline = install(dir, "third", FakeGitHub.offlineGitHub(), "7.8.9");

        assertThat(causeOf(failed)).as("the refresh fails").isNotNull();
        assertThat(dir.resolve("cache").resolve(FILE)).as("the cache is mixed now").hasContent("tools at B");
        assertThat(dir.resolve("cache/.ref")).as("no stamp over mixed content").doesNotExist();
        assertThat(causeOf(offline)).as("mixed cache is not A's").isInstanceOf(HttpClientError.ConnectionFailed.class);
    }

    @Test
    void install_failedRefresh_thenTheOldRefAgain_installsExactlyTheOldRef(@TempDir Path dir) throws Exception {
        install(dir, "first", FakeGitHub.fakeGitHub().withRef(TAG_A, "sha-a", Map.of(FILE, "tools at A", AGENT, "agent")), "7.8.9");
        install(dir, "second",
                FakeGitHub.fakeGitHub()
                          .withRef(TAG_B, "sha-b", Map.of(FILE, "tools at B", AGENT, "agent", ONLY_SKILL, "only at B"))
                          .withFailure("sha-b/ai-tools/" + ONLY_SKILL),
                "7.8.10");

        var again = install(dir, "third", FakeGitHub.fakeGitHub().withRef(TAG_A, "sha-a", Map.of(FILE, "tools at A", AGENT, "agent")), "7.8.9");

        assertThat(refOf(again)).isEqualTo(TAG_A);
        assertThat(dir.resolve("cache").resolve(FILE)).hasContent("tools at A");
        assertThat(dir.resolve("cache").resolve(ONLY_SKILL)).as("B's file in A's cache").doesNotExist();
        assertThat(dir.resolve("third/.claude").resolve(FILE)).hasContent("tools at A");
        assertThat(dir.resolve("cache/.ref")).hasContent(TAG_A + "@sha-a");
    }

    @Test
    void install_refToRefreshCannotBeListed_leavesTheCacheAsItWas(@TempDir Path dir) {
        install(dir, "first", FakeGitHub.fakeGitHub().withRef(TAG_A, "sha-a", withOnly("tools at A")), "7.8.9");
        var unlistable = FakeGitHub.fakeGitHub()
                                   .withRef(TAG_B, "sha-b", Map.of(FILE, "tools at B", AGENT, "agent"))
                                   .withFailure("git/trees/sha-b");

        var failed = install(dir, "second", unlistable, "7.8.10");
        var offline = install(dir, "third", FakeGitHub.offlineGitHub(), "7.8.9");

        assertThat(causeOf(failed)).isNotNull();
        assertThat(dir.resolve("cache/.ref")).hasContent(TAG_A + "@sha-a");
        assertThat(dir.resolve("cache").resolve(ONLY_SKILL)).exists();
        assertThat(refOf(offline)).as("the untouched cache is still A's").isEqualTo(TAG_A);
    }

    // A tree whose second skill and second agent exist at this ref only.
    private static Map<String, String> withOnly(String content) {
        var files = new TreeMap<String, String>();

        files.put(FILE, content);
        files.put(AGENT, "agent");
        files.put(ONLY_SKILL, "only here");
        files.put(ONLY_AGENT, "only here");
        return files;
    }

    private static void deleteTree(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private static Result<AiToolsOutcome> install(Path dir, String project, FakeGitHub github, String version) {
        return AiToolsInstaller.aiToolsInstaller(dir.resolve(project), github, dir.resolve("cache"), AiToolsRef.aiToolsRef(version))
                               .install();
    }

    private static Result<AiToolsOutcome> install(Path dir, FakeGitHub github, String version) {
        return AiToolsInstaller.aiToolsInstaller(dir.resolve("project"), github, dir.resolve("cache"), AiToolsRef.aiToolsRef(version))
                               .install();
    }
}
