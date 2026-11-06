// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.workflow;

import org.pragmatica.lang.io.TimeSpan;


/// rc6 §4.4 (`old INV-10`): the bounds of one workflow kind. `attempts` is how many effects one step may issue before its
/// postcondition is observed; `elapsed` is the whole run's time budget, fixed into the run's `deadline` at admission; `effects` is
/// how many effects the whole run may issue. Exhausting any of them ESCALATES the run.
public record WorkflowBudget(int attempts, TimeSpan elapsed, int effects) {}
