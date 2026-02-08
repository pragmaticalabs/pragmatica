package org.pragmatica.aether.forge.load.pattern;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PatternParserTest {

    @Test
    void parse_uuid_succeeds() {
        PatternParser.parse("${uuid}")
            .onFailureRun(Assertions::fail)
            .onSuccess(gen -> {
                assertThat(gen).isInstanceOf(UuidGenerator.class);
                var uuid = gen.generate();
                assertThat(uuid).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
            });
    }

    @Test
    void parse_random_succeeds() {
        PatternParser.parse("${random:SKU-#####}")
            .onFailureRun(Assertions::fail)
            .onSuccess(gen -> {
                assertThat(gen).isInstanceOf(RandomGenerator.class);
                var value = gen.generate();
                assertThat(value).matches("SKU-\\d{5}");
            });
    }

    @Test
    void parse_random_fails_withoutTemplate() {
        PatternParser.parse("${random}")
            .onSuccessRun(Assertions::fail)
            .onFailure(cause -> assertThat(cause.message()).contains("random requires template"));
    }

    @Test
    void parse_range_succeeds() {
        PatternParser.parse("${range:1-100}")
            .onFailureRun(Assertions::fail)
            .onSuccess(gen -> {
                assertThat(gen).isInstanceOf(RangeGenerator.class);
                var value = Integer.parseInt(gen.generate());
                assertThat(value).isBetween(1, 100);
            });
    }

    @Test
    void parse_range_fails_withoutArgs() {
        PatternParser.parse("${range}")
            .onSuccessRun(Assertions::fail)
            .onFailure(cause -> assertThat(cause.message()).contains("range requires MIN-MAX"));
    }

    @Test
    void parse_choice_succeeds() {
        PatternParser.parse("${choice:NYC,LAX,CHI}")
            .onFailureRun(Assertions::fail)
            .onSuccess(gen -> {
                assertThat(gen).isInstanceOf(ChoiceGenerator.class);
                var value = gen.generate();
                assertThat(value).isIn("NYC", "LAX", "CHI");
            });
    }

    @Test
    void parse_choice_fails_withoutArgs() {
        PatternParser.parse("${choice}")
            .onSuccessRun(Assertions::fail)
            .onFailure(cause -> assertThat(cause.message()).contains("choice requires values"));
    }

    @Test
    void parse_seq_succeeds_withStart() {
        PatternParser.parse("${seq:1000}")
            .onFailureRun(Assertions::fail)
            .onSuccess(gen -> {
                assertThat(gen).isInstanceOf(SequenceGenerator.class);
                assertThat(gen.generate()).isEqualTo("1000");
                assertThat(gen.generate()).isEqualTo("1001");
            });
    }

    @Test
    void parse_seq_succeeds_withoutStart() {
        PatternParser.parse("${seq}")
            .onFailureRun(Assertions::fail)
            .onSuccess(gen -> {
                assertThat(gen).isInstanceOf(SequenceGenerator.class);
                assertThat(gen.generate()).isEqualTo("1");
            });
    }

    @Test
    void parse_fails_forUnknownType() {
        PatternParser.parse("${unknown:args}")
            .onSuccessRun(Assertions::fail)
            .onFailure(cause -> assertThat(cause.message()).contains("Unknown pattern type"));
    }

    @Test
    void parse_fails_forInvalidSyntax() {
        PatternParser.parse("not-a-pattern")
            .onSuccessRun(Assertions::fail)
            .onFailure(cause -> assertThat(cause.message()).contains("Invalid pattern syntax"));
    }

    @Test
    void containsPatterns_returnsTrue_forPatternText() {
        assertThat(PatternParser.containsPatterns("Hello ${uuid} world")).isTrue();
    }

    @Test
    void containsPatterns_returnsFalse_forPlainText() {
        assertThat(PatternParser.containsPatterns("Hello world")).isFalse();
    }

    @Test
    void extractType_returnsType_forValidPattern() {
        assertThat(PatternParser.extractType("${uuid}").or("")).isEqualTo("uuid");
        assertThat(PatternParser.extractType("${random:###}").or("")).isEqualTo("random");
        assertThat(PatternParser.extractType("${range:1-10}").or("")).isEqualTo("range");
    }

    @Test
    void extractType_returnsEmpty_forInvalidPattern() {
        assertThat(PatternParser.extractType("not-a-pattern").isEmpty()).isTrue();
    }
}
