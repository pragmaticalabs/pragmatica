package org.pragmatica.jbct.derive.emit;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// The minimal JSON emitter: correct escaping, and objects/arrays assembled from members.
class JsonTest {
    @Test
    void string_escapesQuotesAndBackslashes() {
        assertThat(Json.string("say \"hi\"")).isEqualTo("\"say \\\"hi\\\"\"");
        assertThat(Json.string("a\\b")).isEqualTo("\"a\\\\b\"");
        assertThat(Json.string("line\nbreak")).isEqualTo("\"line\\nbreak\"");
    }

    @Test
    void string_escapesControlCharacters() {
        assertThat(Json.string("a" + (char) 1 + "b")).isEqualTo("\"a\\u0001b\"");
        assertThat(Json.string("bell" + (char) 7)).isEqualTo("\"bell\\u0007\"");
        assertThat(Json.string("back" + (char) 8 + "space")).isEqualTo("\"back\\bspace\"");
        assertThat(Json.string("form" + (char) 12 + "feed")).isEqualTo("\"form\\ffeed\"");
        assertThat(Json.string("tab" + (char) 9 + "after")).isEqualTo("\"tab\\tafter\"");
    }

    @Test
    void object_assemblesMembers() {
        assertThat(Json.object(List.of(Json.str("k", "v"), Json.num("n", 3))))
            .isEqualTo("{\"k\":\"v\",\"n\":3}");
    }

    @Test
    void array_joinsElements() {
        assertThat(Json.stringArray(List.of("a", "b"))).isEqualTo("[\"a\",\"b\"]");
        assertThat(Json.array(List.of())).isEqualTo("[]");
    }
}
