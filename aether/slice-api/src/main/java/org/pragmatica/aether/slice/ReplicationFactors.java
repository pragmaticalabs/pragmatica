// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice;

import java.util.ArrayList;
import java.util.List;

import org.pragmatica.lang.Result;


/// #1564 (owner ruling, know 267792392): the replication policy every stream, durable topic and durable entity
/// shares. `replicationFactor` (RF) is the number of copies of each partition, the owner included.
/// `confirmationFactor` (CF) is how many of them, the owner included, hold a write before it is acknowledged:
/// the owner appends, then awaits `CF - 1` distinct non-self acknowledgements. Valid iff `1 <= CF <= RF`.
///
/// Writes stay available while at least CF replicas are live, so they tolerate `RF - CF` replica losses; an
/// acknowledged record survives `CF - 1` simultaneous replica losses. CF need not be a majority: ownership is
/// fenced by the committed record and its epoch (#1230), so CF buys durability, not consistency. `CF < RF` is
/// lossless only through #1555's promotion gate, which catches the new owner up from the highest-head survivor.
///
/// Built only through [#replicationFactors] or [ReplicationDeclaration#resolve]; the engine re-checks a
/// committed pair through [#replicationFactors] (#1564 R5), and "RF below 3 only when declared" is a
/// DECLARATION rule ([ReplicationDeclaration]), because a committed pair no longer knows what was declared.
public record ReplicationFactors(int replicationFactor, int confirmationFactor) {
    /// The owner's defaults, used when neither the resource nor the committed cluster config sets a value.
    public static final ReplicationFactors BUILT_IN = new ReplicationFactors(3, 2);
    /// Below this RF a resource must declare its RF itself, and is warned loudly: under terminal removal a
    /// smaller factor loses a partition when that many nodes die.
    public static final int RECOMMENDED_MINIMUM_FACTOR = 3;

    public static Result<ReplicationFactors> replicationFactors(int replicationFactor, int confirmationFactor) {
        if (replicationFactor < 1) {
            return new ReplicationFactorsError.FactorBelowOne(replicationFactor).result();
        }

        if (confirmationFactor < 1 || confirmationFactor > replicationFactor) {
            return new ReplicationFactorsError.ConfirmationOutOfRange(replicationFactor, confirmationFactor).result();
        }

        return Result.success(new ReplicationFactors(replicationFactor, confirmationFactor));
    }

    /// Refused when RF exceeds the cluster's DESIRED core count (#1564 R7): a partition could never hold RF
    /// copies. `desiredCoreCount <= 0` means no committed cluster shape is known, and nothing is checked.
    public Result<ReplicationFactors> withinCoreCount(int desiredCoreCount) {
        return desiredCoreCount > 0 && replicationFactor > desiredCoreCount
               ? new ReplicationFactorsError.ExceedsCoreCount(replicationFactor, desiredCoreCount).result()
               : Result.success(this);
    }

    /// Refused when a live resource is redeclared with different factors (#1564 R8): a committed policy is not
    /// changed in place, and keeping the old one silently would leave the operator believing the new one holds.
    public Result<ReplicationFactors> sameAsCommitted(String resource, ReplicationFactors committed) {
        return equals(committed)
               ? Result.success(this)
               : new ReplicationFactorsError.ChangedOnLiveResource(resource, committed, this).result();
    }

    /// The warnings this pair raises at declaration. `factorDeclared` is whether the resource set its RF itself.
    public List<ReplicationWarning> warnings(boolean factorDeclared) {
        var warnings = new ArrayList<ReplicationWarning>();

        if (factorDeclared && replicationFactor < RECOMMENDED_MINIMUM_FACTOR) {
            warnings.add(ReplicationWarning.FACTOR_BELOW_THREE);
        }

        if (confirmationFactor == replicationFactor) {
            warnings.add(ReplicationWarning.CONFIRMATION_EQUALS_FACTOR);
        }

        if (confirmationFactor == 1) {
            warnings.add(ReplicationWarning.CONFIRMATION_OWNER_ONLY);
        }

        return List.copyOf(warnings);
    }

    @Override
    public String toString() {
        return "replication_factor=" + replicationFactor + ", confirmation_factor=" + confirmationFactor;
    }
}
