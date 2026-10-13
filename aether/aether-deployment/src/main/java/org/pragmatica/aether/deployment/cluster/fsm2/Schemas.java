// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.TxDraft;
import org.pragmatica.aether.slice.blueprint.Blueprint;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.BlueprintParser;
import org.pragmatica.aether.slice.blueprint.DeploymentConfig;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SchemaVersionKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaVersionValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.lang.Option;


/// Schema records (C12) and the schema half of the activation gate (R-3.3.5): a PENDING, MIGRATING or FAILED record of
/// a datasource owned by the slice's blueprint holds the slice in LOADED, and a COMPLETED record releases it.
public sealed interface Schemas {
    Set<SchemaStatus> BLOCKING = Set.of(SchemaStatus.PENDING, SchemaStatus.MIGRATING, SchemaStatus.FAILED);

    // -- C12: SchemaVersionPut -------------------------------------------------------------------------------------
    static ClusterModel onPut(ClusterModel m, Ctx ctx, String datasource, SchemaVersionValue value) {
        Indexer.indexSchema(m, datasource, value);

        return switch (value.status()) {
            case PENDING -> dispatch(m, ctx, datasource);
            case COMPLETED -> Slices.retryHeld(m, ctx);
            case FAILED -> blocked(m, ctx, value);
            case MIGRATING -> m;
            case UNKNOWN -> unreadable(m, ctx, datasource);
        };
    }

    private static ClusterModel dispatch(ClusterModel m, Ctx ctx, String datasource) {
        ctx.emit(new ClusterEffect.DispatchMigration(datasource));

        return m;
    }

    /// #542: a FAILED record holds every slice of its owning blueprint, so the leader reports the consequence: which slices
    /// are held, and by whose migration.
    private static ClusterModel blocked(ClusterModel m, Ctx ctx, SchemaVersionValue value) {
        var owner = value.owningBlueprint();
        var slices = slicesOwnedBy(m, owner);

        ctx.route(new RoutedEvent.ActivationBlocked(value.datasourceName(), owner.asString(), slices));
        ctx.emit(new ClusterEffect.Audit("schema-activation-blocked",
                                         value.datasourceName() + " owner=" + owner.asString()));

        return m;
    }

    /// #964: a status this node cannot decode is neither completed nor failed nor pending. No action is taken.
    private static ClusterModel unreadable(ClusterModel m, Ctx ctx, String datasource) {
        ctx.warn("schema-status-unknown",
                 datasource,
                 "Schema record for datasource " + datasource + " carries a status this node cannot decode");

        return m;
    }

    private static List<String> slicesOwnedBy(ClusterModel m, BlueprintId owner) {
        return m.targets.entries()
                        .stream()
                        .filter(entry -> entry.getValue()
                                              .owningBlueprint()
                                              .map(owned -> owned.base()
                                                                 .equals(owner.base()))
                                              .or(false))
                        .map(entry -> entry.getKey()
                                           .withVersion(entry.getValue().currentVersion())
                                           .asString())
                        .sorted()
                        .toList();
    }

    // -- C1: rebuild-time recovery ---------------------------------------------------------------------------------
    /// A PENDING record whose Put landed before this FSM was Active is lost to the live arm: re-dispatch it. A MIGRATING
    /// record whose lock is absent or expired died mid-flight: reset it to PENDING, which re-fires the live arm.
    static ClusterModel resumeMigrations(ClusterModel m, Ctx ctx) {
        m.schemas.entries()
                 .stream()
                 .sorted(Comparator.comparing(entry -> entry.getKey()))
                 .forEach(entry -> recoverOne(m,
                                              ctx,
                                              entry.getKey(),
                                              entry.getValue()));

        return m;
    }

    /// The reset half of the recovery alone: it is a write, so a leader that was suspended when it ran at Rebuild runs it again on the
    /// resume (CI-16). The dispatch half is an effect and cannot be lost to a suspension.
    static ClusterModel resetStalledMigrations(ClusterModel m, Ctx ctx) {
        m.schemas.entries()
                 .stream()
                 .sorted(Comparator.comparing(entry -> entry.getKey()))
                 .filter(entry -> entry.getValue()
                                       .status() == SchemaStatus.MIGRATING && lockExpired(m,
                                                                                          ctx,
                                                                                          entry.getKey()))
                 .forEach(entry -> resetStalled(m,
                                                entry.getKey(),
                                                entry.getValue(),
                                                ctx));

        return m;
    }

    private static ClusterModel recoverOne(ClusterModel m, Ctx ctx, String datasource, SchemaVersionValue value) {
        if (value.status() == SchemaStatus.PENDING) {
            dispatch(m, ctx, datasource);
        } else if (value.status() == SchemaStatus.MIGRATING && lockExpired(m, ctx, datasource)) {
            resetStalled(m, datasource, value, ctx);
        }

        return m;
    }

    private static boolean lockExpired(ClusterModel m, Ctx ctx, String datasource) {
        return m.schemaLocks.get(datasource)
                            .map(lock -> lock.expiresAt() < ctx.nowMs())
                            .or(true);
    }

    /// The lock is not removed here (#806): this runs only for an expired lock, which the next acquire takes over.
    private static ClusterModel resetStalled(ClusterModel m, String datasource, SchemaVersionValue value, Ctx ctx) {
        var reset = new SchemaVersionValue(datasource,
                                           value.currentVersion(),
                                           value.lastMigration(),
                                           SchemaStatus.PENDING,
                                           value.artifactCoords(),
                                           value.owningBlueprint(),
                                           value.attemptCount(),
                                           ctx.nowMs());
        var key = new SchemaVersionKey(datasource);
        var mutation = new KVCommand.Mutation<AetherKey, AetherValue>(key,
                                                                      Option.some((AetherValue) value),
                                                                      Option.some((AetherValue) reset));

        Outbox.direct(m,
                      TxDraft.draft(SubmitKind.SCHEMA, key, List.of(mutation)));

        return m;
    }

    // -- the gate --------------------------------------------------------------------------------------------------
    /// The schema records that hold `artifact`: records of a blocking status, owned by the artifact's committed owner
    /// (matched on blueprint base), when that owner requires a schema (`schemaRequired`, default true).
    static List<SchemaVersionValue> blocking(ClusterModel m, Artifact artifact) {
        return owner(m, artifact).filter(blueprint -> schemaRequired(m, blueprint))
                    .map(blueprint -> m.schemas.values()
                                               .stream()
                                               .filter(schema -> BLOCKING.contains(schema.status()))
                                               .filter(schema -> schema.owningBlueprint()
                                                                       .base()
                                                                       .equals(blueprint.base()))
                                               .sorted(Comparator.comparing(SchemaVersionValue::datasourceName))
                                               .toList())
                    .or(List.of());
    }

    /// The committed owner of the slice: the `SliceTargetValue` record is the ownership authority (#805 item 1).
    static Option<BlueprintId> owner(ClusterModel m, Artifact artifact) {
        return m.targets.get(artifact.base())
                        .flatMap(target -> target.owningBlueprint());
    }

    /// The blueprint's declared `schema_required`, from its resources config; true when it cannot be resolved.
    static boolean schemaRequired(ClusterModel m, BlueprintId owner) {
        return m.blueprints.get(owner)
                           .flatMap(value -> value.blueprint()
                                                  .resourcesConfig())
                           .flatMap(toml -> BlueprintParser.parse(toml).option())
                           .flatMap(Blueprint::deploymentConfig)
                           .map(DeploymentConfig::schemaRequired)
                           .or(true);
    }

    /// A stable signature of a hold, so a repeated observation of the same hold warns once (#760).
    static String signature(List<SchemaVersionValue> records) {
        return records.stream()
                      .sorted(Comparator.comparing(SchemaVersionValue::datasourceName))
                      .map(record -> record.datasourceName() + "=" + record.status())
                      .collect(Collectors.joining(", "));
    }

    record unused() implements Schemas {}
}
