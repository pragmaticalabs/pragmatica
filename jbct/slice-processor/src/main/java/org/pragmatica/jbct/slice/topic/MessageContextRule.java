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

    /// Interceptors generate a wrapper record implementing the slice interface over EVERY method,
    /// with one `Fn1<Promise<R>, T>` component per method — typed on the payload alone. A
    /// context-carrying handler cannot be represented there: the override must take two arguments
    /// while the function accepts one, and the only way to make that compile is to drop the context.
    ///
    /// The scope is the SLICE, not the handler. An interceptor on any other method of the same slice
    /// still generates the wrapper, and the wrapper still walks this handler — which is exactly the
    /// case a per-method check misses.
    public static String interceptorViolation(String methodName) {
        return "Subscription method '" + methodName
             + "' declares a " + MethodModel.MESSAGE_CONTEXT_TYPE
             + " parameter, but the slice declares method interceptors. Interceptors generate a wrapper"
             + " implementing the slice over EVERY method, whose function components are typed on the"
             + " event alone and cannot carry the delivery context — it would be silently dropped."
             + " The interceptor does not have to be on this handler: one anywhere in the slice"
             + " generates the wrapper. Remove the slice's method interceptors, or drop the"
             + " MessageContext parameter.";
    }

    /// A context-carrying handler reached through a slice DEPENDENCY proxy. The proxy exists to let
    /// one slice call another remotely, and a caller has no envelope to draw a context from — it
    /// would have to fabricate one, which is the lie this rule exists to prevent. Refusing also
    /// removes the phantom `dep_<Method>Request(T, MessageContext)` record the proxy would otherwise
    /// synthesize: a type the runtime is told to serialize that nothing will ever send.
    public static String dependencyMethodViolation(String declaringInterface,
                                                   String methodName,
                                                   String dependencyInterface) {
        var inherited = declaringInterface.equals(dependencyInterface)
                        ? ""
                        : " (inherited by dependency " + dependencyInterface + ")";

        return "Slice dependency method " + declaringInterface + "." + methodName
             + " takes a " + MethodModel.MESSAGE_CONTEXT_TYPE
             + " parameter" + inherited
             + ". Delivery context is supplied by the dispatcher from the envelope of an actual"
             + " delivery, so a caller invoking this method through the slice-to-slice proxy could only"
             + " fabricate one. A context-carrying subscriber is not remotely invocable: keep it as a"
             + " subscription handler, or drop the MessageContext parameter from the dependency"
             + " interface.";
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
