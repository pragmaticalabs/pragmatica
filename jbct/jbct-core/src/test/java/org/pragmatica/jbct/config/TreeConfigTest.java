package org.pragmatica.jbct.config;

import java.util.List;

import org.pragmatica.config.toml.TomlParser;
import org.pragmatica.jbct.lint.tree.TreeConfig;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// `[lint.tree] exclude` (#2526 P3): parsed per layer, merged per key, materialised once, written back by `toToml`.
class TreeConfigTest {
    private static Result<PartialConfig> layer(String toml) {
        return TomlParser.parse(toml)
                         .flatMap(PartialConfig::partialConfig);
    }

    private static Result<PartialConfig> merged(String farther, String nearer) {
        return Result.all(layer(farther), layer(nearer))
                     .map(PartialConfig::mergeWith);
    }

    private static void fail(Cause cause) {
        Assertions.fail(cause.message());
    }

    @Test
    void partialConfig_capturesTheExcludeGlobs() {
        layer("[lint.tree]\nexclude = [\"com.shop.generated.**\", \"com.shop.*.gen\"]\n").onFailure(TreeConfigTest::fail)
             .onSuccess(layer -> assertThat(layer.tree()
                                                 .excludeGlobs()).isEqualTo(Option.some(List.of("com.shop.generated.**",
                                                                                                "com.shop.*.gen"))));
    }

    @Test
    void partialConfig_leavesTheKeyAbsent_whenTheSectionIsMissing() {
        layer("[lint]\nfailOnWarning = true\n").onFailure(TreeConfigTest::fail)
             .onSuccess(layer -> assertThat(layer.tree()
                                                 .excludeGlobs()).isEqualTo(Option.none()));
    }

    @Test
    void partialConfig_refusesAnExcludeThatIsNotAStringList() {
        layer("[lint.tree]\nexclude = \"com.shop.generated.**\"\n").onSuccess(layer -> Assertions.fail("a string is not a list, loaded " + layer))
             .onFailure(cause -> assertThat(cause.message()).contains("exclude")
                                                           .contains("com.shop.generated.**"));
    }

    @Test
    void materialize_defaultsToNoExcludes() {
        layer("").onFailure(TreeConfigTest::fail)
                 .onSuccess(layer -> assertThat(layer.materialize()
                                                     .tree()).isEqualTo(TreeConfig.DEFAULT));
    }

    @Test
    void materialize_carriesTheExcludes() {
        layer("[lint.tree]\nexclude = [\"a.b.**\"]\n").onFailure(TreeConfigTest::fail)
             .onSuccess(layer -> assertThat(layer.materialize()
                                                 .tree()
                                                 .excludeGlobs()).containsExactly("a.b.**"));
    }

    @Test
    void mergeWith_nearerExcludeReplacesFarther() {
        merged("[lint.tree]\nexclude = [\"far.**\"]\n", "[lint.tree]\nexclude = [\"near.**\"]\n").onFailure(TreeConfigTest::fail)
              .onSuccess(merged -> assertThat(merged.tree()
                                                    .excludeGlobs()).isEqualTo(Option.some(List.of("near.**"))));
    }

    @Test
    void mergeWith_keepsFartherExclude_whenNearerSaysNothing() {
        merged("[lint.tree]\nexclude = [\"far.**\"]\n", "[lint]\nfailOnWarning = true\n").onFailure(TreeConfigTest::fail)
              .onSuccess(merged -> assertThat(merged.tree()
                                                    .excludeGlobs()).isEqualTo(Option.some(List.of("far.**"))));
    }

    @Test
    void mergeWith_nearerEmptyListClearsFartherExclude() {
        merged("[lint.tree]\nexclude = [\"far.**\"]\n", "[lint.tree]\nexclude = []\n").onFailure(TreeConfigTest::fail)
              .onSuccess(merged -> assertThat(merged.tree()
                                                    .excludeGlobs()).isEqualTo(Option.some(List.of())));
    }

    @Test
    void toToml_roundTripsTheExcludes() {
        TomlParser.parse("[lint.tree]\nexclude = [\"a.b.**\", \"c.*\"]\n")
                  .flatMap(JbctConfig::fromToml)
                  .flatMap(config -> TomlParser.parse(config.toToml())
                                               .flatMap(JbctConfig::fromToml))
                  .onFailure(TreeConfigTest::fail)
                  .onSuccess(config -> assertThat(config.tree()
                                                        .excludeGlobs()).containsExactly("a.b.**", "c.*"));
    }

    @Test
    void toToml_omitsTheSection_whenThereAreNoExcludes() {
        assertThat(JbctConfig.DEFAULT.toToml()).doesNotContain("lint.tree");
    }

    @Test
    void toToml_keepsTheTreeSectionBesideTheLayersSection() {
        TomlParser.parse("[lint.layers]\ndomain = [\"a.core.**\"]\n[lint.tree]\nexclude = [\"a.gen.**\"]\n")
                  .flatMap(JbctConfig::fromToml)
                  .flatMap(config -> TomlParser.parse(config.toToml())
                                               .flatMap(JbctConfig::fromToml))
                  .onFailure(TreeConfigTest::fail)
                  .onSuccess(config -> {
                                 assertThat(config.tree()
                                                  .excludeGlobs()).containsExactly("a.gen.**");
                                 assertThat(config.layers()
                                                  .domainGlobs()).containsExactly("a.core.**");
                             });
    }
}
