// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.utility.ULID;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Result.success;


/// The apply ledger, schema 2 (cluster-apply spec 4.2). The format is replaced, not migrated: a file of any other
/// schema is refused by name and left untouched.
@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02"})
public record ApplyState(String applyId,
                         ClusterName clusterName,
                         String configHash,
                         long storedVersion,
                         String startedAt,
                         ApplyPhase phase,
                         List<ApplyAction> actions,
                         List<ApplyResource> resources,
                         List<ApplyReservation> reservations,
                         List<CreatedResource> createdResources,
                         List<String> destroyedNodeIds) {
    public static final int SCHEMA_VERSION = 2;
    /// A lowercase ULID: inside every provider's label-value grammar (Hetzner `[a-zA-Z0-9_.-]`, 63 characters).
    private static final Pattern APPLY_ID = Pattern.compile("^[0-9a-z]{26}$");

    public ApplyState {
        actions = List.copyOf(actions);
        resources = List.copyOf(resources);
        reservations = List.copyOf(reservations);
        createdResources = List.copyOf(createdResources);
        destroyedNodeIds = List.copyOf(destroyedNodeIds);
    }

    public static ApplyState applyState(String applyId,
                                        ClusterName clusterName,
                                        String configHash,
                                        long storedVersion,
                                        String startedAt,
                                        ApplyPhase phase,
                                        List<ApplyAction> actions,
                                        List<ApplyResource> resources,
                                        List<ApplyReservation> reservations,
                                        List<CreatedResource> createdResources,
                                        List<String> destroyedNodeIds) {
        return new ApplyState(applyId,
                              clusterName,
                              configHash,
                              storedVersion,
                              startedAt,
                              phase,
                              actions,
                              resources,
                              reservations,
                              createdResources,
                              destroyedNodeIds);
    }

    /// A fresh apply with no resources yet. `storedVersion` is the cluster's config version the plan was read at
    /// (0 until the commit step supplies it).
    public static ApplyState startedApply(ClusterName clusterName,
                                          String configHash,
                                          long storedVersion,
                                          String startedAt,
                                          List<ApplyAction> actions) {
        return applyState(newApplyId(),
                          clusterName,
                          configHash,
                          storedVersion,
                          startedAt,
                          ApplyPhase.RUNNING,
                          actions,
                          List.of(),
                          List.of(),
                          List.of(),
                          List.of());
    }

    public static String newApplyId() {
        return ULID.ulid().encoded();
    }

    static boolean isApplyId(String candidate) {
        return APPLY_ID.matcher(candidate).matches();
    }

    public ApplyState withPhase(ApplyPhase newPhase) {
        return applyState(applyId,
                          clusterName,
                          configHash,
                          storedVersion,
                          startedAt,
                          newPhase,
                          actions,
                          resources,
                          reservations,
                          createdResources,
                          destroyedNodeIds);
    }

    /// Records a VM row: replaces the row of the same node id (a state change), or appends (a new node).
    public ApplyState withNode(ApplyResource row) {
        var updated = new ArrayList<>(resources.stream()
                                               .filter(existing -> !existing.nodeId()
                                                                            .equals(row.nodeId()))
                                               .toList());

        updated.add(row);

        return applyState(applyId,
                          clusterName,
                          configHash,
                          storedVersion,
                          startedAt,
                          phase,
                          actions,
                          updated,
                          reservations,
                          createdResources,
                          destroyedNodeIds);
    }

    public ApplyState withResource(CreatedResource resource) {
        var updated = new ArrayList<>(createdResources);

        updated.add(resource);

        return applyState(applyId,
                          clusterName,
                          configHash,
                          storedVersion,
                          startedAt,
                          phase,
                          actions,
                          resources,
                          reservations,
                          updated,
                          destroyedNodeIds);
    }

    public ApplyState withDestroyedNode(String nodeId) {
        var updated = new ArrayList<>(destroyedNodeIds);

        updated.add(nodeId);

        return applyState(applyId,
                          clusterName,
                          configHash,
                          storedVersion,
                          startedAt,
                          phase,
                          actions,
                          resources,
                          reservations,
                          createdResources,
                          updated);
    }

    private static final Path AETHER_DIR = Path.of(System.getProperty("user.home"), ".aether", "clusters");
    /// Where one cluster's apply state lives. Every read, write and delete resolves through here, which is
    /// the seam a test replaces to prove a code path never touches the state store (same pattern as
    /// `ClusterTargetMixin#registryLoader`).
    static Function<ClusterName, Path> stateDirResolver = name -> AETHER_DIR.resolve(name.value());
    private static final String STATE_FILE_NAME = "apply-state.json";

    /// Durable and atomic (see [ApplyLedger]); the result MUST be acted on: a ledger that was not written is a
    /// ledger that does not exist, and the caller has to stop before creating anything it cannot account for.
    static Result<Unit> save(ApplyState state) {
        return ApplyLedger.write(stateDirResolver.apply(state.clusterName()),
                                 STATE_FILE_NAME,
                                 toJson(state));
    }

    /// ABSENT and UNREADABLE are different facts, as for the bootstrap ledger
    /// (`BootstrapStatePersistence#read`): absent is `success(none())`, present and parsed is
    /// `success(some(state))`, present but refused (torn file, other schema, unknown resource type, blank id) is a
    /// FAILURE carrying the parse cause. A caller that would otherwise treat "unreadable" as "none" and
    /// then write over the file (an older CLI erasing a newer CLI's ledger) must refuse instead.
    static Result<Option<ApplyState>> read(ClusterName clusterName) {
        var path = stateFilePath(clusterName);

        if (!Files.exists(path)) {
            return success(none());
        }

        return Result.lift(PersistenceError::new,
                           () -> Files.readString(path))
                     .flatMap(ApplyState::fromJson)
                     .map(Option::some);
    }

    static Result<Unit> delete(ClusterName clusterName) {
        return Result.lift(PersistenceError::new, () -> doDelete(clusterName));
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Unit doDelete(ClusterName clusterName) throws Exception {
        var path = stateFilePath(clusterName);

        Files.deleteIfExists(path);

        return Unit.unit();
    }

    private static Path stateFilePath(ClusterName clusterName) {
        return stateDirResolver.apply(clusterName)
                               .resolve(STATE_FILE_NAME);
    }

    static String toJson(ApplyState state) {
        return ApplyStateJson.toJson(state);
    }

    static Result<ApplyState> fromJson(String json) {
        return ApplyStateJson.fromJson(json);
    }

    record PersistenceError(Throwable cause) implements Cause {
        @Override
        public String message() {
            return "Apply state persistence error: " + cause.getMessage();
        }
    }

    record ParseError(Throwable cause) implements Cause {
        @Override
        public String message() {
            return "Failed to parse apply state JSON: " + cause.getMessage();
        }
    }
}
