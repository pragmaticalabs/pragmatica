// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// v2713c survivors n19 and r06: the operator reads these texts verbatim, so the argument order and the wording are pinned.
class ScaleDeployErrorTextTest {
    /// n19: the requested count comes first and the slice's maximum second, both in the fields and in the message.
    @Test
    void instancesAboveMax_carriesRequestedThenMax_andNamesThemInOrder() {
        var cause = ScaleRouteError.InstancesAboveMax.FACTORY.apply(9, 6);

        assertThat(cause.requested()).isEqualTo(9);
        assertThat(cause.maxInstances()).isEqualTo(6);
        assertThat(cause.message()).isEqualTo("Requested 9 instances but the slice's maxInstances is 6");
    }

    /// r06: a deploy that carries `instances` is refused with a text that says why and where to go instead.
    @Test
    void instancesNotAccepted_textNamesTheReasonAndTheScaleRoute() {
        assertThat(DeployRouteError.INSTANCES_NOT_ACCEPTED.message())
                .isEqualTo("instances is not accepted: a deployment runs over the slice's committed target, whose count the leader's "
                           + "requirement combiner owns (rc6 §3.1); set an operator requirement with POST /api/scale (aether scale -n)");
    }
}
