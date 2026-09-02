package org.pragmatica.jbct.cli;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// Pins the property that makes a stale binary detectable (#620).
///
/// A version string shared by every build of a release branch cannot answer "is this jar the code
/// I just changed?". #620 was filed because it could not: the CLI reporting `1.0.0-rc3` was an
/// installed jar months older than the branch under test, and its findings were read as evidence
/// against a fix that was in fact correct.
///
/// The invariant is therefore not "`--version` mentions a date" but "two builds of the SAME
/// version render differently". That fails silently in one specific way — the stamp is declared in
/// the properties resource but the resource is not filtered, or the format property is missing —
/// which leaves the placeholder or `unknown` in place and reads exactly like a fresh build. Both
/// assertions below target that shape rather than the rendering.
class VersionStampTest {
    @Test
    void buildTime_isResolvedByResourceFiltering() {
        assertThat(Version.buildTime())
                  .as("buildTime comes from a FILTERED resource; an unsubstituted placeholder or "
                     + "'unknown' means the pom's filtering include or timestamp format was lost")
                  .isNotEqualTo("unknown")
                  .doesNotContain("${")
                  .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z");
    }

    /// The two assertions above pin the VALUE. This one pins the SURFACE: `--version` renders
    /// through `VersionProvider`, and a revert of that one call site back to [Version#get()] would
    /// leave both of the others green while shipping exactly the output #620 could not tell apart.
    @Test
    void versionProvider_rendersTheStamp_notTheBareVersion() {
        assertThat(new JbctCommand.VersionProvider().getVersion())
                  .as("the shipped --version surface, not just the API behind it")
                  .containsExactly(Version.full());

        assertThat(new JbctCommand.VersionProvider().getVersion()[0])
                  .contains(Version.buildTime())
                  .isNotEqualTo(Version.get());
    }

    @Test
    void full_carriesBuildStamp_beyondTheVersionAlone() {
        assertThat(Version.full())
                  .as("--version must distinguish two builds of the same version")
                  .startsWith(Version.get())
                  .isNotEqualTo(Version.get())
                  .contains(Version.buildTime());
    }
}
