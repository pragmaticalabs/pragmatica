// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.aether.deployment.cluster.ProvisioningWorld.KillPoint;

/// One seeded random schedule of faults against a [ProvisioningWorld]. Everything random comes from one [Random] seeded with `seed`, and the
/// world is a pure function of its inputs, so `new ProvisioningSchedule(seed).run()` replays a failing run exactly.
final class ProvisioningSchedule {
    /// Faults are injected during this window; the world then runs the rest with every fault off, past the ceiling plus three passes.
    private static final long FAULT_WINDOW_MS = 900_000L;
    private static final long SETTLE_MS = 2_700_000L;

    final long seed;
    final ProvisioningWorld world = new ProvisioningWorld();
    private final Random random;

    ProvisioningSchedule(long seed) {
        this.seed = seed;
        this.random = new Random(seed);
    }

    /// Plays the whole schedule and returns the finished world.
    ProvisioningWorld run() {
        var provider = world.provider;
        var lost = random.nextInt(4);
        var lostApplied = lost == 1 ? 0.5 : 0.1;
        var lostDropped = lost == 2 ? 0.5 : 0.1;

        provider.createDelayMs = 200L + random.nextInt(1_800);
        provider.visibilityLagMs = 600L + random.nextInt(301);
        provider.fate = (name, attempt) -> fateFor(lostApplied, lostDropped);
        world.gossipSurvives = random.nextBoolean();
        world.start(3 + random.nextInt(2));

        if (random.nextInt(8) == 0) {
            // K11: the first create stays unlisted far past the ceiling plus three passes, and its server never joins.
            var first = new AtomicReference<String>();
            var base = provider.fate;

            provider.fate = (name, attempt) -> {
                first.compareAndSet(null, name);

                return name.equals(first.get()) ? ModelComputeProvider.Fate.APPLIED_REPLY_LOST : base.apply(name, attempt);
            };
            provider.lagOf = (name, attempt) -> name.equals(first.get()) ? 1_800_000L : null;
            world.firstServerNeverBoots = true;
        }

        var kill = random.nextInt(7);

        if (kill >= 1 && kill <= 4) {
            world.killLeaderAt(KillPoint.values()[kill]);
        }

        if (kill == 5 || kill == 6) {
            world.partitionInsteadOfKillMs = 5_000L + random.nextInt(60_000);
            world.killLeaderAt(KillPoint.values()[1 + random.nextInt(4)]);
        }

        scheduleEvents();
        world.run(FAULT_WINDOW_MS);
        quiesceFaults();
        world.run(SETTLE_MS);

        return world;
    }

    private ModelComputeProvider.Fate fateFor(double lostApplied, double lostDropped) {
        var roll = random.nextDouble();

        return roll < lostApplied
               ? ModelComputeProvider.Fate.APPLIED_REPLY_LOST
               : roll < lostApplied + lostDropped ? ModelComputeProvider.Fate.NOT_APPLIED_REPLY_LOST : ModelComputeProvider.Fate.APPLIED;
    }

    private void scheduleEvents() {
        if (random.nextInt(4) == 0) {
            var start = 5_000L + random.nextInt(120_000);

            world.after(start, () -> world.provider.outage = true);
            world.after(start + 30_000L + random.nextInt(240_000), () -> world.provider.outage = false);
        }

        if (random.nextInt(4) == 0) {
            world.after(10_000L + random.nextInt(400_000), this::externalDeleteSomething);
        }

        if (random.nextInt(6) == 0) {
            world.after(10_000L + random.nextInt(60_000), world::nextCommitAckIsLost);
        }

        if (random.nextInt(10) == 0) {
            world.after(20_000L + random.nextInt(60_000), () -> world.lowerTargetTo(world.target - 1));
        }
    }

    private void externalDeleteSomething() {
        var servers = world.provider.servers();

        if (!servers.isEmpty()) {
            world.externalDelete(servers.get(random.nextInt(servers.size())).name());
        }
    }

    /// Every fault off: the provider answers, creates are applied, replies arrive, no partition remains.
    private void quiesceFaults() {
        world.provider.outage = false;
        world.provider.fate = (name, attempt) -> ModelComputeProvider.Fate.APPLIED;
        world.provider.lagOf = (name, attempt) -> null;
        world.firstServerNeverBoots = false;
    }
}
