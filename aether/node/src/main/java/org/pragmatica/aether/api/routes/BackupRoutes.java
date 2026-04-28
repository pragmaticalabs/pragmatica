// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.api.OperationalEvent;
import org.pragmatica.aether.backup.BackupService;
import org.pragmatica.aether.backup.BackupService.BackupInfo;
import org.pragmatica.aether.http.security.AuditLog;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Promise;

import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;


public final class BackupRoutes implements RouteSource {
    private final Supplier<BackupService> backupServiceSupplier;
    private final Supplier<ManageableNode> nodeSupplier;

    private BackupRoutes(Supplier<BackupService> backupServiceSupplier, Supplier<ManageableNode> nodeSupplier) {
        this.backupServiceSupplier = backupServiceSupplier;
        this.nodeSupplier = nodeSupplier;
    }

    public static BackupRoutes backupRoutes(Supplier<BackupService> backupServiceSupplier,
                                            Supplier<ManageableNode> nodeSupplier) {
        return new BackupRoutes(backupServiceSupplier, nodeSupplier);
    }

    record BackupResponse(boolean success, String message) {
        static BackupResponse backupResponse(boolean success, String message) {
            return new BackupResponse(success, message);
        }
    }

    record RestoreRequest(String commit){}

    @Override public Stream<Route<?>> routes() {
        return Stream.of(ManagementRoutes.<BackupResponse>route(ManagementRoute.BACKUP_TRIGGER)
                                         .toJson(this::triggerBackup),
                         ManagementRoutes.<List<BackupInfo>>route(ManagementRoute.BACKUPS_LIST)
                                         .toJson(this::listBackups),
                         ManagementRoutes.<BackupResponse>route(ManagementRoute.BACKUP_RESTORE)
                                         .withBody(RestoreRequest.class)
                                         .toJson(this::restoreBackup));
    }

    private BackupResponse triggerBackup() {
        var response = backupServiceSupplier.get().backupNow()
                                                .fold(cause -> BackupResponse.backupResponse(false,
                                                                                             cause.message()),
                                                      _ -> BackupResponse.backupResponse(true, "Backup completed"));
        AuditLog.backupCreated(response.success(), response.message());
        emitBackupCreated(response);
        return response;
    }

    private void emitBackupCreated(BackupResponse response) {
        if (response.success()) {nodeSupplier.get().route(OperationalEvent.BackupCreated.backupCreated("latest", "api"));}
    }

    private List<BackupInfo> listBackups() {
        return backupServiceSupplier.get().listBackups()
                                        .or(List.of());
    }

    private Promise<BackupResponse> restoreBackup(RestoreRequest request) {
        var response = backupServiceSupplier.get().restore(request.commit())
                                                .fold(cause -> BackupResponse.backupResponse(false,
                                                                                             cause.message()),
                                                      _ -> BackupResponse.backupResponse(true, "Restore completed"));
        AuditLog.backupRestored(response.success(), request.commit(), response.message());
        emitBackupRestored(request.commit(), response);
        return Promise.success(response);
    }

    private void emitBackupRestored(String commitId, BackupResponse response) {
        if (response.success()) {nodeSupplier.get()
                                                 .route(OperationalEvent.BackupRestored.backupRestored(commitId, "api"));}
    }
}
