// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.blueprint;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.ReplicationFactors;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.source.MapConfigSource;
import org.pragmatica.config.source.TomlConfigSource;
import org.pragmatica.config.toml.TomlParser;

import static org.assertj.core.api.Assertions.assertThat;

/// #3520: the inert storage-declaration gate decides from what the stream parser READS of the section (the keys of
/// [StreamConfigParser#DECLARATION_SECTION_KEYS], asked of the section view), never from which keys the static TOML happens to
/// list. So a `[retention]` or `[hot]` key is refused whichever spelling or layer supplies it, and a table array under a stream
/// section, which the parser never reads, is refused as malformed. One table of supply forms, each with its expected refusal.
class InertDeclarationSupplyFormsTest {
    private static final String ORDERS = "[streams.orders]\nversion = \"1.0.0\"\n";
    private static final String RETENTION_CLAUSE = "the stream's retention still comes from today's keys (`retention`, `retention_value`, "
                                                   + "`retention_mode`, `max_age`, `max_count`, `max_bytes`) until release step S2a3 enforces the [retention] "
                                                   + "declaration (#3334). Remove the [retention] sub-section and declare retention with today's keys until S2a3.";
    private static final String HOT_CLAUSE = "the ring is still sized from today's retention until release step S2b sizes it from the [hot] "
                                             + "window (#3334). Remove the [hot] sub-section until S2b.";

    private record Form(String name, StreamSection section, List<String> inertKeys) {}

    private static StreamSection toml(String text) {
        return StreamSection.tomlSection(TomlParser.parse(text).unwrap(), "streams.orders", "orders");
    }

    private static StreamSection overlaid(String staticToml, Map<String, String> overlay) {
        var provider = ConfigurationProvider.builder()
                                            .withSource(TomlConfigSource.tomlConfigSource(staticToml).unwrap())
                                            .withSource(MapConfigSource.mapConfigSource("overlay", overlay, 100).unwrap())
                                            .build();

        return StreamSection.providerSection(provider, "streams.orders");
    }

    private static List<Form> forms() {
        return List.of(new Form("dotted key", toml(ORDERS + "hot.max_count = 100\n"), List.of("hot.max_count")),
                       new Form("quoted key", toml(ORDERS + "\"retention.max_age\" = \"1h\"\n"), List.of("retention.max_age")),
                       new Form("[section] header",
                                toml(ORDERS + "[streams.orders.retention]\nmax_age = \"1h\"\nmax_bytes = \"2GB\"\n"),
                                List.of("retention.max_age", "retention.max_bytes")),
                       new Form("header after another stream's section",
                                toml(ORDERS + "[streams.billing]\nversion = \"1.0.0\"\n[streams.orders.hot]\nmax_bytes = \"8MB\"\n"),
                                List.of("hot.max_bytes")),
                       new Form("overlay layer, [hot] key beside a static section",
                                overlaid(ORDERS, Map.of("streams.orders.hot.max_count", "100")),
                                List.of("hot.max_count")),
                       new Form("overlay layer, [retention] key beside a static [retention] key",
                                overlaid(ORDERS + "[streams.orders.retention]\nmax_age = \"1h\"\n",
                                         Map.of("streams.orders.retention.max_bytes", "2GB")),
                                List.of("retention.max_age", "retention.max_bytes")),
                       new Form("overlay layer, [retention] key with no static [retention] key",
                                overlaid(ORDERS, Map.of("streams.orders.retention.max_bytes", "2GB")),
                                List.of("retention.max_bytes")),
                       new Form("static [hot] key beside an overlay [hot] key",
                                overlaid(ORDERS + "[streams.orders.hot]\nmax_bytes = \"8MB\"\n", Map.of("streams.orders.hot.max_count", "50")),
                                List.of("hot.max_bytes", "hot.max_count")));
    }

    @Test
    void everySupplyForm_isRefused_namingTheSourceKeys() {
        forms().forEach(form -> assertThat(InertKeyRules.inertDeclarationKeys(form.section())).as(form.name())
                                                                                              .extracting(InertKeyRules.InertKey::key)
                                                                                              .containsExactlyElementsOf(form.inertKeys()));
    }

    /// The two clauses, pinned whole and to their own table: a clause that named the other table's step would fail here, which
    /// `contains("S2b")` on a message could not see.
    @Test
    void eachRefusal_carriesItsOwnTablesFullClause() {
        var retention = InertKeyRules.inertDeclarationKeys(overlaid(ORDERS, Map.of("streams.orders.retention.max_bytes", "2GB")));
        var hot = InertKeyRules.inertDeclarationKeys(overlaid(ORDERS, Map.of("streams.orders.hot.max_count", "50")));

        assertThat(retention).extracting(InertKeyRules.InertKey::message)
                             .containsExactly("retention.max_bytes = '2GB' has no runtime effect yet — " + RETENTION_CLAUSE);
        assertThat(hot).extracting(InertKeyRules.InertKey::message)
                       .containsExactly("hot.max_count = '50' has no runtime effect yet — " + HOT_CLAUSE);
    }

    /// Controls: `[truncation]` is not inert and an empty `[retention]` header declares nothing, in either view.
    @Test
    void notInertForms_areNotRefused() {
        assertThat(InertKeyRules.inertDeclarationKeys(toml(ORDERS + "[streams.orders.truncation]\npolicy = \"window\"\n[streams.orders.retention]\n"))).isEmpty();
        assertThat(InertKeyRules.inertDeclarationKeys(overlaid(ORDERS, Map.of("streams.orders.truncation.policy", "window")))).isEmpty();
        assertThat(InertKeyRules.inertDeclarationKeys(overlaid(ORDERS, Map.of("streams.other.hot.max_count", "9")))).isEmpty();
    }

    private static String tableArrayRefusal(String text) {
        var rejected = StreamConfigParser.parseResourcesPartitioned(text, Map.of(), ReplicationFactors.BUILT_IN).unwrap().rejected();

        assertThat(rejected).as("a table array under a stream section must be refused: " + text).hasSize(1);
        assertThat(rejected.get(0).cause()).isInstanceOf(StreamDeclarationError.class);
        return rejected.get(0).cause().message();
    }

    /// `[[streams.<alias>.<name>]]` is a table array the parser never reads, so it was accepted and ignored.
    @Test
    void tableArrayUnderAStreamSection_isRefusedAsMalformed() {
        assertThat(tableArrayRefusal(ORDERS + "[[streams.orders.retention]]\nmax_bytes = \"2GB\"\n"))
            .isEqualTo("Stream resource 'orders' declares [[streams.orders.retention]] as an array of tables: a stream section holds tables, "
                       + "so write it as [streams.orders.retention]");
        assertThat(tableArrayRefusal(ORDERS + "[[streams.orders.hot]]\nmax_count = 100\n"))
            .isEqualTo("Stream resource 'orders' declares [[streams.orders.hot]] as an array of tables: a stream section holds tables, "
                       + "so write it as [streams.orders.hot]");
        assertThat(tableArrayRefusal(ORDERS + "[[streams.orders.consumers.billing]]\nbatch_size = 1\n"))
            .isEqualTo("Stream resource 'orders' declares [[streams.orders.consumers.billing]] as an array of tables: a stream section holds tables, "
                       + "so write it as [streams.orders.consumers.billing]");
    }

    /// The refusal is per stream: a table array under `orders2`, or one outside `streams.`, does not refuse `orders`.
    @Test
    void tableArrayUnderAnotherStream_orOutsideStreams_doesNotRefuseThisOne() {
        var text = ORDERS + "[streams.orders2]\nversion = \"1.0.0\"\n[[streams.orders2.retention]]\nmax_bytes = \"2GB\"\n[[nodes.peers]]\nname = \"a\"\n";
        var partitioned = StreamConfigParser.parseResourcesPartitioned(text, Map.of(), ReplicationFactors.BUILT_IN).unwrap();

        assertThat(partitioned.accepted()).containsOnlyKeys("orders");
        assertThat(partitioned.rejected()).extracting(StreamConfigParser.RefusedSection::alias).containsExactly("orders2");
    }
}
