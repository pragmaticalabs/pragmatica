package org.pragmatica.aether.forge.load.pattern;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RangeGeneratorTest {

    @Test
    void parse_succeeds_forValidRange() {
        RangeGenerator.rangeGenerator("1-100")
            .onFailureRun(Assertions::fail)
            .onSuccess(gen -> {
                assertThat(gen).isInstanceOf(RangeGenerator.class);
                var rg = (RangeGenerator) gen;
                assertThat(rg.min()).isEqualTo(1);
                assertThat(rg.max()).isEqualTo(100);
            });
    }

    @Test
    void parse_succeeds_forNegativeRange() {
        RangeGenerator.rangeGenerator("-50-50")
            .onFailureRun(Assertions::fail)
            .onSuccess(gen -> {
                var rg = (RangeGenerator) gen;
                assertThat(rg.min()).isEqualTo(-50);
                assertThat(rg.max()).isEqualTo(50);
            });
    }

    @Test
    void parse_succeeds_forSingleValue() {
        RangeGenerator.rangeGenerator("5-5")
            .onFailureRun(Assertions::fail)
            .onSuccess(gen -> {
                assertThat(gen.generate()).isEqualTo("5");
            });
    }

    @Test
    void parse_fails_forMinGreaterThanMax() {
        RangeGenerator.rangeGenerator("100-1")
            .onSuccessRun(Assertions::fail)
            .onFailure(cause -> assertThat(cause.message()).contains("min cannot be greater than max"));
    }

    @Test
    void parse_fails_forInvalidFormat() {
        RangeGenerator.rangeGenerator("invalid")
            .onSuccessRun(Assertions::fail)
            .onFailure(cause -> assertThat(cause.message()).contains("Invalid range format"));
    }

    @Test
    void parse_fails_forNonNumericValues() {
        RangeGenerator.rangeGenerator("a-b")
            .onSuccessRun(Assertions::fail);
    }

    @Test
    void generate_returnsValuesInRange() {
        var gen = new RangeGenerator(1, 10);
        for (int i = 0; i < 100; i++) {
            var value = Integer.parseInt(gen.generate());
            assertThat(value).isBetween(1, 10);
        }
    }

    @Test
    void pattern_returnsCorrectFormat() {
        var gen = new RangeGenerator(1, 100);
        assertThat(gen.pattern()).isEqualTo("${range:1-100}");
    }
}
