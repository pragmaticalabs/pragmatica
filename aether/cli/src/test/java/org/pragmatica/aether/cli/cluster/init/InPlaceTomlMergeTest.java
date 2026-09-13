// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster.init;

import org.pragmatica.config.toml.TomlParser;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// #311 — the merge locates init-owned keys by LINE in the operator's text, so the line index must
/// read the text the way `TomlParser` does: a multi-line array or string before the key, a quoted or
/// dotted spelling of the key, and a `[[…]]` element's sub-table must all leave the rewrite on the
/// right line and everything else byte-for-byte.
class InPlaceTomlMergeTest {
    private static final String GENERATED = """
        [cluster]
        name = "new-name"

        [runtime.default]
        type = "docker"
        image = "ghcr.io/pragmaticalabs/aether-node:1.0.0"
        jvm_args = "new"
        """;

    private static InPlaceTomlMerge.Plan plan(String existing) {
        return TomlParser.parse(existing)
                         .flatMap(document -> InPlaceTomlMerge.plan(existing, document, GENERATED))
                         .fold(cause -> fail("plan refused: " + cause.message()), plan -> plan);
    }

    @Test
    void rewrite_landsOnTheKeyLine_pastMultiLineValues_keepingItsTrailingComment() {
        var existing = """
            cluster.name = "old-name"

            [runtime.default]
            type = "docker"
            extra = [
              1,
              2, # ]
            ]
            note = \"""
            jvm_args = "not me"
            \"""
            image = "ghcr.io/pragmaticalabs/aether-node:1.0.0"
            "jvm_args" = 'old'   # trailing comment stays
            """;
        var plan = plan(existing);

        assertThat(plan.changes()).extracting(InPlaceTomlMerge.Change::toString)
                  .containsExactly("cluster.name: \"old-name\" → \"new-name\"", "runtime.default.jvm_args: 'old' → \"new\"");
        assertThat(plan.added()).isEmpty();
        assertThat(plan.kept()).containsExactly("runtime.default.extra", "runtime.default.note");
        assertThat(plan.render(false)).as("without consent nothing moves").isEqualTo(existing);
        assertThat(plan.render(true)).isEqualTo(existing.replace("cluster.name = \"old-name\"", "cluster.name = \"new-name\"")
                                                        .replace("\"jvm_args\" = 'old'   # trailing comment stays",
                                                                 "\"jvm_args\" = \"new\"   # trailing comment stays"));
    }

    @Test
    void addition_goesIntoItsSection_beforeTrailingCommentsAndAfterMultiLineValues() {
        var existing = """
            [cluster]
            name = "new-name"

            [runtime.default]
            type = "docker"
            image = "ghcr.io/pragmaticalabs/aether-node:1.0.0"
            # Per-runtime environment variables (uncomment to set):
            # [runtime.default.env]

            [ops]
            owner = "me"
            """;
        var plan = plan(existing);

        assertThat(plan.changes()).isEmpty();
        assertThat(plan.added()).containsExactly("runtime.default.jvm_args");
        assertThat(plan.kept()).containsExactly("ops.owner");
        assertThat(plan.render(false)).isEqualTo(existing.replace("aether-node:1.0.0\"\n", "aether-node:1.0.0\"\njvm_args = \"new\"\n"));
    }

    @Test
    void missingSection_isInsertedAfterTheNearestPrecedingGeneratedBlock_notAtTheEnd() {
        var existing = """
            [cluster]
            name = "new-name"

            # advanced templates follow
            # [operations.timeouts]
            """;
        var plan = plan(existing);

        assertThat(plan.added()).containsExactly("runtime.default.type", "runtime.default.image", "runtime.default.jvm_args");
        assertThat(plan.render(false)).isEqualTo("""
            [cluster]
            name = "new-name"

            [runtime.default]
            type = "docker"
            image = "ghcr.io/pragmaticalabs/aether-node:1.0.0"
            jvm_args = "new"

            # advanced templates follow
            # [operations.timeouts]
            """);
    }

    @Test
    void arrayElementSubTables_areNotMistakenForSections() {
        var existing = """
            [cluster]
            name = "new-name"

            [[rules]]
            port = 1

            [rules.meta]
            type = "sub-table, not a section"

            [runtime.default]
            type = "docker"
            image = "ghcr.io/pragmaticalabs/aether-node:1.0.0"
            jvm_args = "new"
            """;
        var plan = plan(existing);

        assertThat(plan.changes()).isEmpty();
        assertThat(plan.added()).isEmpty();
        assertThat(plan.kept()).containsExactly("rules[port=1]");
        assertThat(plan.render(true)).isEqualTo(existing);
    }
}
