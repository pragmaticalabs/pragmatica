// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.generation;

import org.pragmatica.serialization.Codec;


/// Advisory health hint for a core member, as observed by the leader via pings + SWIM.
///
/// See `aether/docs/specs/cluster-generation-spec.md` §6 / §8.
@Codec public enum HealthHint {
    HEALTHY,
    SUSPECTED,
    FAULTY
}
