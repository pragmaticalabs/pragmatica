// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.List;
import java.util.stream.Stream;

import org.pragmatica.aether.config.cluster.DiffAction;
import org.pragmatica.aether.config.cluster.DiffPlan;


/// One planned action of an apply and how far it got. `planned` is the number of nodes the action creates
/// when the plan alone determines it (`AddRole`, `ScaleUp`), 0 otherwise.
@SuppressWarnings("JBCT-UTIL-02")
public record ApplyAction(String key, int planned, Status status) {
    public enum Status {
        PENDING,
        IN_PROGRESS,
        DONE,
        FAILED
    }

    public static ApplyAction applyAction(String key, int planned, Status status) {
        return new ApplyAction(key, planned, status);
    }

    /// The plan's actions in the order the executor runs them (additions, modifications, removals), all PENDING.
    /// Immutable changes never reach the ledger: the plan is refused before an apply starts.
    public static List<ApplyAction> fromPlan(DiffPlan plan) {
        return Stream.of(plan.additions(),
                         plan.modifications(),
                         plan.removals())
                     .flatMap(List::stream)
                     .map(ApplyAction::pendingFor)
                     .toList();
    }

    private static ApplyAction pendingFor(DiffAction action) {
        return applyAction(actionKey(action), plannedNodes(action), Status.PENDING);
    }

    private static int plannedNodes(DiffAction action) {
        return switch (action) {
            case DiffAction.AddRole add -> add.count();
            case DiffAction.ScaleUp up -> up.to() - up.from();
            default -> 0;
        };
    }

    /// `kind|source|role|from->to`; fields an action does not have are empty.
    static String actionKey(DiffAction action) {
        return switch (action) {
            case DiffAction.AddSource a -> key("AddSource",
                                               a.sourceName().value(),
                                               "",
                                               "");
            case DiffAction.RemoveSource a -> key("RemoveSource",
                                                  a.sourceName().value(),
                                                  "",
                                                  "");
            case DiffAction.AddRole a -> key("AddRole",
                                             a.sourceName().value(),
                                             a.role().value(),
                                             "->" + a.count());
            case DiffAction.RemoveRole a -> key("RemoveRole",
                                                a.sourceName().value(),
                                                a.role().value(),
                                                a.count() + "->");
            case DiffAction.ScaleUp a -> key("ScaleUp",
                                             a.sourceName().value(),
                                             a.role().value(),
                                             a.from() + "->" + a.to());
            case DiffAction.ScaleDown a -> key("ScaleDown",
                                               a.sourceName().value(),
                                               a.role().value(),
                                               a.from() + "->" + a.to());
            case DiffAction.RuntimeChange a -> key("RuntimeChange",
                                                   a.sourceName().value(),
                                                   a.role().value(),
                                                   a.fromRuntime() + "->" + a.toRuntime());
            case DiffAction.RuntimeProfileChange a -> key("RuntimeProfileChange", a.profileName(), "", a.fields());
            case DiffAction.SourceFieldChange a -> key("SourceFieldChange",
                                                       a.sourceName().value(),
                                                       "",
                                                       a.field());
            case DiffAction.ClusterLevelChange a -> key("ClusterLevelChange",
                                                        "",
                                                        "",
                                                        a.field() + ":" + a.from() + "->" + a.to());
            case DiffAction.CommunityPlacementChange _ -> key("CommunityPlacementChange", "", "", "");
            case DiffAction.ImmutableFieldChange a -> key("ImmutableFieldChange", "", "", a.field());
        };
    }

    private static String key(String kind, String source, String role, String change) {
        return String.join("|", kind, source, role, change);
    }
}
