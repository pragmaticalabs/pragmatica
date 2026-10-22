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

    /// #1455: a key present with the wrong TOML type was captured as "key not set", so a lower layer or
    /// the built-in default silently won. Every typed read of the layer is covered: integer, boolean,
    /// long, string list.
    @Test
    void partialConfig_refusesNamingEveryKeyAndValue_whenKeysHaveWrongTypes() {
        layer("""
              [format]
              maxLineLength = "wide"
              useTabs = "maybe"

              [files]
              maxFileSize = "big"
              excludes = "generated"

              [lint]
              failOnWarning = "yes"
              """).onSuccess(layer -> org.junit.jupiter.api.Assertions.fail("must refuse, captured " + layer))
                  .onFailure(cause -> assertThat(cause.message()).contains("format.maxLineLength", "wide")
                                                                 .contains("format.useTabs", "maybe")
                                                                 .contains("files.maxFileSize", "big")
                                                                 .contains("files.excludes", "generated")
                                                                 .contains("lint.failOnWarning", "yes"));
    }

    @Test
    void fromToml_refusesAWrongTypedKey_andMaterialisesAWellTypedDocument() {
        TomlParser.parse("[format]\nindentSize = \"four\"\n")
                  .flatMap(JbctConfig::fromToml)
                  .onSuccess(config -> org.junit.jupiter.api.Assertions.fail("must refuse, loaded " + config))
                  .onFailure(cause -> assertThat(cause.message()).contains("format.indentSize", "four"));
        assertThat(TomlParser.parse("[format]\nindentSize = 2\n").flatMap(JbctConfig::fromToml).unwrap().formatter().indentSize()).isEqualTo(2);
    }

    private static Result<PartialConfig> layer(String toml) {
        return TomlParser.parse(toml)
                         .flatMap(PartialConfig::partialConfig);
    }

    /// Invalid VALUES are refused like invalid types (#1455): an unknown severity token left the layer
    /// silent about that rule, an unknown schema mode fell back to REQUIRED.
    @Test
    void partialConfig_refusesUnknownRuleSeverity_namingKeyValueAndAllowed() {
        layer("""
              [lint.rules]
              JBCT-STY-01 = "eror"
              JBCT-STY-02 = "warn"
              """).onSuccess(layer -> org.junit.jupiter.api.Assertions.fail("must refuse, captured " + layer))
                  .onFailure(cause -> assertThat(cause.message()).contains("lint.rules.JBCT-STY-01", "eror")
                                                                 .contains("one of: error, warning (warn), info, off (disabled)")
                                                                 .doesNotContain("JBCT-STY-02"));
    }

    @Test
    void partialConfig_refusesUnknownSchemaMode_namingKeyValueAndAllowed() {
        layer("[blueprint]\nschema = \"requird\"\n")
            .onSuccess(layer -> org.junit.jupiter.api.Assertions.fail("must refuse, captured " + layer))
            .onFailure(cause -> assertThat(cause.message()).contains("blueprint.schema", "requird")
                                                           .contains("one of: required, external, optional"));
        layer("[blueprint]\nschema = \"External\"\n")
            .onFailure(PartialConfigTest::fail)
            .onSuccess(layer -> assertThat(layer.blueprint().schema()).isEqualTo(Option.some(BlueprintConfig.SchemaMode.EXTERNAL)));
    }

    @Test
    void partialConfig_acceptsEveryDocumentedSeverityToken() {
        layer("""
              [lint.rules]
              A = "error"
              B = "WARN"
              C = "warning"
              D = "info"
              E = "off"
              F = "Disabled"
              """).onFailure(PartialConfigTest::fail)
                  .onSuccess(layer -> assertThat(layer.lint().rules()).hasSize(6));
    }
}
