// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.validation;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.aether.deployment.validation.ConfigSectionPreflightValidator.SliceJar;
import org.pragmatica.aether.slice.topology.SliceTopology;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;


/// #1337: deploy-time refusal of a topic whose publisher and subscriber disagree about its delivery tier.
///
/// A subscriber whose handler takes the delivery context (`(T event, MessageContext context)`) needs the envelope only a DURABLE
/// topic carries. Each slice reads the durability of a topic from ITS OWN jar's `resources.toml`, so a publisher jar that
/// declares the topic ephemeral (or says nothing, which is the ephemeral default) and a subscriber jar that declares it durable
/// both compile and load; nothing before this check compared them, and the first delivery died in the subscriber's bridge.
///
/// The check runs over what the blueprint being published will load: for each publisher topic of each slice jar, its durability
/// is read from [ConfigSectionPreflightValidator#bindingView], the view the slice loader binds that slice's sections from (the
/// same view [ReplicationPreflight] reads), and topics are matched on the RESOLVED address, as [org.pragmatica.aether.slice.blueprint.PubSubValidator]
/// matches them. Every conflict is reported, not the first.
///
/// What it does not check: a publisher whose durability cannot be read (no [ConfigurationProvider] wired and a `resources.toml` that
/// does not parse) is skipped, "not checked" rather than "checked and passed", as in [ConfigSectionPreflightValidator] (a jar with no
/// `resources.toml` at all declares nothing, which the runtime reads as ephemeral, so that publisher IS checked); a publisher and a
/// subscriber in different blueprints never share an address, so they are never compared; and a subscriber that is deployed
/// later than a publisher that is already running is not seen here, only by the runtime refusal of
/// `TopicPublisher` (the subscription row's `tier`).
public sealed interface TopicTierPreflight {
    String DURABILITY_KEY = "durability";
    String DURABLE = "durable";

    static Result<Unit> validate(List<SliceJar> sliceJars, Option<ConfigurationProvider> nodeComposite) {
        var conflicts = ephemeralPublishers(sliceJars, nodeComposite).flatMap(publisher -> conflictsWith(publisher, sliceJars))
                                                                     .toList();

        return conflicts.isEmpty()
               ? Result.unitResult()
               : TopicTierMismatchRefused.FACTORY.apply(conflicts.stream()
                                                                 .map(Conflict::describe)
                                                                 .collect(Collectors.joining("; ")))
                                                 .result();
    }

    /// Every topic a slice jar publishes that is positively known to be ephemeral.
    private static Stream<EphemeralPublisher> ephemeralPublishers(List<SliceJar> sliceJars,
                                                                  Option<ConfigurationProvider> nodeComposite) {
        return sliceJars.stream()
                        .flatMap(sliceJar -> ConfigSectionPreflightValidator.bindingView(sliceJar, nodeComposite)
                                                                            .map(view -> sliceJar.topologies()
                                                                                                 .stream()
                                                                                                 .flatMap(topology -> ephemeralPublishers(topology,
                                                                                                                                          view)))
                                                                            .or(Stream.empty()));
    }

    private static Stream<EphemeralPublisher> ephemeralPublishers(SliceTopology topology, ConfigurationProvider view) {
        return topology.publishes()
                       .stream()
                       .filter(publisher -> !isDurable(view, publisher.config()))
                       .map(publisher -> new EphemeralPublisher(topology.sliceName(),
                                                                publisher.config(),
                                                                publisher.address()));
    }

    private static boolean isDurable(ConfigurationProvider view, String section) {
        return view.getString(section + "." + DURABILITY_KEY)
                   .filter(value -> DURABLE.equalsIgnoreCase(value.trim()))
                   .isPresent();
    }

    private static Stream<Conflict> conflictsWith(EphemeralPublisher publisher, List<SliceJar> sliceJars) {
        return sliceJars.stream()
                        .flatMap(sliceJar -> sliceJar.topologies()
                                                     .stream())
                        .flatMap(topology -> topology.subscribes()
                                                     .stream()
                                                     .filter(SliceTopology.TopicSub::contextual)
                                                     .filter(subscriber -> subscriber.address()
                                                                                     .equals(publisher.address()))
                                                     .map(subscriber -> new Conflict(publisher,
                                                                                     topology.sliceName(),
                                                                                     subscriber.method())));
    }

    record EphemeralPublisher(String slice, String section, String address) {}

    record Conflict(EphemeralPublisher publisher, String subscriberSlice, String method) {
        String describe() {
            return "topic " + publisher.address()
                   + " is published on the EPHEMERAL tier by " + publisher.slice()
                   + " (section [" + publisher.section()
                   + "] declares no durability = \"durable\") but subscribed by " + subscriberSlice
                   + "." + method
                   + ", whose handler takes a MessageContext and needs a durable topic";
        }
    }

    /// The deploy is refused: at least one context-taking subscriber sits on a topic an ephemeral publisher publishes.
    record TopicTierMismatchRefused(String conflicts, String message) implements Cause {
        public static final Fn1<TopicTierMismatchRefused, String> FACTORY = Causes.forOneValue("Deploy refused, topic tier mismatch: %s. Declare the topic durable in the publisher slice's resources.toml, or drop the MessageContext parameter from the subscriber",
                                                                                               TopicTierMismatchRefused::new);
    }

    record unused() implements TopicTierPreflight {}
}
