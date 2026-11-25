package org.pragmatica.jbct.init;

import org.pragmatica.jbct.init.AiToolsRef.AiToolsRefError;
import org.pragmatica.http.HttpClientError;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.jbct.init.ResultViews.causeOf;
import static org.pragmatica.jbct.init.ResultViews.tagOf;

/// #2363: the AI-tools ref is derived from the JBCT build version, never `main`.
class AiToolsRefTest {
    @Test
    void aiToolsRef_isDerivedFromTheVersion_notHardCoded() {
        assertThat(tagOf(AiToolsRef.aiToolsRef("1.0.0-rc5"))).isEqualTo("ai-tools-v1.0.0-rc5");
        assertThat(tagOf(AiToolsRef.aiToolsRef("2.3.4"))).isEqualTo("ai-tools-v2.3.4");
    }

    @Test
    void aiToolsRef_ofTheRunningBuild_usesTheVersionStampedIntoTheJar() {
        var stamped = GitHubVersionResolver.defaultJbctVersion();

        assertThat(tagOf(AiToolsRef.aiToolsRef()))
            .isEqualTo(tagOf(AiToolsRef.aiToolsRef(stamped)));
    }

    @Test
    void aiToolsRef_snapshotBuild_isRefusedNamingTheTagLookedFor() {
        var cause = causeOf(AiToolsRef.aiToolsRef("1.0.0-rc6-SNAPSHOT"));

        assertThat(cause).isInstanceOf(AiToolsRefError.UnreleasedBuild.class);
        assertThat(cause.message()).contains("ai-tools-v1.0.0-rc6-SNAPSHOT").contains("siy/coding-technology").contains("no fallback to main");
    }

    @Test
    void aiToolsRef_unknownVersion_isRefused() {
        assertThat(causeOf(AiToolsRef.aiToolsRef("unknown"))).isInstanceOf(AiToolsRefError.UnreleasedBuild.class);
        assertThat(causeOf(AiToolsRef.aiToolsRef("${project.version}"))).isInstanceOf(AiToolsRefError.UnreleasedBuild.class);
    }

    @Test
    void resolveSha_missingTag_isReportedByName_forBothStatusesGitHubUses() {
        var ref = new AiToolsRef("ai-tools-v9.9.9");

        for (var status : new int[]{404, 422}) {
            var cause = causeOf(ref.resolveSha(FakeGitHub.fakeGitHub().withRefStatus("ai-tools-v9.9.9", status)));

            assertThat(cause).as("status " + status).isInstanceOf(AiToolsRefError.TagNotFound.class);
            assertThat(cause.message()).contains("ai-tools-v9.9.9").contains("no fallback to main");
        }
    }

    @Test
    void resolveSha_otherFailure_isNotReportedAsAMissingTag() {
        var cause = causeOf(new AiToolsRef("ai-tools-v9.9.9").resolveSha(FakeGitHub.fakeGitHub().withRefStatus("ai-tools-v9.9.9", 500)));

        assertThat(cause).isInstanceOf(HttpClientError.RequestFailed.class);
    }

    @Test
    void stamp_recordsTheRefAndTheCommit_andIsRecognisedOnlyByItsOwnRef() {
        var ref = new AiToolsRef("ai-tools-v1.0.0");

        assertThat(ref.stamp("abc123")).isEqualTo("ai-tools-v1.0.0@abc123");
        assertThat(ref.isStampOfThisRef("ai-tools-v1.0.0@zzz")).isTrue();
        assertThat(ref.isStampOfThisRef("ai-tools-v1.0.01@zzz")).as("a longer tag is another ref").isFalse();
        assertThat(ref.isStampOfThisRef("abc123")).as("a legacy bare-sha record is no ref").isFalse();
    }
}
