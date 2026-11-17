package org.pragmatica.jbct.config;

import org.pragmatica.config.toml.TomlParser;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// `[lint] mode` and `[project] basePackage` (#2525): the four modes, the two refused until the entry points honour
/// them, the default, the `toToml` round trip and per-key merge precedence.
class JbctModeConfigTest {
    @Test
    void partialConfig_acceptsBusinessMode() {
        layer("[lint]\nmode = \"business\"\n").onFailure(JbctModeConfigTest::fail)
             .onSuccess(layer -> assertThat(layer.mode()).isEqualTo(Option.some(JbctMode.BUSINESS)));
    }

    @Test
    void partialConfig_acceptsInfrastructureMode() {
        layer("[lint]\nmode = \"infrastructure\"\n").onFailure(JbctModeConfigTest::fail)
             .onSuccess(layer -> assertThat(layer.mode()).isEqualTo(Option.some(JbctMode.INFRASTRUCTURE)));
    }

    @Test
    void partialConfig_acceptsFormatOnlyMode() {
        layer("[lint]\nmode = \"format-only\"\n").onFailure(JbctModeConfigTest::fail)
             .onSuccess(layer -> assertThat(layer.mode()).isEqualTo(Option.some(JbctMode.FORMAT_ONLY)));
    }

    @Test
    void partialConfig_acceptsDisabledMode() {
        layer("[lint]\nmode = \"disabled\"\n").onFailure(JbctModeConfigTest::fail)
             .onSuccess(layer -> assertThat(layer.mode()).isEqualTo(Option.some(JbctMode.DISABLED)));
    }

    @Test
    void partialConfig_refusesUnknownModeNamingKeyValueAndAllowed() {
        layer("[lint]\nmode = \"strict\"\n").onSuccess(layer -> Assertions.fail("must refuse, captured " + layer))
             .onFailure(cause -> assertThat(cause.message()).contains("lint.mode", "strict", JbctMode.ALLOWED));
    }

    @Test
    void jbctMode_acceptsAllFourAndRefusesAnUnknownToken() {
        assertThat(JbctMode.jbctMode("business").unwrap()).isEqualTo(JbctMode.BUSINESS);
        assertThat(JbctMode.jbctMode("infrastructure").unwrap()).isEqualTo(JbctMode.INFRASTRUCTURE);
        assertThat(JbctMode.jbctMode("format-only").unwrap()).isEqualTo(JbctMode.FORMAT_ONLY);
        assertThat(JbctMode.jbctMode("disabled").unwrap()).isEqualTo(JbctMode.DISABLED);
        JbctMode.jbctMode("nope")
                .onSuccess(mode -> Assertions.fail("accepted " + mode))
                .onFailure(cause -> assertThat(cause).isInstanceOf(ConfigError.ModeUnknown.class));
    }

    @Test
    void fromToml_defaultsToBusinessWithNoBasePackage_whenKeysAbsent() {
        config("[lint]\nfailOnWarning = true\n").onFailure(JbctModeConfigTest::fail)
              .onSuccess(config -> {
                             assertThat(config.mode()).isEqualTo(JbctMode.BUSINESS);
                             assertThat(config.basePackage()).isEqualTo(Option.none());
                         });
    }

    @Test
    void fromToml_readsBasePackage() {
        config("[project]\nbasePackage = \"com.example.orders\"\n").onFailure(JbctModeConfigTest::fail)
              .onSuccess(config -> assertThat(config.basePackage()).isEqualTo(Option.some("com.example.orders")));
    }

    @Test
    void fromToml_refusesBasePackageThatIsNotAPackageName() {
        layer("[project]\nbasePackage = \"com..example\"\n").onSuccess(layer -> Assertions.fail("must refuse, captured " + layer))
             .onFailure(cause -> assertThat(cause.message()).contains("project.basePackage", "com..example"));
    }

    @Test
    void fromToml_refusesBasePackageWithAKeywordSegment() {
        layer("[project]\nbasePackage = \"com.class.orders\"\n").onSuccess(layer -> Assertions.fail("must refuse, captured " + layer))
             .onFailure(cause -> assertThat(cause.message()).contains("project.basePackage", "com.class.orders"));
    }

    @Test
    void fromToml_refusesBasePackageThatIsALiteralOrKeywordOnItsOwn() {
        layer("[project]\nbasePackage = \"true\"\n").onSuccess(layer -> Assertions.fail("must refuse, captured " + layer))
             .onFailure(cause -> assertThat(cause.message()).contains("project.basePackage", "true"));
    }

    @Test
    void fromToml_refusesBasePackageThatIsNotAString() {
        layer("[project]\nbasePackage = true\n").onSuccess(layer -> Assertions.fail("must refuse, captured " + layer))
             .onFailure(cause -> assertThat(cause.message()).contains("project.basePackage", "true"));
    }

    @Test
    void fromToml_refusesBasePackageThatIsANumber() {
        layer("[project]\nbasePackage = 42\n").onSuccess(layer -> Assertions.fail("must refuse, captured " + layer))
             .onFailure(cause -> assertThat(cause.message()).contains("project.basePackage", "42"));
    }

    @Test
    void fromToml_refusesANonStringWhoseTextReadsAsAPackageName() {
        layer("[project]\nbasePackage = nan\n").onSuccess(layer -> Assertions.fail("must refuse, captured " + layer))
             .onFailure(cause -> assertThat(cause.message()).contains("project.basePackage", "NaN"));
        layer("[project]\nbasePackage = inf\n").onSuccess(layer -> Assertions.fail("must refuse, captured " + layer))
             .onFailure(cause -> assertThat(cause.message()).contains("project.basePackage", "Infinity"));
    }

    @Test
    void fromToml_acceptsBasePackageWithDigitsAndUnderscoresInsideSegments() {
        config("[project]\nbasePackage = \"com.example_1.v2\"\n").onFailure(JbctModeConfigTest::fail)
              .onSuccess(config -> assertThat(config.basePackage()).isEqualTo(Option.some("com.example_1.v2")));
    }

    @Test
    void toToml_roundTripsBothKeys() {
        config("[lint]\nmode = \"infrastructure\"\n[project]\nbasePackage = \"org.acme.billing\"\n").flatMap(config -> TomlParser.parse(config.toToml()).flatMap(JbctConfig::fromToml))
              .onFailure(JbctModeConfigTest::fail)
              .onSuccess(config -> {
                             assertThat(config.mode()).isEqualTo(JbctMode.INFRASTRUCTURE);
                             assertThat(config.basePackage()).isEqualTo(Option.some("org.acme.billing"));
                         });
    }

    @Test
    void toToml_roundTripsDefaults_withoutBasePackageKey() {
        var toml = JbctConfig.DEFAULT.toToml();

        assertThat(toml).contains("mode = \"business\"").doesNotContain("basePackage");
        TomlParser.parse(toml)
                  .flatMap(JbctConfig::fromToml)
                  .onFailure(JbctModeConfigTest::fail)
                  .onSuccess(config -> assertThat(config.basePackage()).isEqualTo(Option.none()));
    }

    @Test
    void mergeWith_nearerModeAndBasePackageWin() {
        merged("[lint]\nmode = \"business\"\n[project]\nbasePackage = \"far.pkg\"\n",
               "[lint]\nmode = \"infrastructure\"\n[project]\nbasePackage = \"near.pkg\"\n").onFailure(JbctModeConfigTest::fail)
              .onSuccess(merged -> {
                             assertThat(merged.mode()).isEqualTo(Option.some(JbctMode.INFRASTRUCTURE));
                             assertThat(merged.basePackage()).isEqualTo(Option.some("near.pkg"));
                         });
    }

    @Test
    void mergeWith_keepsFartherModeAndBasePackage_whenNearerLayerIsSilent() {
        merged("[lint]\nmode = \"infrastructure\"\n[project]\nbasePackage = \"far.pkg\"\n",
               "[lint]\nfailOnWarning = true\n").onFailure(JbctModeConfigTest::fail)
              .onSuccess(merged -> {
                             assertThat(merged.mode()).isEqualTo(Option.some(JbctMode.INFRASTRUCTURE));
                             assertThat(merged.basePackage()).isEqualTo(Option.some("far.pkg"));
                         });
    }

    @Test
    void mergeWith_nearerExplicitDefaultModeOverridesFartherNonDefault() {
        merged("[lint]\nmode = \"infrastructure\"\n", "[lint]\nmode = \"business\"\n").onFailure(JbctModeConfigTest::fail)
              .onSuccess(merged -> assertThat(merged.materialize().mode()).isEqualTo(JbctMode.BUSINESS));
    }

    @Test
    void overrideMode_replacesTheConfiguredMode_inBothDirections() {
        config("[lint]\nmode = \"business\"\n").flatMap(config -> config.overrideMode(Option.some("infrastructure")))
              .onFailure(JbctModeConfigTest::fail)
              .onSuccess(config -> assertThat(config.mode()).isEqualTo(JbctMode.INFRASTRUCTURE));
        config("[lint]\nmode = \"infrastructure\"\n").flatMap(config -> config.overrideMode(Option.some("business")))
              .onFailure(JbctModeConfigTest::fail)
              .onSuccess(config -> assertThat(config.mode()).isEqualTo(JbctMode.BUSINESS));
    }

    @Test
    void overrideMode_leavesTheConfiguredMode_whenNoOverrideIsGiven() {
        config("[lint]\nmode = \"infrastructure\"\n").flatMap(config -> config.overrideMode(Option.none()))
              .onFailure(JbctModeConfigTest::fail)
              .onSuccess(config -> assertThat(config.mode()).isEqualTo(JbctMode.INFRASTRUCTURE));
    }

    @Test
    void overrideMode_changesOnlyTheMode() {
        config("[lint]\nmode = \"business\"\nfailOnWarning = true\n[project]\nbasePackage = \"org.example\"\n")
        .flatMap(config -> config.overrideMode(Option.some("infrastructure")))
        .onFailure(JbctModeConfigTest::fail)
        .onSuccess(config -> assertOnlyModeChanged(config));
    }

    @Test
    void overrideMode_refusesUnknownValueNamingItAndTheAllowedOnes() {
        JbctConfig.DEFAULT.overrideMode(Option.some("strict"))
                          .onSuccess(config -> Assertions.fail("accepted an unknown mode"))
                          .onFailure(cause -> assertThat(cause).isInstanceOf(ConfigError.ModeUnknown.class))
                          .onFailure(cause -> assertThat(cause.message()).contains("strict", JbctMode.ALLOWED));
    }

    @Test
    void overrideMode_acceptsFormatOnly() {
        JbctConfig.DEFAULT.overrideMode(Option.some("format-only")).onFailure(JbctModeConfigTest::fail)
                          .onSuccess(config -> assertThat(config.mode()).isEqualTo(JbctMode.FORMAT_ONLY));
    }

    @Test
    void overrideMode_acceptsDisabled() {
        JbctConfig.DEFAULT.overrideMode(Option.some("disabled")).onFailure(JbctModeConfigTest::fail)
                          .onSuccess(config -> assertThat(config.mode()).isEqualTo(JbctMode.DISABLED));
    }

    private static void assertOnlyModeChanged(JbctConfig config) {
        assertThat(config.mode()).isEqualTo(JbctMode.INFRASTRUCTURE);
        assertThat(config.lint().failOnWarning()).isTrue();
        assertThat(config.basePackage()).isEqualTo(Option.some("org.example"));
    }

    private static Result<PartialConfig> merged(String farther, String nearer) {
        return Result.all(layer(farther), layer(nearer)).map(PartialConfig::mergeWith);
    }

    private static Result<PartialConfig> layer(String toml) {
        return TomlParser.parse(toml).flatMap(PartialConfig::partialConfig);
    }

    private static Result<JbctConfig> config(String toml) {
        return TomlParser.parse(toml).flatMap(JbctConfig::fromToml);
    }

    private static void fail(Cause cause) {
        Assertions.fail(cause.message());
    }
}
