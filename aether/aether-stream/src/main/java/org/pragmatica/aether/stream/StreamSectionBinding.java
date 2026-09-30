// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;
import org.pragmatica.utility.warning.OperatorWarnings;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.aether.slice.ReplicationContext;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.blueprint.StreamConfigParser;
import org.pragmatica.aether.slice.blueprint.StreamDeclarationError;
import org.pragmatica.aether.slice.blueprint.StreamSection;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.lang.Result;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// The section binder both stream resource factories share (#1549): a `[streams.X]` section provisioned at
/// slice activation is parsed by [StreamConfigParser#parseStreamConfig], the parse deploy validation runs
/// over the same declaration. Before #1549 the generic record binder read snake_case component names and
/// resolved every key it did not find from `StreamConfig.DEFAULT`, so the documented dashed keys
/// (`min-sync-replicas`, `max-event-size`, `retention*`, `auto-offset-reset`, `encryption-key-id`) never
/// reached the runtime.
public sealed interface StreamSectionBinding {
    Logger LOG = LoggerFactory.getLogger(StreamSectionBinding.class);

    /// #1564: the section's `replication_factor`/`confirmation_factor` are resolved against the node's
    /// [ReplicationContext] — the committed cluster defaults and desired core count — the same resolution deploy
    /// validation applies; the declaration's warnings are raised here, at activation, as `replication-policy-warning`
    /// operator warnings through the node's [OperatorWarningSink] (a WARN log, plus a cluster event when the node
    /// supplies the sink).
    static Result<StreamConfig> bindStreamSection(ConfigurationProvider provider,
                                                  String section,
                                                  ProvisioningContext context) {
        return context.extension(ReplicationContext.Source.class)
                      .mapError(_ -> new StreamDeclarationError.ReplicationContextUnavailable(StreamSection.providerSection(provider,
                                                                                                                            section).alias()))
                      .flatMap(ReplicationContext.Source::current)
                      .flatMap(replication -> bindAgainst(provider,
                                                          section,
                                                          replication,
                                                          warningSink(context)));
    }

    private static Result<StreamConfig> bindAgainst(ConfigurationProvider provider,
                                                    String section,
                                                    ReplicationContext replication,
                                                    OperatorWarningSink sink) {
        var streamSection = StreamSection.providerSection(provider, section);

        return StreamConfigParser.parseStreamDeclaration(streamSection,
                                                         replication.defaults())
                                 .flatMap(declared -> declared.config()
                                                              .replication()
                                                              .withinCoreCount(replication.desiredCoreCount())
                                                              .mapError(cause -> new StreamDeclarationError.ReplicationRefused(streamSection.alias(),
                                                                                                                               cause))
                                                              .map(_ -> declared))
                                 .onSuccess(declared -> raiseWarnings(sink,
                                                                      streamSection.alias(),
                                                                      declared))
                                 .map(StreamConfigParser.DeclaredStream::config);
    }

    /// The node's operator-warning sink when it supplies one; otherwise the WARN log is the whole report.
    static OperatorWarningSink warningSink(ProvisioningContext context) {
        return context.extension(OperatorWarningSink.class)
                      .or(OperatorWarningSink.logOnly());
    }

    private static void raiseWarnings(OperatorWarningSink sink,
                                      String alias,
                                      StreamConfigParser.DeclaredStream declared) {
        var resource = "stream '" + alias + "'";

        declared.warnings()
                .forEach(warning -> OperatorWarnings.raise(LOG,
                                                           sink,
                                                           OperatorWarningCode.REPLICATION_POLICY_WARNING,
                                                           resource,
                                                           "{}stream replication warning [{}]: {}",
                                                           warning.loud()
                                                           ? "LOUD: "
                                                           : "",
                                                           warning.code(),
                                                           warning.message(resource,
                                                                           declared.config().replication())));
    }

    record unused() implements StreamSectionBinding {}
}
