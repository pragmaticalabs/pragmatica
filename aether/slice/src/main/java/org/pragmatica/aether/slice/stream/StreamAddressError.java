// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.stream;

import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.utils.Causes;


/// A declared stream alias could not be resolved to the catalog address its blueprint was deployed
/// under (#1040).
///
/// WHY THESE FAIL RATHER THAN FALL BACK, which is the whole point of the type. #1040 fixed a SILENT
/// MERGE — separate blueprints sharing one ring because the engine keyed by the bare section name.
/// Qualification turns that into a SILENT SPLIT for anyone who relied on it: the consumer polls a ring
/// no producer writes to, forever, with no error anywhere. Trading a silent merge for a silent split
/// is not a fix, so an alias that cannot be resolved fails where an operator sees it instead of
/// quietly reverting to the bare spelling that caused the defect.
///
/// This is classified FATAL rather than intermittent, and that rests on an ordering fact rather than
/// on optimism: both `BlueprintService` publish paths — `buildAllCommands` (artifact) and
/// `storeBlueprintWithKey` (TOML body, since #1066) — put `BlueprintStreamBindingsKey` in the SAME
/// consensus batch as the blueprint itself, and the `SliceTargetKey` that triggers deployment is
/// written strictly later. A node applying the log in order therefore cannot see the slice-target
/// without already having the bindings — so "bindings missing" is a misconfiguration, never a
/// replication race, and retrying it would only delay the diagnosis.
public sealed interface StreamAddressError extends Cause {
    /// The blueprint published its bindings, and this alias is not among them.
    ///
    /// Two declarations produce this, and the message names both because they are the only ways in:
    ///  - a **`version = "latest"`** spec — `BlueprintService.resolveOwnedAddress` omits `Latest`
    ///    because it has no concrete address until resolved against the live registry. Before #1040
    ///    such a consumer worked only by landing on the bare name some other blueprint also used;
    ///  - a **`StreamResourceValidator` failure**, after which `buildStreamBindingsCommand` writes an
    ///    EMPTY bindings entry by design, preserving rc1 deploy semantics. The stream config was
    ///    rejected and the deployment continued; this is where that surfaces.
    ///
    /// The fix in both cases is the same: name the owner explicitly with
    /// `source = "<namespace>:<stream>:<version>"`, which is what `StreamResource.External` is for.
    record UnboundStreamAlias(String alias, BlueprintId blueprintId, String message) implements StreamAddressError {
        static final Fn2<UnboundStreamAlias, String, BlueprintId> FACTORY = Causes.forTwoValues("Stream alias '%s' has no address binding in blueprint %s. A blueprint-declared "
                                                                                               + "stream resolves through the bindings published at deploy time; an alias missing "
                                                                                               + "from them is either a `version = \"latest\"` declaration (which has no concrete "
                                                                                               + "address) or a stream-config validation failure (which publishes empty bindings). "
                                                                                               + "Declare the owning stream explicitly with `source = \"<namespace>:<stream>:<version>\"`.",
                                                                                                UnboundStreamAlias::new);
    }

    /// The slice names an owning blueprint, but that blueprint has no bindings entry at all.
    ///
    /// Distinct from [UnboundStreamAlias] because the diagnosis differs: there the blueprint published
    /// bindings and this alias was excluded, here nothing was published for the blueprint. Given the
    /// same-batch ordering above, the reachable cause is a cluster deployed before stream bindings
    /// existed — an upgrade that needs a redeploy, not a declaration to fix. Until #1066 there was a
    /// second, far commoner cause: the TOML body publish (`POST /api/v1/blueprints`, `aether blueprint
    /// apply`, Forge) wrote no bindings at all.
    record UnresolvedStreamBindings(String alias, BlueprintId blueprintId, String message) implements StreamAddressError {
        static final Fn2<UnresolvedStreamBindings, String, BlueprintId> FACTORY = Causes.forTwoValues("Cannot resolve stream alias '%s': blueprint %s published no stream bindings. "
                                                                                                     + "Redeploy the blueprint so its alias-to-address bindings are written.",
                                                                                                      UnresolvedStreamBindings::new);
    }

    record unused() implements StreamAddressError {
        @Override
        public String message() {
            return "";
        }
    }
}
