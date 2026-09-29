// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

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
    /// validation applies; the declaration's warnings are logged here, at activation.
    static Result<StreamConfig> bindStreamSection(ConfigurationProvider provider,
                                                  String section,
                                                  ProvisioningContext context) {
        return context.extension(ReplicationContext.Source.class)
                      .flatMap(ReplicationContext.Source::current)
                      .flatMap(replication -> bindAgainst(provider, section, replication));
    }

    private static Result<StreamConfig> bindAgainst(ConfigurationProvider provider,
                                                    String section,
                                                    ReplicationContext replication) {
        var streamSection = StreamSection.providerSection(provider, section);

        return StreamConfigParser.parseStreamDeclaration(streamSection, replication.defaults())
                                 .flatMap(declared -> declared.config()
                                                              .replication()
                                                              .withinCoreCount(replication.desiredCoreCount())
                                                              .mapError(cause -> new StreamDeclarationError.ReplicationRefused(streamSection.alias(),
                                                                                                                               cause))
                                                              .map(_ -> declared))
                                 .onSuccess(declared -> logWarnings(streamSection.alias(), declared))
                                 .map(StreamConfigParser.DeclaredStream::config);
    }

    private static void logWarnings(String alias, StreamConfigParser.DeclaredStream declared) {
        declared.warnings()
                .forEach(warning -> LOG.warn("{}stream replication warning [{}]: {}",
                                             warning.loud()
                                             ? "LOUD: "
                                             : "",
                                             warning.code(),
                                             warning.message("stream '" + alias + "'",
                                                             declared.config().replication())));
    }

    record unused() implements StreamSectionBinding {}
}
