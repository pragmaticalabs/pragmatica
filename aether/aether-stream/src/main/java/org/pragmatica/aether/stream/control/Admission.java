// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.control;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;


/// The admission predicate of one [ControlKind.Named] kind (#2590 P1, the SPI of §5.2): whether `incoming` may become
/// the latest record for its key, given the key's `latest` record in the owner's ADMITTED view (the committed index plus
/// the in-flight tail) and the owner's `mirror`. Pure for a given owner: the owner is the single decider for its
/// partition, and it runs this on the partition's serial queue. A failure is the refusal, and its cause is what the
/// writer is told; nothing is appended for a refused record.
@FunctionalInterface
public interface Admission {
    Result<Unit> admit(Option<ControlRecord> latest, ControlRecord incoming, MirrorView mirror);
}
