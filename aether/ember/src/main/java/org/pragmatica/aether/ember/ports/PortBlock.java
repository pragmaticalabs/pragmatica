// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember.ports;

import java.util.stream.IntStream;

import org.pragmatica.aether.node.health.CoreSwimHealthDetector;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;


/// One cluster's port block: `STRIDE` consecutive ports from `base`, every one of them claimed (M9).
///
/// Layout from `base`: QUIC `[0, slots)`, management at `MGMT_OFFSET + slot`, app HTTP at `APP_OFFSET + slot`, SWIM UDP at
/// `SWIM_OFFSET + slot` (the node's QUIC port plus the production constant). `slots` is the cluster's WHOLE slot pool
/// (`poolSize * stride`, `EmberCluster` ports are `base + slot * portStride`), so `addNode` and relaunch bind only claimed
/// ports. The management and app offsets are a choice of this allocator [guess: the design fixes only the SWIM offset and
/// the 128 stride].
public record PortBlock(int base, int slots) {
    public static final int SWIM_OFFSET = CoreSwimHealthDetector.SWIM_PORT_OFFSET;
    public static final int MAX_SLOTS = 28;
    public static final int STRIDE = SWIM_OFFSET + MAX_SLOTS;
    public static final int MGMT_OFFSET = 32;
    public static final int APP_OFFSET = 64;

    public static Result<PortBlock> portBlock(int base, int slots) {
        return checkSlots(slots).map(checked -> new PortBlock(base, checked));
    }

    /// A slot pool must be positive and fit the block; above `MAX_SLOTS` the claim is refused, naming both numbers.
    public static Result<Integer> checkSlots(int slots) {
        return Verify.ensure(slots, Verify.Is::positive, PortError.SlotsNotPositive.FACTORY).flatMap(positive -> Verify.ensure(positive,
                                                                                                                               Verify.Is::lessThanOrEqualTo,
                                                                                                                               MAX_SLOTS,
                                                                                                                               pool -> PortError.SlotPoolTooLarge.FACTORY.apply(pool,
                                                                                                                                                                                MAX_SLOTS)));
    }

    public int quicBase() {
        return base;
    }

    public int mgmtBase() {
        return base + MGMT_OFFSET;
    }

    public int appBase() {
        return base + APP_OFFSET;
    }

    public int swimBase() {
        return base + SWIM_OFFSET;
    }

    /// Every port of the block, claimed whether a slot uses it or not.
    public IntStream ports() {
        return IntStream.range(base, base + STRIDE);
    }
}
