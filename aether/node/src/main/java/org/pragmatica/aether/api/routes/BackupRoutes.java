// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.util.function.Supplier;
import java.util.stream.Stream;

import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.node.backup.BackupGenesis;
import org.pragmatica.aether.node.backup.BackupGenesis.GenesisDeclared;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.HttpStatusAware;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;


/// #1532 — `POST /backup/declare-genesis` (ADMIN, leader): make this cluster's state the KV backup head
/// in place of a backup of another lineage. See [BackupGenesis] for what it refuses and why.
public final class BackupRoutes implements RouteSource {
    private final Supplier<ManageableNode> nodeSupplier;

    private BackupRoutes(Supplier<ManageableNode> nodeSupplier) {
        this.nodeSupplier = nodeSupplier;
    }

    public static BackupRoutes backupRoutes(Supplier<ManageableNode> nodeSupplier) {
        return new BackupRoutes(nodeSupplier);
    }

    @Override
    public Stream<Route<?>> routes() {
        return Stream.of(ManagementRoutes.<GenesisDeclared> route(ManagementRoute.BACKUP_DECLARE_GENESIS).toJson(_ -> declareGenesis()));
    }

    Promise<GenesisDeclared> declareGenesis() {
        var node = nodeSupplier.get();

        return node.backupGenesis()
                   .map(genesis -> genesis.declare(node::<Object> apply))
                   .or(BackupRouteError.BACKUP_NOT_ENABLED::promise);
    }

    enum BackupRouteError implements Cause, HttpStatusAware {
        BACKUP_NOT_ENABLED("The KV backup is not enabled on this node ([backup] enabled = true with a path)");
        private final String message;
        BackupRouteError(String message) {
            this.message = message;
        }
        @Override
        public String message() {
            return message;
        }
        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.CONFLICT;
        }
    }
}
