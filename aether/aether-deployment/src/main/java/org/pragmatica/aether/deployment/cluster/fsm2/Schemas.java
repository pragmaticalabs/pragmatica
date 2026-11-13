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
        if (value.status() != SchemaStatus.UNKNOWN) {
            readable(m, ctx, datasource, "the status decodes as " + value.status());
        }

        return switch (value.status()) {
            case PENDING -> dispatch(m, ctx, datasource);
            case COMPLETED -> Slices.retryHeld(m, ctx);
            case FAILED -> blocked(m, ctx, value);
            case MIGRATING -> m;
            case UNKNOWN -> unreadable(m, ctx, datasource);
        };
    }

    /// Every dispatch is stamped (`schemaDispatchedAt`): the stall sweep measures quiet time from the later of the record's `updatedAt` and
    /// this stamp, because a dispatch is an act on the record that its own commit (the lock Put) has not yet reached, and a restore keeps the
    /// backup's old `updatedAt`.
    private static ClusterModel dispatch(ClusterModel m, Ctx ctx, String datasource) {
        ctx.emit(new ClusterEffect.DispatchMigration(datasource));

        return stamp(m, ctx, datasource);
    }

    private static ClusterModel stamp(ClusterModel m, Ctx ctx, String datasource) {
        m.schemaDispatchedAt = m.schemaDispatchedAt.put(datasource, ctx.nowMs());

        return m;
    }

    /// #542: a FAILED record holds every slice of its owning blueprint, so the leader reports the consequence: which slices
    /// are held, and by whose migration.
    // Design-out: no transaction backs this event. It reports a FAILED record that is already committed (a fact in the mirror), so there is no
    // write to wait for. It is announced by the leader that is acting only: a suspended FSM indexes the record and announces nothing, as it
    // announces no decision of its own (R-5.7.1 (a)), but it remembers the datasource and announces it once on the resume (CI-16), when it
    // is still FAILED. Guarantee: a FAILED record committed in a witness-stale window is reported by the leader that is still this node;
    // mechanism: `withheldBlocked`, drained by `announceWithheld`. A deposed FSM (Deactivate) discards the set with the model.
    private static ClusterModel blocked(ClusterModel m, Ctx ctx, SchemaVersionValue value) {
        return Lifecycle.isSuspended(m, ctx)
               ? withhold(m, value.datasourceName())
               : announceBlocked(m, ctx, value);
    }

    private static ClusterModel withhold(ClusterModel m, String datasource) {
        m.withheldBlocked = m.withheldBlocked.add(datasource);

        return m;
    }

    /// CI-16 resume: the FAILED records committed while suspended are announced, once each, in datasource order.
    static ClusterModel announceWithheld(ClusterModel m, Ctx ctx) {
        var withheld = m.withheldBlocked.toList().stream().sorted().toList();

        m.withheldBlocked = PSet.empty(m.scans);
        withheld.forEach(datasource -> m.schemas.get(datasource)
                                                .filter(value -> value.status() == SchemaStatus.FAILED)
                                                .onPresent(value -> announceBlocked(m, ctx, value)));

        return m;
    }

    private static ClusterModel announceBlocked(ClusterModel m, Ctx ctx, SchemaVersionValue value) {
        var owner = value.owningBlueprint();
        var slices = slicesOwnedBy(m, owner);

        ctx.route(new RoutedEvent.ActivationBlocked(value.datasourceName(),
                                                    owner,
                                                    slices,
                                                    value.artifactCoords(),
                                                    value.attemptCount()));
        ctx.emit(new ClusterEffect.Audit("schema-activation-blocked",
                                         value.datasourceName() + " owner=" + owner.asString()));

        return m;
    }

    /// #964: a status this node cannot decode is neither completed nor failed nor pending. No action is taken.
    /// C12, the removal: a reported datasource whose record is removed is closed with the reason subject-gone (D.6 row 17).
    static ClusterModel onRemove(ClusterModel m, Ctx ctx, String datasource) {
        readable(m, ctx, datasource, Recoveries.gone(datasource, "its schema record was removed"));
        stallEnded(m, ctx, datasource, Recoveries.gone(datasource, "its schema record was removed"));
        m.schemaDispatchedAt = m.schemaDispatchedAt.remove(datasource);

        return Indexer.removeSchema(m, datasource);
    }

    /// D.6 row 17: a datasource reported `schema-status-unknown` closes its episode: `schema-status-readable`, same subject.
    private static ClusterModel readable(ClusterModel m, Ctx ctx, String datasource, String why) {
        if (m.schemaUnknownWarned.contains(datasource)) {
            m.schemaUnknownWarned = m.schemaUnknownWarned.remove(datasource);
            ctx.warn(Recoveries.SCHEMA_STATUS_READABLE,
                     datasource,
                     "Schema record for datasource " + datasource + ": " + why);
        }

        return m;
    }

    private static ClusterModel unreadable(ClusterModel m, Ctx ctx, String datasource) {
        // #2834: once per episode: an open warning, this leader's or inherited from the committed open set (`Warnings.inherit`), is not raised again.
        if (!m.schemaUnknownWarned.contains(datasource)) {
            m.schemaUnknownWarned = m.schemaUnknownWarned.add(datasource);
            ctx.warn("schema-status-unknown",
                     datasource,
                     "Schema record for datasource " + datasource + " carries a status this node cannot decode");
        }

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

    // -- #2773: the stall sweep ------------------------------------------------------------------------------------
    /// The longest a record legitimately stays PENDING with no live lock: the orchestrator writes PENDING and re-dispatches after a backoff
    /// of at most 45 s (a transient retry's `calculateBackoff(MAX_RETRIES - 1)` and the fenced-in-the-database cap, both pinned equal to this
    /// by `G2SchemaScenariosTest`). A held lock is a live attempt, so a record with a live lock is never stalled.
    long RETRY_CEILING_MS = 45_000L;
    /// Reconcile ticks of grace past the ceiling before a record is reported (the tick is the sweep's granularity).
    int STALL_GRACE_TICKS = 2;

    /// Step 2d of the reconcile: the level-triggered stall check. A PENDING or MIGRATING record with no live lock and no write or dispatch of
    /// this leader for longer than [#RETRY_CEILING_MS] plus the grace (with the default 30 s tick, 105 s) is stalled: nothing in this term re-dispatches it (a failed lock Put abandons the attempt without a
    /// retry). The leader reports it once per episode and closes the report when the condition ends. It dispatches and writes nothing:
    // Design-out: no recovery writer here. A MIGRATING reset in-term would multiply unfenced re-runs on datasources without a database fence
    // (`schema-migration-unfenced`); the rebuild's reset (C1) stays the only automatic one, and the warning names it. Guarantee: a stall is
    // reported within the ceiling + grace + one tick of its start by the leader that is acting; mechanism: the `FsmWarningKey` open set
    // (`Warnings`), which a successor inherits at `Rebuild`, so the report survives a leader change.
    static ClusterModel sweepStalls(ClusterModel m, Ctx ctx) {
        var stalled = m.schemas.values()
                               .stream()
                               .filter(value -> isStalled(m, ctx, value))
                               .sorted(Comparator.comparing(SchemaVersionValue::datasourceName))
                               .toList();
        var names = stalled.stream().map(SchemaVersionValue::datasourceName).collect(Collectors.toSet());

        stalled.forEach(value -> stallOpened(m, ctx, value));
        m.schemaStalledWarned.toList()
                             .stream()
                             .sorted()
                             .filter(datasource -> !names.contains(datasource))
                             .forEach(datasource -> stallEnded(m,
                                                               ctx,
                                                               datasource,
                                                               endedMessage(datasource)));

        return m;
    }

    private static boolean isStalled(ClusterModel m, Ctx ctx, SchemaVersionValue value) {
        return isUnsettled(value)
               && lockExpired(m, ctx, value.datasourceName())
               && isQuiet(m, ctx, value);
    }

    private static boolean isUnsettled(SchemaVersionValue value) {
        return value.status() == SchemaStatus.PENDING || value.status() == SchemaStatus.MIGRATING;
    }

    private static boolean isQuiet(ClusterModel m, Ctx ctx, SchemaVersionValue value) {
        return ctx.nowMs() - quietSince(m, value) > stallAfterMs(ctx);
    }

    /// When the record last moved. A record not yet reported moves with a write (`updatedAt`) or with this leader's dispatch or reset; a
    /// record already reported moves only with a dispatch or reset: a write that dispatches nothing and takes no lock (the operator's
    /// `POST /schema/migrate` on MIGRATING, "no orchestrator effect") is not progress and must not close the report.
    private static long quietSince(ClusterModel m, SchemaVersionValue value) {
        var dispatched = m.schemaDispatchedAt.get(value.datasourceName()).or(0L);

        return m.schemaStalledWarned.contains(value.datasourceName())
               ? dispatched
               : Math.max(value.updatedAt(), dispatched);
    }

    private static long stallAfterMs(Ctx ctx) {
        return RETRY_CEILING_MS + STALL_GRACE_TICKS * ctx.settings()
                                                         .timing()
                                                         .tick();
    }

    private static ClusterModel stallOpened(ClusterModel m, Ctx ctx, SchemaVersionValue value) {
        if (!m.schemaStalledWarned.contains(value.datasourceName())) {
            m.schemaStalledWarned = m.schemaStalledWarned.add(value.datasourceName());
            ctx.warn(Warnings.SCHEMA_STALLED, value.datasourceName(), stalledMessage(m, ctx, value));
        }

        return m;
    }

    private static ClusterModel stallEnded(ClusterModel m, Ctx ctx, String datasource, String why) {
        if (m.schemaStalledWarned.contains(datasource)) {
            m.schemaStalledWarned = m.schemaStalledWarned.remove(datasource);
            ctx.warn(Recoveries.SCHEMA_MIGRATION_PROGRESSED, datasource, why);
        }

        return m;
    }

    private static String endedMessage(String datasource) {
        return "Schema migration for datasource " + datasource
             + " progressed: its record has a live lock, was dispatched or reset by the leader, or left PENDING or MIGRATING";
    }

    /// The report names the datasource, the recorded status, the slices the record holds and the lever. The lever is chosen by the predicate
    /// the retry route uses (`SchemaStatus#canRetryManually`), so the warning never names a route that would refuse the record.
    private static String stalledMessage(ClusterModel m, Ctx ctx, SchemaVersionValue value) {
        return "Schema migration for datasource %s is stalled: status %s for %ds with no live migration lock; holds slices %s. %s".formatted(value.datasourceName(),
                                                                                                                                             value.status(),
                                                                                                                                             (ctx.nowMs() - value.updatedAt()) / 1000L,
                                                                                                                                             slicesOwnedBy(m,
                                                                                                                                                           value.owningBlueprint()),
                                                                                                                                             lever(ctx,
                                                                                                                                                   value));
    }

    /// The MIGRATING lever is a leadership change, because only a rebuild resets the record. `aether nodes shutdown <leader>` is the lever:
    /// the route marks the node SHUTTING_DOWN, `NodeDeploymentManager.onMembershipDecision` runs the shutdown callback (`AetherNode.stop`) and
    /// the leader leaves. `aether nodes drain` moves slices only and keeps the leader, so it is not named. The re-run is unfenced where
    /// `schema-migration-unfenced` applies: a migration still running there can run twice.
    private static String lever(Ctx ctx, SchemaVersionValue value) {
        return value.status()
                    .canRetryManually()
               ? "Recovery: aether schema retry " + value.datasourceName()
               : "No manual retry (the retry route refuses " + value.status()
                + "). Recovery: move leadership off the current leader " + ctx.self()
                                                                              .id()
                + " with `aether nodes shutdown " + ctx.self()
                                                       .id()
                + "`; the next leader's rebuild resets the record to PENDING and re-runs it (unfenced where schema-migration-unfenced applies:"
                + " a migration still running there can run twice)";
    }

    // -- C1: rebuild-time recovery ---------------------------------------------------------------------------------
    /// A PENDING record whose Put landed before this FSM was Active is lost to the live arm: re-dispatch it. A MIGRATING
    /// record whose lock is absent or expired died mid-flight: reset it to PENDING, which re-fires the live arm.
    static ClusterModel resumeMigrations(ClusterModel m, Ctx ctx) {
        m.schemas.entries()
                 .stream()
                 .sorted(Comparator.comparing(entry -> entry.getKey()))
                 .filter(entry -> entry.getValue()
                                       .status() == SchemaStatus.PENDING)
                 .forEach(entry -> dispatch(m,
                                            ctx,
                                            entry.getKey()));

        return resetStalledMigrations(m, ctx);
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

        return stamp(m, ctx, datasource);
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
