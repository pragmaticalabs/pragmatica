package org.pragmatica.jbct.slice.routing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OverridePolicyTest {

    @Test
    void parse_returnsStrengthenOnly_forStrengthenOnlyString() {
        var result = OverridePolicy.parse("strengthen_only");

        assertThat(result.isSuccess()).isTrue();
        result.onSuccess(policy -> assertThat(policy).isEqualTo(OverridePolicy.STRENGTHEN_ONLY));
    }

    @Test
    void parse_returnsFull_forFullString() {
        var result = OverridePolicy.parse("full");

        assertThat(result.isSuccess()).isTrue();
        result.onSuccess(policy -> assertThat(policy).isEqualTo(OverridePolicy.FULL));
    }

    @Test
    void parse_returnsNone_forNoneString() {
        var result = OverridePolicy.parse("none");

        assertThat(result.isSuccess()).isTrue();
        result.onSuccess(policy -> assertThat(policy).isEqualTo(OverridePolicy.NONE));
    }

    @Test
    void parse_isCaseInsensitive() {
        var result = OverridePolicy.parse("STRENGTHEN_ONLY");

        assertThat(result.isSuccess()).isTrue();
        result.onSuccess(policy -> assertThat(policy).isEqualTo(OverridePolicy.STRENGTHEN_ONLY));
    }

    @Test
    void parse_fails_forUnknownValue() {
        var result = OverridePolicy.parse("unknown");

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause.message()).contains("Unknown override policy"));
    }

    @Test
    void parse_fails_forEmptyValue() {
        var result = OverridePolicy.parse("");

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause.message()).contains("Empty"));
    }

    @Test
    void parse_fails_forNullValue() {
        var result = OverridePolicy.parse(null);

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause.message()).contains("Empty"));
    }
}
