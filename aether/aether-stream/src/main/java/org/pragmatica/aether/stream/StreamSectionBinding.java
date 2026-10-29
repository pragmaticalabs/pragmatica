// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.List;
import java.util.stream.Stream;

import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;
import org.pragmatica.utility.warning.OperatorWarnings;
import org.pragmatica.aether.slice.ConsumerConfig;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.aether.slice.ReplicationContext;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.blueprint.InertKeyRules;
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
/// (`confirmation_factor`, `max_event_size`, `retention*`, `auto_offset_reset`, `encryption_key_id`) never
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
                                 .flatMap(declared -> refuseInertKeys(provider,
                                                                      section,
                                                                      streamSection.alias(),
                                                                      declared))
                                 .onSuccess(declared -> raiseWarnings(sink,
                                                                      streamSection.alias(),
                                                                      declared))
                                 .map(StreamConfigParser.DeclaredStream::config);
    }

    /// #2201 E-02: a key the deploy validator refuses as inert ([InertKeyRules]) is refused here too, for the stream and for
    /// each of its consumer sections, so a declaration that reaches activation without the validator cannot bind with a key
    /// that silently does nothing.
    private static Result<StreamConfigParser.DeclaredStream> refuseInertKeys(ConfigurationProvider provider,
                                                                             String section,
                                                                             String alias,
                                                                             StreamConfigParser.DeclaredStream declared) {
        return consumerSections(provider, section).map(consumers -> inertReasons(declared.config(), consumers))
                                                  .flatMap(reasons -> reasons.isEmpty()
                                                                      ? Result.success(declared)
                                                                      : new StreamDeclarationError.InertKeysRefused(alias,
                                                                                                                    reasons).result());
    }

    private static List<String> inertReasons(StreamConfig config, List<ConsumerConfig> consumers) {
        var streamKeys = Stream.concat(InertKeyRules.inertStreamKeys(config)
                                                    .stream(),
                                       InertKeyRules.unsupportedConsistency(config)
                                                    .stream());
        var consumerKeys = consumers.stream()
                                    .flatMap(consumer -> InertKeyRules.inertConsumerKeys(consumer)
                                                                      .stream());

        return Stream.concat(streamKeys, consumerKeys)
                     .map(InertKeyRules.InertKey::message)
                     .toList();
    }

    /// Every `[<section>.consumers.<group>]` the provider's file-backed layers name, read by
    /// [StreamConfigParser#parseConsumerSection].
    private static Result<List<ConsumerConfig>> consumerSections(ConfigurationProvider provider, String section) {
        var prefix = section + ".consumers.";
        var groups = provider.staticKeys()
                             .stream()
                             .filter(key -> key.startsWith(prefix))
                             .map(key -> key.substring(prefix.length()))
                             .filter(rest -> rest.indexOf('.') > 0)
                             .map(rest -> rest.substring(0, rest.indexOf('.')))
                             .distinct()
                             .sorted()
                             .map(group -> StreamConfigParser.parseConsumerSection(StreamSection.providerSection(provider,
                                                                                                                 prefix + group)))
                             .toList();

        return Result.allOf(groups);
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
                                                           warning.loud()
                                                           ? OperatorWarningCode.REPLICATION_FACTOR_BELOW_THREE
                                                           : OperatorWarningCode.REPLICATION_POLICY_WARNING,
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
