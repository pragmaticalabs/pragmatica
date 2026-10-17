// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.List;

import org.pragmatica.aether.deployment.validation.StreamValidationFailure;
import org.pragmatica.aether.deployment.validation.StreamValidationWarning;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;


/// #1336 — what a publish answers with: the stored blueprint and the `[streams.*]` declarations the
/// publish did NOT bind, each named by field and rule. The rejections are deploy-response data, not
/// blueprint state — [ExpandedBlueprint] is the replicated KV record and must not carry them. A slice
/// that uses a rejected alias fails at load naming that alias; the operator learns why here, at the
/// point where it is actionable.
///
/// #1564: `warnings` are the deploy-time warnings — the stream validation warnings and the replication warnings
/// the declarations raised (owner ruling: LOUD). Deploy-response data too, reported to the operator here.
public record PublishedBlueprint(ExpandedBlueprint blueprint,
                                 List<StreamValidationFailure> rejectedStreamBindings,
                                 List<StreamValidationWarning> warnings) {
    public PublishedBlueprint {
        rejectedStreamBindings = List.copyOf(rejectedStreamBindings);
        warnings = List.copyOf(warnings);
    }

    public static PublishedBlueprint publishedBlueprint(ExpandedBlueprint blueprint,
                                                        List<StreamValidationFailure> rejectedStreamBindings,
                                                        List<StreamValidationWarning> warnings) {
        return new PublishedBlueprint(blueprint, rejectedStreamBindings, warnings);
    }
}
