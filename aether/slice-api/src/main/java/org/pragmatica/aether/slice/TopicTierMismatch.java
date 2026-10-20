// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.utils.Causes;


/// An ephemeral-tier delivery reached a subscriber whose handler belongs to a durable topic (#1337): the
/// handler declares [org.pragmatica.aether.slice.topic.ContextualEvent] as its parameter, and an ephemeral
/// delivery carries a bare event with no envelope to build that context from.
///
/// It is a refusal, not a defect. The slice version is not broken — the topic's publisher and subscriber
/// disagree about its durability class — so this is deliberately NOT a [SliceDefect]: it must never feed the
/// leader's all-instances-failed detector, which would read it as a bad version and could roll back a healthy
/// one. Before this type the delivery reached the generated adapter, which threw a `ClassCastException`
/// tagged [SliceDefect.MethodThrew].
///
/// Across nodes only the message text survives (`InvokeResponse` carries a string), so the publisher
/// recognises a refusal from a remote subscriber by [#MARKER] in the text, through [#isTierMismatch].
///
/// @param method the subscriber method that was refused
public record TopicTierMismatch(String method, String message) implements Cause {
    /// The stable lead of every refusal's text; what a publisher matches when the refusal came back as a string.
    public static final String MARKER = "Topic tier mismatch";

    public static final Fn1<TopicTierMismatch, String> FACTORY = Causes.forOneValue(MARKER + ": method '%s' takes a ContextualEvent (a durable-topic handler) but was delivered a bare event by an ephemeral-tier publisher; declare the publisher's topic durable or drop the context parameter",
                                                                                    TopicTierMismatch::new);

    /// Whether `cause` is a tier-mismatch refusal, produced here or relayed from another node as text.
    public static boolean isTierMismatch(Cause cause) {
        return cause instanceof TopicTierMismatch || cause.message()
                                                          .contains(MARKER);
    }
}
