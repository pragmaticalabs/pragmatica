/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.backup.BackupService;
import org.pragmatica.aether.backup.BackupService.BackupInfo;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Promise;

import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

/// Routes for cluster backup management: trigger, list, restore.
public final class BackupRoutes implements RouteSource {
    private final Supplier<BackupService> backupServiceSupplier;

    private BackupRoutes(Supplier<BackupService> backupServiceSupplier) {
        this.backupServiceSupplier = backupServiceSupplier;
    }

    public static BackupRoutes backupRoutes(Supplier<BackupService> backupServiceSupplier) {
        return new BackupRoutes(backupServiceSupplier);
    }

    record BackupResponse(boolean success, String message) {
        static BackupResponse backupResponse(boolean success, String message) {
            return new BackupResponse(success, message);
        }
    }

    record RestoreRequest(String commit) {}

    @Override
    public Stream<Route<?>> routes() {
        return Stream.of(Route.<BackupResponse> post("/api/backup")
                              .toJson(this::triggerBackup),
                         Route.<List<BackupInfo>> get("/api/backups")
                              .toJson(this::listBackups),
                         Route.<BackupResponse> post("/api/backup/restore")
                              .withBody(RestoreRequest.class)
                              .toJson(this::restoreBackup));
    }

    private BackupResponse triggerBackup() {
        return backupServiceSupplier.get()
                                    .backupNow()
                                    .fold(cause -> BackupResponse.backupResponse(false,
                                                                                 cause.message()),
                                          _ -> BackupResponse.backupResponse(true, "Backup completed"));
    }

    private List<BackupInfo> listBackups() {
        return backupServiceSupplier.get()
                                    .listBackups()
                                    .or(List.of());
    }

    private Promise<BackupResponse> restoreBackup(RestoreRequest request) {
        return Promise.success(backupServiceSupplier.get()
                                                    .restore(request.commit())
                                                    .fold(cause -> BackupResponse.backupResponse(false,
                                                                                                 cause.message()),
                                                          _ -> BackupResponse.backupResponse(true, "Restore completed")));
    }
}
