// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigDiff;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.config.cluster.SourceType;
import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.aether.environment.ProvisionedNode;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;

/// G1-g: an apply action that would create nodes asks who the pool's decider is first. The leader's pool: nothing is created and the leader
/// is waited for. Apply's pool: created. A refusal creates nothing. Driven at the gate and through `WaveExecutor`, whose scale-up and
/// add-role actions are the callers.
class PoolGateTest {
    private static final ClusterName CLUSTER = ClusterName.clusterName("prod-cluster").unwrap();
    private static final String TOML = """
            config_version = "1.0.0"

            [cluster]
            name = "prod-cluster"
            version = "1.0.0"

            [source.eu-1]
            type = "cloud"
            provider = "hetzner"
            region = "eu-central"
            credentials = "hcloud-token"
            zone = "fsn1"

            [source.eu-1.core]
            count = %d
            """;

    @RegisterExtension
    final LedgerDirExtension ledger = new LedgerDirExtension();

    private final PoolAuthority original = WaveExecutor.poolAuthority;
    private final List<String> asked = new ArrayList<>();
    private final List<String> waited = new ArrayList<>();
    private final AtomicInteger creates = new AtomicInteger();

    @AfterEach
    void restore() {
        WaveExecutor.poolAuthority = original;
    }

    private PoolAuthority authority(PoolAuthority.Actor actor) {
        return new PoolAuthority() {
            @Override
            public Result<Actor> actorFor(String source, NodeRole role, int wanted) {
                asked.add(source + "." + role.value() + "=" + wanted);

                return Result.success(actor);
            }

            @Override
            public Result<Unit> awaitLeader(NodeRole role, int wantedInRole, Consumer<String> progress) {
                waited.add(role.value() + ">=" + wantedInRole);

                return Result.unitResult();
            }
        };
    }

    private PoolAuthority refusing() {
        return new PoolAuthority() {
            @Override
            public Result<Actor> actorFor(String source, NodeRole role, int wanted) {
                return new PoolRefused(source, role, "test refusal").result();
            }

            @Override
            public Result<Unit> awaitLeader(NodeRole role, int wantedInRole, Consumer<String> progress) {
                throw new AssertionError("a refused pool is never waited on");
            }
        };
    }

    private Result<List<ProvisionedNode>> through(PoolAuthority authority, SourceType type, int missing) {
        return PoolGate.through(authority,
                                new PoolGate.Pool("eu-1", type, NodeRole.CORE, missing, 5, 5),
                                _ -> {},
                                () -> {
                                    creates.incrementAndGet();

                                    return Result.success(List.of());
                                });
    }

    @Test
    void applysPool_createsOnce() {
        through(authority(PoolAuthority.Actor.APPLY), SourceType.CLOUD, 2).unwrap();

        assertThat(creates.get()).isEqualTo(1);
        assertThat(waited).isEmpty();
    }

    @Test
    void leadersPool_createsNothing_andWaitsForTheRoleToReachItsConfiguredCount() {
        through(authority(PoolAuthority.Actor.LEADER), SourceType.CLOUD, 2).unwrap();

        assertThat(creates.get()).isZero();
        assertThat(waited).containsExactly("core>=5");
    }

    @Test
    void refusedPool_createsNothing_andIsNotWaitedOn() {
        var result = through(refusing(), SourceType.DOCKER, 2);

        assertThat(result.isFailure()).isTrue();
        assertThat(creates.get()).isZero();
    }

    @Test
    void nothingMissing_createsWithoutAskingAnyone() {
        through(refusing(), SourceType.CLOUD, 0).unwrap();

        assertThat(creates.get()).isEqualTo(1);
    }

    @Test
    void sourceThatCreatesNoNode_isNotAsked() {
        through(refusing(), SourceType.SSH, 2).unwrap();
        through(refusing(), SourceType.FORGE, 2).unwrap();

        assertThat(creates.get()).isEqualTo(2);
    }

    // ---- through WaveExecutor: the gate is on the scale-up path, and only there

    private Result<ApplyResult> scaleCoreFromThreeToFive() {
        var stored = ClusterBootstrapConfigParser.parse(TOML.formatted(3)).unwrap();
        var desired = ClusterBootstrapConfigParser.parse(TOML.formatted(5)).unwrap();
        var plan = ClusterBootstrapConfigDiff.diff(stored, desired);
        var recorder = ledger.recorder(CLUSTER, ApplyAction.fromPlan(plan).stream().map(ApplyAction::key).toList());

        return WaveExecutor.execute(plan, stored, desired, recorder);
    }

    @Test
    void scaleUp_leadersPool_createsNothing_andSucceedsOnceTheLeaderConverged() {
        WaveExecutor.poolAuthority = authority(PoolAuthority.Actor.LEADER);

        var result = scaleCoreFromThreeToFive();

        assertThat(result.isSuccess()).as("%s", result).isTrue();
        assertThat(result.unwrap().nodesAdded()).isZero();
        assertThat(asked).containsExactly("eu-1.core=5");
        assertThat(waited).containsExactly("core>=5");
    }

    @Test
    void scaleUp_refusedPool_failsNamingThePool_andCreatesNothing() {
        WaveExecutor.poolAuthority = refusing();

        var result = scaleCoreFromThreeToFive();

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause.message()).contains("eu-1.core").contains("test refusal"));
    }

    @Test
    void scaleUp_applysPool_goesOnToCreate() {
        WaveExecutor.poolAuthority = authority(PoolAuthority.Actor.APPLY);

        var result = scaleCoreFromThreeToFive();

        assertThat(asked).containsExactly("eu-1.core=5");
        assertThat(waited).isEmpty();
        // no join inputs in this test, so the creating path ends there: it was reached, which is the point
        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause.message()).doesNotContain("test refusal"));
    }
}
