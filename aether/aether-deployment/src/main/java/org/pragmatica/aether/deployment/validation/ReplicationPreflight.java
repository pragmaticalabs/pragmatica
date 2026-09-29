// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.validation;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.pragmatica.aether.deployment.validation.ConfigSectionPreflightValidator.SliceJar;
import org.pragmatica.aether.slice.ReplicationContext;
import org.pragmatica.aether.slice.ReplicationDeclaration;
import org.pragmatica.aether.slice.ReplicationFactorsError;
import org.pragmatica.aether.slice.topology.SliceTopology;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.parse.Number;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// #1564: deploy-time replication validation for the two resource kinds declared in SLICE jars — durable entities
/// (`@ResourceQualifier(type = DurableEntity.class, ...)`) and durable topics published by a slice. Each section's
/// `replication_factor`/`confirmation_factor` is read from the view the slice loader would bind it from
/// ([ConfigSectionPreflightValidator#loaderView]) and resolved through [ReplicationContext#resolve] — the same
/// resolution the resource factories apply at activation — so a declaration activation would refuse fails the
/// deploy with its typed cause, and the warnings it raises reach the operator in the deploy response. Streams are
/// validated by [StreamResourceValidator] over the blueprint's own `resources.toml`.
///
/// A section the view does not hold is not reported here: [ConfigSectionPreflightValidator] refuses it for an
/// entity, and an absent topic section provisions an ephemeral topic, which has no factors.
public sealed interface ReplicationPreflight {
    String DURABLE_ENTITY_TYPE = "DurableEntity";
    String DURABILITY_KEY = "durability";
    String DURABLE = "durable";

    /// The declaration's warnings, or the first refusal per section aggregated across the blueprint.
    static Result<List<StreamValidationWarning>> validate(List<SliceJar> sliceJars,
                                                          Option<ConfigurationProvider> nodeComposite,
                                                          ReplicationContext context) {
        var checks = sliceJars.stream()
                              .flatMap(sliceJar -> checkSliceJar(sliceJar, nodeComposite, context))
                              .toList();

        return Result.allOf(checks).map(perSection -> perSection.stream().flatMap(List::stream).toList());
    }

    private static Stream<Result<List<StreamValidationWarning>>> checkSliceJar(SliceJar sliceJar,
                                                                               Option<ConfigurationProvider> nodeComposite,
                                                                               ReplicationContext context) {
        return ConfigSectionPreflightValidator.bindingView(sliceJar, nodeComposite)
                                              .map(view -> sliceJar.topologies()
                                                                   .stream()
                                                                   .flatMap(topology -> declaredSections(topology,
                                                                                                         view))
                                                                   .map(section -> checkSection(section, view, context)))
                                              .or(Stream.empty());
    }

    /// Every durable-entity section and every durably published topic section the topology declares.
    private static Stream<DeclaredSection> declaredSections(SliceTopology topology, ConfigurationProvider view) {
        var entities = topology.resources()
                               .stream()
                               .filter(resource -> DURABLE_ENTITY_TYPE.equals(resource.type()))
                               .map(resource -> new DeclaredSection(topology.sliceName(), "entity", resource.config()));
        var topics = topology.publishes()
                             .stream()
                             .map(SliceTopology.TopicPub::config)
                             .filter(section -> isDurableTopic(view, section))
                             .map(section -> new DeclaredSection(topology.sliceName(), "durable topic", section));

        return Stream.concat(entities, topics).distinct();
    }

    private static boolean isDurableTopic(ConfigurationProvider view, String section) {
        return view.getString(section + "." + DURABILITY_KEY)
                   .filter(value -> DURABLE.equalsIgnoreCase(value.trim()))
                   .isPresent();
    }

    private static Result<List<StreamValidationWarning>> checkSection(DeclaredSection section,
                                                                      ConfigurationProvider view,
                                                                      ReplicationContext context) {
        return Result.all(declared(view, section.name(), ReplicationDeclaration.FACTOR_KEY),
                          declared(view, section.name(), ReplicationDeclaration.CONFIRMATION_KEY))
                     .map(ReplicationDeclaration::replicationDeclaration)
                     .flatMap(context::resolve)
                     .mapError(cause -> new ReplicationPreflightFailure(section.slice(), section.kind(), section.name(), cause))
                     .map(resolved -> resolved.warnings()
                                              .stream()
                                              .map(warning -> StreamValidationWarning.streamValidationWarning("[" + section.name() + "]",
                                                                                                             warning.code(),
                                                                                                             warning.message(section.kind() + " section '"
                                                                                                                             + section.name() + "' of slice "
                                                                                                                             + section.slice(),
                                                                                                                             resolved.factors())))
                                              .toList());
    }

    private static Result<Option<Integer>> declared(ConfigurationProvider view, String section, String key) {
        return view.getString(section + "." + key)
                   .map(raw -> parsed(key, raw.trim()))
                   .or(Result.success(none()));
    }

    private static Result<Option<Integer>> parsed(String key, String raw) {
        return Number.parseInt(raw)
                     .map(value -> some(value))
                     .mapError(_ -> new ReplicationFactorsError.NotAnInteger(key, raw));
    }

    record DeclaredSection(String slice, String kind, String name) {}

    /// A slice-jar section whose replication declaration the deploy refuses; `cause` is the typed
    /// [ReplicationFactorsError].
    record ReplicationPreflightFailure(String slice, String kind, String section, Cause cause) implements Cause {
        @Override
        public String message() {
            return "Slice " + slice + ": " + kind + " section [" + section + "] replication refused: " + cause.message();
        }
    }

    record unused() implements ReplicationPreflight {}
}
