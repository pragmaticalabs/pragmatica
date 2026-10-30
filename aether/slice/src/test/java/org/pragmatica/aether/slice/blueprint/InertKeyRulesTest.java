// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.blueprint;

import java.util.List;

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
}
