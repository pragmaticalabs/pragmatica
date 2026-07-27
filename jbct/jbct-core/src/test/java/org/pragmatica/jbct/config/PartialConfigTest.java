package org.pragmatica.jbct.config;

import java.util.List;

import org.pragmatica.config.toml.TomlParser;
import org.pragmatica.jbct.config.PartialConfig.RuleSetting;
import org.pragmatica.jbct.lint.DiagnosticSeverity;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// The partial representation itself (issue #532): a layer records only the keys it actually
/// writes, so "absent" and "explicitly set to the built-in default" are distinguishable, and
/// folding is per key rather than per section.
class PartialConfigTest {
    @Test
    void partialConfig_leavesKeyAbsent_whenDocumentOmitsIt() {
        layer("""
              [lint]
              failOnWarning = true
              """).onFailure(PartialConfigTest::fail)
                  .onSuccess(PartialConfigTest::assertOnlyFailOnWarningCaptured);
    }

    @Test
    void partialConfig_capturesExplicitDefault_asPresentValue() {
        layer("""
              [format]
              maxLineLength = 120
              """).onFailure(PartialConfigTest::fail)
                  .onSuccess(PartialConfigTest::assertExplicitDefaultCaptured);
    }

    @Test
    void mergeWith_prefersNearerKey_andKeepsFartherSiblings() {
        merged("""
               [format]
               maxLineLength = 100
               indentSize = 2
               """, """
                    [format]
                    indentSize = 8
                    """).onFailure(PartialConfigTest::fail)
                        .onSuccess(PartialConfigTest::assertNearerKeyWinsPerKey);
    }

    @Test
    void mergeWith_prefersNearerExplicitDefault_overFartherValue() {
        merged("""
               [format]
               maxLineLength = 100
               """, """
                    [format]
                    maxLineLength = 120
                    """).onFailure(PartialConfigTest::fail)
                        .onSuccess(PartialConfigTest::assertNearerExplicitDefaultWins);
    }

    @Test
    void mergeWith_keepsFartherLayer_whenNearerIsEmpty() {
        layer("""
              [files]
              excludes = ["**/generated/**"]
              """).map(farther -> farther.mergeWith(PartialConfig.EMPTY))
                  .onFailure(PartialConfigTest::fail)
                  .onSuccess(PartialConfigTest::assertGeneratedExcludeRetained);
    }

    @Test
    void mergeWith_appliesNearerEmptyList_asExplicitClear() {
        merged("""
               [files]
               excludes = ["**/generated/**"]
               """, """
                    [files]
                    excludes = []
                    """).onFailure(PartialConfigTest::fail)
                        .onSuccess(PartialConfigTest::assertExcludesCleared);
    }

    @Test
    void materialize_reproducesBuiltInDefaults_forEmptyLayer() {
        assertThat(PartialConfig.EMPTY.materialize()).isEqualTo(JbctConfig.DEFAULT);
    }

    @Test
    void ruleSetting_returnsNone_forUnrecognisedToken() {
        assertThat(RuleSetting.ruleSetting("bogus")).isEqualTo(Option.none());
    }

    @Test
    void ruleSetting_acceptsAliases_caseInsensitively() {
        assertThat(RuleSetting.ruleSetting("WARN")).isEqualTo(Option.some(RuleSetting.WARNING));
        assertThat(RuleSetting.ruleSetting("Disabled")).isEqualTo(Option.some(RuleSetting.OFF));
    }

    @Test
    void severity_isAbsent_forOffSetting() {
        assertThat(RuleSetting.OFF.severity()).isEqualTo(Option.none());
        assertThat(RuleSetting.ERROR.severity()).isEqualTo(Option.some(DiagnosticSeverity.ERROR));
    }

    private static void assertOnlyFailOnWarningCaptured(PartialConfig layer) {
        assertThat(layer.lint()
                        .failOnWarning()).isEqualTo(Option.some(true));
        assertThat(layer.formatter()
                        .maxLineLength()).isEqualTo(Option.none());
        assertThat(layer.files()
                        .excludes()).isEqualTo(Option.none());
    }

    private static void assertExplicitDefaultCaptured(PartialConfig layer) {
        assertThat(layer.formatter()
                        .maxLineLength()).isEqualTo(Option.some(120));
        assertThat(layer.formatter()
                        .indentSize()).isEqualTo(Option.none());
    }

    private static void assertNearerKeyWinsPerKey(PartialConfig merged) {
        assertThat(merged.formatter()
                         .maxLineLength()).isEqualTo(Option.some(100));
        assertThat(merged.formatter()
                         .indentSize()).isEqualTo(Option.some(8));
    }

    private static void assertNearerExplicitDefaultWins(PartialConfig merged) {
        assertThat(merged.formatter()
                         .maxLineLength()).isEqualTo(Option.some(120));
    }

    private static void assertGeneratedExcludeRetained(PartialConfig merged) {
        assertThat(merged.files()
                         .excludes()).isEqualTo(Option.some(List.of("**/generated/**")));
    }

    private static void assertExcludesCleared(PartialConfig merged) {
        assertThat(merged.files()
                         .excludes()).isEqualTo(Option.some(List.of()));
    }

    private static void fail(Cause cause) {
        Assertions.fail(cause.message());
    }

    private static Result<PartialConfig> merged(String farther, String nearer) {
        return Result.all(layer(farther), layer(nearer))
                     .map(PartialConfig::mergeWith);
    }

    private static Result<PartialConfig> layer(String toml) {
        return TomlParser.parse(toml)
                         .map(PartialConfig::partialConfig);
    }
}
