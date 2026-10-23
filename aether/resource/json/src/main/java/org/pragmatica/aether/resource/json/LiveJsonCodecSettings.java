// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.json;

import java.util.List;
import java.util.stream.Collectors;

import org.pragmatica.aether.slice.JsonCodecSelection;
import org.pragmatica.aether.slice.JsonCodecSettings;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Unit;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;
import org.pragmatica.utility.warning.OperatorWarnings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// A slice's JSON codec selection, re-bound from the slice's configuration provider on every [#refresh].
///
/// **What a reader sees.** [#current] is one volatile read of an immutable [JsonCodecSelection], so a router that
/// samples it once per request decodes and encodes that request under one selection, the old one or the new one,
/// never a mix. Nodes switch independently: each applies its own copy of the committed configuration.
///
/// **Refreshes are serialized.** [#refresh] is `synchronized`, and the node calls it from its single
/// config-notification thread; no test exercises concurrent refreshes, so the lock's effect under contention rests on
/// reading, not on a test. Each refresh binds the whole section as it stands, so a run of KV writes that passes
/// through invalid states (`default` written before its codec) converges on the last valid one.
///
/// **An invalid configuration keeps the previous selection** (owner ruling, #2378): it is reported through
/// [OperatorWarnings#raise] with [OperatorWarningCode#JSON_CONFIG_REJECTED] (ERROR log, operator warning and cluster
/// event in one call) on the transition into refusal only, and [OperatorWarningCode#JSON_CONFIG_APPLIED] once when a
/// valid configuration is adopted after it. A refusal that changes while already refused is logged, not raised again.
/// The same configuration fails the slice's next provisioning, where the binder refuses it.
final class LiveJsonCodecSettings implements JsonCodecSettings {
    private static final Logger LOG = LoggerFactory.getLogger(LiveJsonCodecSettings.class);

    private final ConfigurationProvider provider;
    private final String section;
    private final OperatorWarningSink sink;
    private final String subject;
    private volatile JsonCodecSelection current;
    private String refusal = "";

    private LiveJsonCodecSettings(ConfigurationProvider provider,
                                  String section,
                                  OperatorWarningSink sink,
                                  String subject,
                                  JsonCodecSelection initial) {
        this.provider = provider;
        this.section = section;
        this.sink = sink;
        this.subject = subject;
        this.current = initial;
    }

    /// A handle over `provider`'s `section`, starting at `initial` (the selection provisioning bound); `subject` names
    /// the slice in the operator warnings (`<artifact>/json`).
    static LiveJsonCodecSettings liveJsonCodecSettings(ConfigurationProvider provider,
                                                       String section,
                                                       OperatorWarningSink sink,
                                                       String subject,
                                                       JsonCodecSelection initial) {
        return new LiveJsonCodecSettings(provider, section, sink, subject, initial);
    }

    @Override
    public JsonCodecSelection current() {
        return current;
    }

    @Override
    public synchronized Unit refresh() {
        return JsonSectionBinder.bind(provider, section).fold(this::refuse, config -> adopt(config.selected()));
    }

    // An equal selection keeps the old reference, so a router comparing by reference keeps its built codec.
    private Unit adopt(JsonCodecSelection selection) {
        if (!selection.equals(current)) {
            current = selection;
        }

        if (!refusal.isEmpty()) {
            refusal = "";
            OperatorWarnings.raise(LOG,
                                   sink,
                                   OperatorWarningCode.JSON_CONFIG_APPLIED,
                                   subject,
                                   "JSON settings of {} applied: codec '{}' ({})",
                                   subject,
                                   selection.name(),
                                   selection.implementation());
        }

        return Unit.unit();
    }

    private Unit refuse(Cause cause) {
        var reasons = reasons(cause);

        if (refusal.isEmpty()) {
            OperatorWarnings.raise(LOG,
                                   sink,
                                   OperatorWarningCode.JSON_CONFIG_REJECTED,
                                   subject,
                                   "JSON settings of {} rejected, keeping codec '{}' ({}): {}",
                                   subject,
                                   current.name(),
                                   current.implementation(),
                                   reasons);
        } else if (!refusal.equals(reasons)) {
            LOG.error("[{}] JSON settings of {} still rejected, keeping codec '{}' ({}): {}",
                      OperatorWarningCode.JSON_CONFIG_REJECTED.code(),
                      subject,
                      current.name(),
                      current.implementation(),
                      reasons);
        }

        refusal = reasons;

        return Unit.unit();
    }

    private static String reasons(Cause cause) {
        return leaves(cause).stream()
                     .map(Cause::message)
                     .collect(Collectors.joining("; "));
    }

    private static List<Cause> leaves(Cause cause) {
        return cause instanceof org.pragmatica.lang.utils.Causes.CompositeCause composite
               ? composite.stream()
                          .flatMap(inner -> leaves(inner).stream())
                          .toList()
               : List.of(cause);
    }
}
