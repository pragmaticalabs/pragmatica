// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.blueprint;

import java.util.List;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.ConsumerConfig;
import org.pragmatica.aether.slice.ConsumerConfig.ErrorStrategy;
import org.pragmatica.aether.slice.ConsumerConfig.ProcessingMode;
import org.pragmatica.aether.slice.ReadPreference;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamCompression;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.config.toml.TomlParser;

import static org.assertj.core.api.Assertions.assertThat;

/// #2201 E-02 (spec 3.2 item 9): the one table names each inert key, and each refusal message starts with the key it
/// refuses, so an operator reading the deploy or activation refusal knows which line to remove (F4, i3).
class InertKeyRulesTest {
    @Test
    void everyNonDefaultConsumerKey_isNamed_andItsMessageStartsWithIt() {
        var everyKey = ConsumerConfig.consumerConfig("billing", 50, ProcessingMode.PARALLEL, ErrorStrategy.SKIP, 5_000L, 9, "orders-dlq", ReadPreference.ANY_REPLICA);
        var inert = InertKeyRules.inertConsumerKeys(everyKey);

        assertThat(inert).extracting(InertKeyRules.InertKey::key)
                         .containsExactly("batch_size", "processing", "on_failure", "checkpoint_interval", "max_retries", "dead_letter", "read_preference");
        assertThat(inert).allSatisfy(key -> assertThat(key.message()).startsWith(key.key() + " = '" + key.value() + "'"));
    }

    @Test
    void theConsumerDefaults_areNotInert() {
        assertThat(InertKeyRules.inertConsumerKeys(ConsumerConfig.consumerConfig("billing"))).isEmpty();
    }

    @Test
    void everyInertStreamKey_isNamed_andItsMessageStartsWithIt() {
        var config = StreamConfig.streamConfig("orders",
                                               1,
                                               RetentionPolicy.retentionPolicy(),
                                               "latest",
                                               StreamConfig.DEFAULT.maxEventSizeBytes(),
                                               ConsistencyMode.STRONG,
                                               StreamConfig.DEFAULT.replicationFactor(),
                                               StreamConfig.DEFAULT.confirmationFactor(),
                                               StreamCompression.LZ4,
                                               Option.some("orders-key"));
        var inert = InertKeyRules.inertStreamKeys(config);

        assertThat(inert).extracting(InertKeyRules.InertKey::key).containsExactly("encryption_key_id", "compression", "auto_offset_reset");
        assertThat(inert).allSatisfy(key -> assertThat(key.message()).startsWith(key.key()));
        assertThat(InertKeyRules.unsupportedConsistency(config).map(InertKeyRules.InertKey::message))
            .isEqualTo(Option.some(InertKeyRules.strongConsistencyMessage("consistency")));
        assertThat(List.of(InertKeyRules.strongConsistencyMessage("consistency"))).allSatisfy(message -> assertThat(message).startsWith("consistency 'strong'"));
    }

    private static StreamSection section(String body) {
        return StreamSection.tomlSection(TomlParser.parse("[streams.orders]\nversion = \"1.0.0\"\n" + body).unwrap(), "streams.orders", "orders");
    }

    /// #3495 item 1 tripwire (ENABLED, asserts CURRENT behaviour): the `[retention]` and `[hot]` declaration keys parse but
    /// no runtime code reads them, so they are refused as inert, in sorted key order (six keys: a lucky unsorted order is
    /// 1 in 720), each with its value, naming the release step that wires it.
    @Test
    void retentionAndHotDeclarationKeys_areRefusedAsInert_inSortedOrder_namingTheWiringStep() {
        var inert = InertKeyRules.inertDeclarationKeys(section("[streams.orders.retention]\nmax_age = \"1h\"\nmax_bytes = \"2GB\"\nmax_count = 100\nmode = \"all\"\n"
                                                               + "[streams.orders.hot]\nmax_bytes = \"8MB\"\nmax_count = 50\n"));

        assertThat(inert).extracting(InertKeyRules.InertKey::key)
                         .containsExactly("hot.max_bytes", "hot.max_count", "retention.max_age", "retention.max_bytes", "retention.max_count", "retention.mode");
        assertThat(inert).extracting(InertKeyRules.InertKey::value)
                         .containsExactly("8MB", "50", "1h", "2GB", "100", "all");
        assertThat(inert).allSatisfy(key -> assertThat(key.message()).startsWith(key.key() + " = '" + key.value() + "'")
                                                                      .contains("has no runtime effect yet"));
        assertThat(inert.get(0).message()).contains("S2b");
        assertThat(inert.get(2).message()).contains("S2a3");
    }

    /// The same keys written as dotted keys (no header) are refused alike: one rule over the section's keys.
    @Test
    void dottedRetentionKey_isRefusedAsInert() {
        assertThat(InertKeyRules.inertDeclarationKeys(section("retention.max_age = \"1h\"\n"))).extracting(InertKeyRules.InertKey::key)
                                                                                           .containsExactly("retention.max_age");
    }

    /// `[truncation]` is not inert (its only accepted policy is today's behaviour), and an empty `[retention]` carries no key.
    @Test
    void truncationAndEmptyTables_areNotInert() {
        assertThat(InertKeyRules.inertDeclarationKeys(section("[streams.orders.truncation]\npolicy = \"window\"\n[streams.orders.retention]\n"))).isEmpty();
    }

    /// Item 1 design assertion, beside the tripwire: once S2a3 / S2b wire the tables they leave INERT_DECLARATION_TABLES and
    /// a declared `[retention]` / `[hot]` is accepted. Enable when `INERT_DECLARATION_TABLES` is empty.
    @Test
    @Disabled("S2a3 wires [retention] and S2b wires [hot]: enable and delete the tripwire above")
    void retentionAndHotDeclarationKeys_areAccepted_onceWired() {
        assertThat(InertKeyRules.inertDeclarationKeys(section("[streams.orders.retention]\nmax_age = \"1h\"\n[streams.orders.hot]\nmax_count = 100\n"))).isEmpty();
    }
}
