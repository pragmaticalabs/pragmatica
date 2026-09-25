// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.blueprint;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.utils.Causes;


/// A `[[slices]]` entry [SliceSpec] refuses for what it declares. Typed so a caller can tell the
/// instance-floor refusal apart from a malformed entry without matching on its text.
public sealed interface SliceSpecError extends Cause {
    /// `instances` below [SliceSpec#MIN_INSTANCES] (#1495). Placement puts at most one instance of a slice
    /// on a node, and a drain halts its node without waiting for a replacement to become ACTIVE, so a
    /// single drain or node failure removes one instance: at three or more the slice keeps at least two.
    record InstancesBelowMinimum(Artifact artifact, int instances, String message) implements SliceSpecError {
        static final Fn2<InstancesBelowMinimum, Artifact, Integer> FACTORY = Causes.forTwoValues("Slice %s declares instances = %s; a blueprint slice must run at least " + SliceSpec.MIN_INSTANCES
                                                                                                + " instances",
                                                                                                 InstancesBelowMinimum::new);
    }
}
