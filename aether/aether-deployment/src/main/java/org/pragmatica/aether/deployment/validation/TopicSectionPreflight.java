// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.validation;

import java.util.stream.Stream;

import org.pragmatica.aether.deployment.validation.ConfigSectionPreflightValidator.SliceJar;
import org.pragmatica.aether.resource.TopicConfig;
import org.pragmatica.aether.slice.topology.SliceTopology;
import org.pragmatica.config.ConfigError;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.ProviderBasedConfigService;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;


/// #811: deploy-time binding of every topic section a slice publishes to or subscribes from.
///
/// A topic section is bound at slice activation by `ProviderBasedConfigService.config(section, TopicConfig.class)`,
/// which refuses an unknown or dashed key (`@StrictKeys`, #738), a stream knob on an ephemeral topic and a malformed
/// durable declaration. Before this check the deploy only looked at generic resource sections, so the operator
/// learned of the typo when the slice activated, node by node. This check runs the SAME binder over the SAME view
/// ([ConfigSectionPreflightValidator#bindingView], the view the slice loader binds that slice's sections from), so a
/// refusal here equals the refusal activation would give, and activation keeps its own refusal as the backstop.
///
/// A section the view does not hold is not refused: an absent topic section provisions an ephemeral topic named after
/// the section (#396, `SpiResourceProvider.topicNameFallback`). Like [ConfigSectionPreflightValidator], a slice whose
/// view cannot be built (no node composite and no parseable jar `resources.toml`) is "not checked", not "checked and
/// passed". Declaration checks that need the cluster's replication defaults stay with [ReplicationPreflight].
public sealed interface TopicSectionPreflight {
    /// One check per distinct topic section of the slice jar, aggregated by the caller.
    static Stream<Result<Unit>> check(SliceJar sliceJar, Option<ConfigurationProvider> nodeComposite) {
        return ConfigSectionPreflightValidator.bindingView(sliceJar, nodeComposite)
                                              .map(view -> topicSections(sliceJar).map(section -> bindSection(section,
                                                                                                              view)))
                                              .or(Stream.empty());
    }

    private static Stream<TopicSection> topicSections(SliceJar sliceJar) {
        return sliceJar.topologies()
                       .stream()
                       .flatMap(TopicSectionPreflight::sectionsOf)
                       .distinct();
    }

    private static Stream<TopicSection> sectionsOf(SliceTopology topology) {
        var published = topology.publishes()
                                .stream()
                                .map(SliceTopology.TopicPub::config);
        var subscribed = topology.subscribes()
                                 .stream()
                                 .map(SliceTopology.TopicSub::config);

        return Stream.concat(published, subscribed).map(config -> new TopicSection(topology.sliceName(), config));
    }

    private static Result<Unit> bindSection(TopicSection section, ConfigurationProvider view) {
        return ProviderBasedConfigService.providerBasedConfigService(view)
                                         .config(section.name(), TopicConfig.class)
                                         .fold(cause -> refusal(section, cause), _ -> Result.unitResult());
    }

    /// Design-out, not an absorbed failure: an absent section is a legal declaration that provisions an ephemeral
    /// topic (#396), the fallback `SpiResourceProvider.topicNameFallback` applies at activation, so there is nothing
    /// to refuse. Every other bind failure is the one activation would raise.
    private static Result<Unit> refusal(TopicSection section, Cause cause) {
        return switch (cause) {
            case ConfigError.SectionNotFound _ -> Result.unitResult();
            default -> new TopicSectionRefused(section.slice(), section.name(), cause).result();
        };
    }

    record TopicSection(String slice, String name) {}

    /// A topic section the deploy refuses; `cause` is the binder's typed [ConfigError] (or the [TopicConfig] factory's
    /// refusal), with its key and suggestion in the message.
    record TopicSectionRefused(String slice, String section, Cause cause) implements Cause {
        @Override
        public String message() {
            return "Slice " + slice + ": topic section [" + section + "] refused: " + cause.message();
        }
    }

    record unused() implements TopicSectionPreflight {}
}
