package org.pragmatica.json;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.pragmatica.lang.Option;
import org.pragmatica.serialization.json.JsonName;
import org.pragmatica.serialization.json.JsonOptions;
import org.pragmatica.serialization.json.JsonOptions.NullInclusion;
import org.pragmatica.serialization.json.NamingStrategy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// `withOptions` and `withNaming` map the generated codecs' configuration onto Jackson, one setting each, and
/// `withValueObjects` honours `@JsonName`. The byte-for-byte agreement with the generated codecs is
/// `OptionsConformanceTest` (json-tests); this pins each mapping on its own.
class JsonMapperOptionsTest {
    static final int MAX = Integer.MAX_VALUE;

    enum Tier {
        BASIC,
        @JsonName("pro-tier")
        PRO
    }

    record Account(Option<String> nickName,
                   @JsonName("legacy_Name") String displayName,
                   Tier tier,
                   Map<String, Option<Integer>> scores,
                   List<Option<String>> aliases) {}

    record Count(long count) {}

    static JsonOptions options(NullInclusion inclusion,
                               boolean unknown,
                               boolean duplicate,
                               int depth,
                               int string,
                               int number,
                               int document) {
        return JsonOptions.jsonOptions(inclusion, unknown, duplicate, depth, string, number, document).unwrap();
    }

    static JsonMapper mapper(JsonOptions options) {
        return JsonMapper.jsonMapper()
                         .withPragmaticaTypes()
                         .withValueObjects()
                         .withOptions(options)
                         .build();
    }

    static Account account() {
        var scores = new LinkedHashMap<String, Option<Integer>>();

        scores.put("a", Option.none());

        return new Account(Option.none(),
                           "Jo",
                           Tier.PRO,
                           scores,
                           List.of(Option.none()));
    }

    @Test
    void withOptions_exclude_leavesOutEmptyOptionProperties_only() {
        var json = mapper(options(NullInclusion.EXCLUDE, false, false, 1000, MAX, MAX, MAX)).writeAsString(account())
                         .unwrap();

        assertThat(json).isEqualTo("{\"legacy_Name\":\"Jo\",\"tier\":\"pro-tier\",\"scores\":{\"a\":null},\"aliases\":[null]}");
    }

    @Test
    void withOptions_include_andNoOptions_writeNull() {
        var expected = "{\"nickName\":null,\"legacy_Name\":\"Jo\",\"tier\":\"pro-tier\",\"scores\":{\"a\":null},\"aliases\":[null]}";

        assertThat(mapper(JsonOptions.DEFAULTS).writeAsString(account()).unwrap()).isEqualTo(expected);
        assertThat(JsonMapper.valueObjectJsonMapper().writeAsString(account()).unwrap()).isEqualTo(expected);
    }

    @Test
    void withValueObjects_readsJsonNamedPropertiesAndConstants() {
        var json = "{\"nickName\":\"j\",\"legacy_Name\":\"Jo\",\"tier\":\"pro-tier\",\"scores\":{},\"aliases\":[]}";

        assertThat(JsonMapper.valueObjectJsonMapper().readString(json, Account.class).unwrap()).isEqualTo(new Account(Option.some("j"),
                                                                                                                      "Jo",
                                                                                                                      Tier.PRO,
                                                                                                                      Map.of(),
                                                                                                                      List.of()));
    }

    @Test
    void withOptions_failOnUnknown_refusesAnUndeclaredMember() {
        var json = "{\"count\":1,\"extra\":2}";

        assertThat(mapper(JsonOptions.DEFAULTS).readString(json, Count.class).isSuccess()).isTrue();
        assertThat(mapper(options(NullInclusion.INCLUDE, true, false, 1000, MAX, MAX, MAX)).readString(json, Count.class)
                         .isFailure()).isTrue();
    }

    @Test
    void withOptions_failOnDuplicate_refusesARepeatedKey() {
        var json = "{\"count\":1,\"count\":2}";

        assertThat(mapper(JsonOptions.DEFAULTS).readString(json, Count.class).unwrap()).isEqualTo(new Count(2));
        assertThat(mapper(options(NullInclusion.INCLUDE, false, true, 1000, MAX, MAX, MAX)).readString(json, Count.class)
                         .isFailure()).isTrue();
    }

    @Test
    void withOptions_limits_becomeStreamReadConstraints() {
        assertThat(mapper(options(NullInclusion.INCLUDE, false, false, 2, MAX, MAX, MAX)).readString("{\"count\":1,\"x\":[[1]]}",
                                                                                                     Count.class)
                         .isFailure()).isTrue();
        assertThat(mapper(options(NullInclusion.INCLUDE, false, false, 1000, MAX, 3, MAX)).readString("{\"count\":1234}",
                                                                                                      Count.class)
                         .isFailure()).isTrue();
        assertThat(mapper(options(NullInclusion.INCLUDE, false, false, 1000, 64, MAX, MAX)).readString("{\""
                                                                                                      + "k".repeat(65)
                                                                                                      + "\":1,\"count\":1}",
                                                                                                       Count.class)
                         .isFailure()).isTrue();
        assertThat(mapper(options(NullInclusion.INCLUDE, false, false, 1000, MAX, MAX, 11)).readString("{\"count\":1} ",
                                                                                                       Count.class)
                         .isFailure()).isTrue();
        assertThat(mapper(options(NullInclusion.INCLUDE, false, false, 1000, MAX, MAX, 11)).readString("{\"count\":1}",
                                                                                                       Count.class)
                         .isSuccess()).isTrue();
    }

    // Jackson's own default depth is 500; the options' default is the generated codecs' 1000.
    @Test
    void withOptions_defaults_raiseJacksonsDepthToTheCodecs() {
        var deep = "{\"count\":1,\"x\":" + "[".repeat(700) + "]".repeat(700) + "}";

        assertThat(mapper(JsonOptions.DEFAULTS).readString(deep, Count.class).isSuccess()).isTrue();
        assertThat(JsonMapper.valueObjectJsonMapper().readString(deep, Count.class).isFailure()).isTrue();
    }

    @Test
    void withNaming_translatesUnrenamedProperties() {
        var snake = JsonMapper.jsonMapper()
                              .withPragmaticaTypes()
                              .withValueObjects()
                              .withNaming(NamingStrategy.SNAKE_CASE)
                              .build();

        assertThat(snake.writeAsString(account()).unwrap()).startsWith("{\"nick_name\":null,\"legacy_Name\":\"Jo\"");
    }

    /// #2378: the slice-route fallback factory applies the naming, the options and the value-object handling together.
    @Test
    void valueObjectMapperWithNamingAndOptions_appliesAllThree() {
        var mapper = JsonMapper.valueObjectJsonMapper(NamingStrategy.SNAKE_CASE,
                                                      options(NullInclusion.EXCLUDE, true, false, 1000, MAX, MAX, MAX));

        assertThat(mapper.writeAsString(new Count(3)).unwrap()).isEqualTo("{\"count\":3}");
        assertThat(mapper.writeAsString(account()).unwrap()).contains("\"legacy_Name\"")
                                                             .doesNotContain("nick_name")
                                                             .doesNotContain("nickName");
        assertThat(mapper.readString("{\"count\":3,\"extra\":1}", Count.class).isSuccess()).isFalse();
        assertThat(JsonMapper.valueObjectJsonMapper(NamingStrategy.SNAKE_CASE, JsonOptions.DEFAULTS)
                             .writeAsString(new Account(Option.some("n"), "d", Tier.PRO, Map.of(), List.of()))
                             .unwrap()).contains("\"nick_name\":\"n\"").contains("\"pro-tier\"");
    }
}
