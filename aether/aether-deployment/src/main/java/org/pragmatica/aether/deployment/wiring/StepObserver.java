// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.wiring;

import org.pragmatica.lang.Contract;


/// Where an interpreter reports how long one `step` took: `aether_deployment_fsm_step_seconds{fsm,input}` (design 3.1; with
/// `input=Reconcile` it is the spec's `reconcile_seconds`). The Micrometer implementation is the `EffectTranslator`'s.
public interface StepObserver {
    @Contract
    void stepped(String fsm, String input, long nanos);

    StepObserver NONE = (_, _, _) -> {};
}
