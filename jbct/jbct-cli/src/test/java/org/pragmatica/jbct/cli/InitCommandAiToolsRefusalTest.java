package org.pragmatica.jbct.cli;

import org.pragmatica.jbct.init.AiToolsRef.AiToolsRefError.TagNotFound;
import org.pragmatica.jbct.init.AiToolsRef.AiToolsRefError.UnreleasedBuild;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #2363: `jbct init` refuses (non-zero exit, error text) when the AI-tools ref is unavailable, and keeps the old
/// warning for every other failure (offline, no cache). The decision is these two functions; `call()` returns 1 on it.
class InitCommandAiToolsRefusalTest {
    @Test
    void isRefusal_isTrueForAMissingTagAndForAnUnreleasedBuild() {
        assertThat(InitCommand.isRefusal(TagNotFound.tagNotFound("ai-tools-v9.9.9"))).isTrue();
        assertThat(InitCommand.isRefusal(UnreleasedBuild.unreleasedBuild("9.9.9-SNAPSHOT"))).isTrue();
    }

    @Test
    void isRefusal_isFalseForAnyOtherFailure() {
        assertThat(InitCommand.isRefusal(Causes.cause("network down"))).isFalse();
    }

    @Test
    void aiToolsFailureText_namesTheRefusedTagAsAnError_andOtherFailuresAsAWarning() {
        assertThat(InitCommand.aiToolsFailureText(TagNotFound.tagNotFound("ai-tools-v9.9.9"))).startsWith("Error: ")
                                                                                              .contains("ai-tools-v9.9.9");
        assertThat(InitCommand.aiToolsFailureText(Causes.cause("network down"))).startsWith("Warning: ");
    }
}
