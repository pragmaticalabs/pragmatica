// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.jbct.slice.topic;

import org.pragmatica.jbct.slice.model.MethodModel;
import org.pragmatica.jbct.slice.topic.TopicDurabilityLoader.TopicDurabilityIndex;
import org.pragmatica.lang.Option;


/// The D5 type-level-honesty rule (#386 durable-pubsub-spec §3): a subscriber may declare the
/// `(T event, MessageContext context)` shape only where the delivery actually carries an envelope.
///
/// Kept as a pure decision over an already-loaded durability view, separate from the processor that
/// reads the file. That is not tidiness: the annotation-processor test harness compiles in memory
/// and can never present a readable `resources.toml`, so the DECLARED-EPHEMERAL branch is
/// unreachable from there and would otherwise ship with no pin at all. Here every branch is
/// directly exercisable.
///
/// Fail-closed by construction — [#durabilityViolation] yields a violation whenever durability is
/// not positively established. There is deliberately no overload admitting a default: a permissive
/// default is the one thing this rule must not be able to acquire.
public final class MessageContextRule {
    private MessageContextRule() {}

    /// The violation, if any, for one context-carrying subscription on `topicSection`.
    ///
    /// `durability` is `none()` when `resources.toml` could not be read. That case is reported
    /// distinctly from a topic that was read and found ephemeral: an author whose build lost the
    /// file must not be told their topic is ephemeral, because the declaration they would be sent to
    /// fix may already say `durable`.
    public static Option<String> durabilityViolation(String methodName,
                                                     String topicSection,
                                                     Option<TopicDurabilityIndex> durability) {
        return durability.fold(() -> Option.some(unreadableConfig(methodName, topicSection)),
                               index -> index.isDurable(topicSection)
                                        ? Option.none()
                                        : Option.some(ephemeralTopic(methodName, topicSection)));
    }

    /// The interceptor chain is typed `Fn1<Promise<Unit>, T>` on the payload alone, so it has
    /// nowhere to carry the context. Generating the combination would either drop the context
    /// silently or emit a wrapper that does not implement the declared two-argument signature.
    public static String interceptorViolation(String methodName) {
        return "Subscription method '" + methodName
             + "' declares a " + MethodModel.MESSAGE_CONTEXT_TYPE
             + " parameter and also carries method interceptors. The interceptor chain is typed on the"
             + " event alone and cannot carry the delivery context, so the context would be silently"
             + " dropped. Remove the interceptors from this handler, or drop the MessageContext parameter.";
    }

    private static String unreadableConfig(String methodName, String topicSection) {
        return "Subscription method '" + methodName
             + "' declares a " + MethodModel.MESSAGE_CONTEXT_TYPE
             + " parameter, but '" + TopicDurabilityLoader.CONFIG_FILE
             + "' could not be read, so the durability of topic '" + topicSection
             + "' could not be determined. MessageContext requires a durable topic, and this is refused"
             + " rather than assumed — an unreadable declaration is not evidence of an ephemeral one."
             + " Ensure '" + TopicDurabilityLoader.CONFIG_FILE
             + "' is among the slice's resources and parses, then rebuild.";
    }

    private static String ephemeralTopic(String methodName, String topicSection) {
        return "Subscription method '" + methodName
             + "' declares a " + MethodModel.MESSAGE_CONTEXT_TYPE
             + " parameter, but topic '" + topicSection
             + "' is not declared durable. MessageContext requires a durable topic: ephemeral dispatch"
             + " carries no envelope, so the message id, partition and offset would be fabricated."
             + " Either declare durability = \"durable\" in the '" + topicSection
             + "' section of " + TopicDurabilityLoader.CONFIG_FILE
             + ", or drop the MessageContext parameter.";
    }
}
