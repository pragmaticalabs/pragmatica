// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.jbct.slice.topic;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import org.pragmatica.jbct.slice.topic.TopicDurabilityLoader.TopicDurabilityIndex;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

/// Pins every branch of the #386 D5 rule, including the two the annotation-processor harness cannot
/// reach: a topic READ and found ephemeral, and a topic read and found durable. That harness
/// compiles in memory with no readable `resources.toml`, so from there durability is always
/// undetermined — testing the rule through it would leave the branch that fires for real authors
/// unproven.
class MessageContextRuleTest {

    private static final String METHOD = "onOrderPlaced";
    private static final String TOPIC = "order-events";

    private static Option<TopicDurabilityIndex> readAs(String... durableSections) {
        return Option.some(new TopicDurabilityIndex(Set.of(durableSections)));
    }

    private static Option<TopicDurabilityIndex> unreadable() {
        return Option.none();
    }

    @Nested
    class DurableTopic {

        @Test
        void durabilityViolation_none_whenTopicIsDeclaredDurable() {
            var violation = MessageContextRule.durabilityViolation(METHOD, TOPIC, readAs(TOPIC));

            assertThat(violation.isEmpty()).isTrue();
        }

        /// Durability is per topic: a sibling topic being durable must not admit the shape here.
        @Test
        void durabilityViolation_present_whenOnlyAnotherTopicIsDurable() {
            var violation = MessageContextRule.durabilityViolation(METHOD, TOPIC, readAs("audit-events"));

            assertThat(violation.isPresent()).isTrue();
        }
    }

    @Nested
    class EphemeralTopic {

        /// The branch real authors hit: the file was read, the topic is simply not durable.
        @Test
        void durabilityViolation_explainsTheMissingEnvelope_whenTopicIsEphemeral() {
            var message = MessageContextRule.durabilityViolation(METHOD, TOPIC, readAs())
                                            .or("");

            assertThat(message).contains("MessageContext requires a durable topic");
            assertThat(message).contains("ephemeral dispatch carries no envelope");
            assertThat(message).contains(METHOD);
            assertThat(message).contains(TOPIC);
        }

        /// It must name the fix, and the fix is a declaration the author can go and write.
        @Test
        void durabilityViolation_namesTheDeclarationToAdd_whenTopicIsEphemeral() {
            var message = MessageContextRule.durabilityViolation(METHOD, TOPIC, readAs()).or("");

            assertThat(message).contains("durability = \"durable\"");
            assertThat(message).contains(TopicDurabilityLoader.CONFIG_FILE);
        }
    }

    @Nested
    class UnreadableConfiguration {

        /// Fail-closed: undetermined durability is still a refusal.
        @Test
        void durabilityViolation_present_whenConfigurationCouldNotBeRead() {
            assertThat(MessageContextRule.durabilityViolation(METHOD, TOPIC, unreadable()).isPresent()).isTrue();
        }

        /// The ruling's condition: an author whose build lost `resources.toml` must not be told their
        /// topic is ephemeral. The two causes lead to different fixes, so they must read differently
        /// — and this message must NOT claim an ephemeral verdict it did not establish.
        @Test
        void durabilityViolation_reportsUnreadableConfig_notAnEphemeralVerdict() {
            var message = MessageContextRule.durabilityViolation(METHOD, TOPIC, unreadable()).or("");

            assertThat(message).contains("could not be read");
            assertThat(message).contains("could not be determined");
            assertThat(message).contains("refused rather than assumed");
            assertThat(message).doesNotContain("is not declared durable");
            assertThat(message).doesNotContain("ephemeral dispatch carries no envelope");
        }

        /// Distinguishable in the strong sense: the two causes do not produce the same text.
        @Test
        void durabilityViolation_differsBetweenUnreadableAndEphemeral() {
            var unreadableMessage = MessageContextRule.durabilityViolation(METHOD, TOPIC, unreadable()).or("");
            var ephemeralMessage = MessageContextRule.durabilityViolation(METHOD, TOPIC, readAs()).or("");

            assertThat(unreadableMessage).isNotEqualTo(ephemeralMessage);
        }
    }

    @Nested
    class Interceptors {

        @Test
        void interceptorViolation_namesTheConstraintAndBothWaysOut() {
            var message = MessageContextRule.interceptorViolation(METHOD);

            assertThat(message).contains(METHOD);
            assertThat(message).contains("cannot carry the delivery context");
            assertThat(message).contains("Remove the interceptors");
            assertThat(message).contains("drop the MessageContext parameter");
        }
    }
}
