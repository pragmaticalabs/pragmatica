// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.ntt;

/// Answers whether a drain episode is held open. [DrainProcedure]'s exit gate consults it; the default is [#notHeld()], so the
/// gate behaves as before until a source is bound.
@FunctionalInterface
public interface HoldAnswerSource {
    HoldAnswer holdAnswer(DrainEpisode episode);

    static HoldAnswerSource notHeld() {
        return _ -> HoldAnswer.NOT_HELD;
    }
}
