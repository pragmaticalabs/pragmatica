// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.validation;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.validation.ConfigSectionPreflightValidator.SliceJar;
import org.pragmatica.aether.slice.topology.SliceTopology;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;

/// #1337: a subscriber whose handler takes the delivery context needs a durable topic, and each jar reads the topic's durability
/// from its OWN `resources.toml`, so a publisher jar and a subscriber jar can disagree without either build or load noticing. The
/// pre-flight compares them on the resolved address and refuses the deploy.
class TopicTierPreflightTest {
    private static final String ADDRESS = "orders-app:order-events:1.0.0";
    private static final String EPHEMERAL_TOML = "[order-events]\ntopic_name = \"order-events\"\n";
    private static final String DURABLE_TOML = "[order-events]\ntopic_name = \"order-events\"\ndurability = \"durable\"\n";

    @Test
    void ephemeralPublisher_contextSubscriber_isRefused_namingTopicPublisherAndSubscriber() {
        var outcome = validate(publisherJar("order-pub", EPHEMERAL_TOML), subscriberJar("order-sub", "onOrderPlaced", true, DURABLE_TOML));

        assertThat(message(outcome)).contains("topic tier mismatch")
                                    .contains(ADDRESS)
                                    .contains("EPHEMERAL")
                                    .contains("order-pub")
                                    .contains("order-sub.onOrderPlaced")
                                    .contains("MessageContext");
    }

    /// The publisher says nothing about durability: that IS the ephemeral default at runtime, so it is refused the same way.
    @Test
    void publisherWithNoSectionAtAll_isEphemeral_andIsRefused() {
        var outcome = validate(publisherJar("order-pub", "[unrelated]\nkey = \"value\"\n"), subscriberJar("order-sub", "onOrderPlaced", true, DURABLE_TOML));

        assertThat(message(outcome)).contains("topic tier mismatch").contains("order-pub");
    }

    @Test
    void durablePublisher_contextSubscriber_isAccepted() {
        assertThat(validate(publisherJar("order-pub", DURABLE_TOML), subscriberJar("order-sub", "onOrderPlaced", true, DURABLE_TOML)).isSuccess()).isTrue();
    }

    /// A bare handler works on either tier, so an ephemeral publisher is no reason to refuse it.
    @Test
    void ephemeralPublisher_bareSubscriber_isAccepted() {
        assertThat(validate(publisherJar("order-pub", EPHEMERAL_TOML), subscriberJar("order-sub", "onOrderPlaced", false, DURABLE_TOML)).isSuccess()).isTrue();
    }

    /// Topics are matched on the resolved address, so a context subscriber of ANOTHER topic is not a conflict.
    @Test
    void contextSubscriberOfAnotherTopic_isNotCompared() {
        var other = new SliceTopology("order-sub",
                                      "org.example:order-sub:1.0.0",
                                      List.of(),
                                      List.of(),
                                      List.of(),
                                      List.of(),
                                      List.of(new SliceTopology.TopicSub("audit", "orders-app:audit:1.0.0", "onAudit", "Audit", true)));
        var subscriber = SliceJar.sliceJar(artifact("order-sub"), List.of(other), Option.some(DURABLE_TOML));

        assertThat(validate(publisherJar("order-pub", EPHEMERAL_TOML), subscriber).isSuccess()).isTrue();
    }

    /// Every conflict is reported, not the first.
    @Test
    void twoConflicts_areBothReported() {
        var outcome = validate(publisherJar("order-pub", EPHEMERAL_TOML),
                               subscriberJar("sub-a", "onA", true, DURABLE_TOML),
                               subscriberJar("sub-b", "onB", true, DURABLE_TOML));

        assertThat(message(outcome)).contains("sub-a.onA").contains("sub-b.onB");
    }

    /// Fail-open: with no view of the publisher's durability (no node composite, no resources.toml in its jar) it is "not
    /// checked", never "checked and refused".
    @Test
    void publisherWhoseDurabilityCannotBeRead_isNotChecked() {
        var blind = SliceJar.sliceJar(artifact("order-pub"), List.of(publisherTopology("order-pub")), Option.none());

        assertThat(validate(blind, subscriberJar("order-sub", "onOrderPlaced", true, DURABLE_TOML)).isSuccess()).isTrue();
    }

    private static Result<Unit> validate(SliceJar... jars) {
        return TopicTierPreflight.validate(List.of(jars), Option.none());
    }

    private static String message(Result<Unit> outcome) {
        return outcome.fold(cause -> cause.message(), _ -> "accepted");
    }

    private static SliceJar publisherJar(String slice, String toml) {
        return SliceJar.sliceJar(artifact(slice), List.of(publisherTopology(slice)), Option.some(toml));
    }

    private static SliceTopology publisherTopology(String slice) {
        return new SliceTopology(slice,
                                 "org.example:" + slice + ":1.0.0",
                                 List.of(),
                                 List.of(),
                                 List.of(),
                                 List.of(new SliceTopology.TopicPub("order-events", ADDRESS, "OrderPlaced")),
                                 List.of());
    }

    private static SliceJar subscriberJar(String slice, String method, boolean contextual, String toml) {
        var topology = new SliceTopology(slice,
                                         "org.example:" + slice + ":1.0.0",
                                         List.of(),
                                         List.of(),
                                         List.of(),
                                         List.of(),
                                         List.of(new SliceTopology.TopicSub("order-events", ADDRESS, method, "OrderPlaced", contextual)));

        return SliceJar.sliceJar(artifact(slice), List.of(topology), Option.some(toml));
    }

    private static Artifact artifact(String slice) {
        return Artifact.artifact("org.example:" + slice + ":1.0.0").unwrap();
    }
}
