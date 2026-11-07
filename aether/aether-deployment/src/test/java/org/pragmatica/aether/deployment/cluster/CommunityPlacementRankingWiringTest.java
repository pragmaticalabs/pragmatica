// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser;
import org.pragmatica.aether.config.cluster.CommunityPlacement;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.CommunityPlacementOperationValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/// rc6 f5c Lane B: the community reserve path ranks its short locations with [SourceRanking] and honours `fallback = "forbid"`.
/// Identity for configs that set no new field is pinned against `PlacementReconciler.locationOrder()` itself.
class CommunityPlacementRankingWiringTest {
    private static final NodeId CORE = new NodeId("core");
    private static final String HEAD = """
        config_version = "1.0.0"
        [cluster]
        name = "test"
        version = "1.0.0"
        [runtime.prod]
        type = "container"
        image = "ghcr.io/pragmaticalabs/aether-node:1.0.0"
        """;
    private final KVStore<AetherKey, AetherValue> store = new KVStore<>(MessageRouter.mutable(), new Serializer() {
        @Override public <T> void write(ByteBuf buffer, T value) {}
    }, new Deserializer() {
        @Override public <T> T read(ByteBuf buffer) { return null; }
    });
    private final Set<NodeId> ready = new HashSet<>();
    private final List<CommunityPlacementOperationValue> providerOperations = new ArrayList<>();
    private Runnable beforeCommit = () -> {};
    private final CommunityPlacementReconciler.Actuator actuator = new CommunityPlacementReconciler.Actuator() {
        @Override public org.pragmatica.lang.Result<String> sourceBinding(org.pragmatica.aether.environment.SourceName source) {
            return org.pragmatica.lang.Result.success("binding");
        }
        @Override public Promise<Unit> create(CommunityPlacementOperationValue operation) {
            providerOperations.add(operation);
            return Promise.unitPromise();
        }
        @Override public Promise<Boolean> retirementSafe(CommunityPlacementOperationValue operation) { return Promise.success(true); }
        @Override public Promise<Unit> drain(CommunityPlacementOperationValue operation) { return Promise.unitPromise(); }
        @Override public Promise<Unit> terminate(CommunityPlacementOperationValue operation) { return Promise.unitPromise(); }
        @Override public Promise<Boolean> previousInstanceExists(CommunityPlacementOperationValue operation) { return Promise.success(false); }
    };
    private final CommunityPlacementReconciler reconciler = CommunityPlacementReconciler.communityPlacementReconciler(CORE, store,
        commands -> {
            var hook = beforeCommit;

            if (commands.toString().contains("CommunityPlacementOperationKey")) {
                beforeCommit = () -> {};
                hook.run();
            }
            return Promise.success(store.process(store.createBatch(commands)));
        }, () -> true,
        ready::contains, () -> Set.of(CORE), () -> 10, actuator, _ -> {}, (_, _) -> {}, org.pragmatica.lang.io.TimeSpan.timeSpan(60_000L).millis());

    // ---- fixtures ----
    private static String docker(String name, String extra) {
        return """
            [source.%s]
            type = "docker"
            %s
            [source.%s.worker]
            count = 1
            """.formatted(name, extra, name);
    }

    private static String hetzner(String name, String extra) {
        return """
            [source.%s]
            type = "cloud"
            provider = "hetzner"
            credentials = "key"
            region = "eu-central"
            %s
            [source.%s.worker]
            count = 1
            instance_type = "cx41"
            runtime = "prod"
            """.formatted(name, extra, name);
    }

    private static String community(int target, String... sources) {
        var text = new StringBuilder("[community.stable]\ntarget_size = " + target + "\n");

        for (var source : sources) {
            text.append("[community.stable.placement.").append(source).append("]\nsource = \"").append(source).append("\"\nweight = 1\n");
        }
        return text.toString();
    }

    private void seed(KVCommand command) { store.process(store.createBatch(List.of(command))); }

    private void install(String config, long configVersion) {
        seed(new KVCommand.Put<>(AetherKey.ClusterConfigKey.CURRENT,
            new AetherValue.ClusterConfigValue(Option.some(config), "test", "1.0.0", List.of(), 3, 3, "forge", configVersion, 0)));
    }

    private void start(String config) {
        seed(new KVCommand.Put<>(LeaderKey.INSTANCE, new LeaderValue(CORE, 1)));
        install(config, 1);
    }

    private Option<CommunityPlacementOperationValue> operation() {
        return store.getTyped(new AetherKey.CommunityPlacementOperationKey("stable"), CommunityPlacementOperationValue.class);
    }

    private CommunityPlacementOperationValue current() { return operation().unwrap(); }

    private void member(NodeId node, String source) {
        seed(new KVCommand.Put<>(new AetherKey.ActivationDirectiveKey(node),
            new AetherValue.ActivationDirectiveValue(AetherValue.ActivationDirectiveValue.WORKER, "stable", "")));
        seed(new KVCommand.Put<>(new AetherKey.NodePlacementKey(node), new AetherValue.NodePlacementValue(source, Option.none(), node.id())));
    }

    private void targetReady() {
        var target = current();

        member(target.targetNode(), target.targetSource());
        ready.add(target.targetNode());
    }

    private List<String> fill(int operations) {
        var order = new ArrayList<String>();

        for (int i = 0; i < operations; i++) {
            reconciler.reconcile().await().unwrap();
            order.add(current().targetSource());
            reconciler.reconcile().await().unwrap();
            targetReady();
            reconciler.reconcile().await().unwrap();
        }
        return order;
    }

    private static CommunityPlacement policy(String config) {
        return ClusterBootstrapConfigParser.parse(config).unwrap().communities().get("stable");
    }

    // ---- wiring pins (design section 3) ----
    @Test
    void reserve_dockerAndHetznerDefaultFields_reservesLocationOrderFirst() {
        var config = HEAD + hetzner("a-hz", "") + docker("b-dk", "") + community(2, "a-hz", "b-dk");
        var oracle = policy(config).locations().stream().sorted(PlacementReconciler.locationOrder()).findFirst().orElseThrow();

        start(config);
        reconciler.reconcile().await().unwrap();

        assertThat(current().targetSource()).isEqualTo(oracle.source()).isEqualTo("a-hz");
    }

    @Test
    void reserve_spotAndOndemandCloud_prefersOndemand() {
        start(HEAD + hetzner("a-spot", "cost_class = \"spot\"") + hetzner("b-od", "") + community(2, "a-spot", "b-od"));
        reconciler.reconcile().await().unwrap();

        assertThat(current().targetSource()).isEqualTo("b-od");
    }

    // ---- behaviour ----
    @Test
    void reserve_preferenceChangesWhichShortLocationFillsFirst_finalCountsUnchanged() {
        var plain = HEAD + docker("a-dk", "") + docker("b-dk", "") + community(2, "a-dk", "b-dk");

        start(plain);
        var plainOrder = fill(2);
        var plainCounts = policy(plain).desiredCounts();
        var plainOperations = providerOperations.size();

        assertThat(plainOrder).containsExactly("a-dk", "b-dk");

        var preferred = HEAD + docker("a-dk", "") + docker("b-dk", "preference = 10") + community(2, "a-dk", "b-dk");
        var other = new CommunityPlacementRankingWiringTest();

        other.start(preferred);
        var preferredOrder = other.fill(2);
        other.reconciler.reconcile().await().unwrap();

        assertThat(preferredOrder).containsExactly("b-dk", "a-dk");
        assertThat(policy(preferred).desiredCounts()).isEqualTo(plainCounts);
        assertThat(other.providerOperations).as("full after one member per location: no third operation").hasSize(plainOperations);
        assertThat(other.providerOperations.stream().map(CommunityPlacementOperationValue::targetSource).sorted().toList())
            .containsExactly("a-dk", "b-dk");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "preference = 1", "cost_class = \"spot\"", "cost_class = \"reserved\"\npreference = 900", "fallback = \"forbid\""})
    void reserve_singleBinding_isUnchangedByItsProfile(String extra) {
        start(HEAD + hetzner("only", extra) + community(1, "only"));
        reconciler.reconcile().await().unwrap();

        assertThat(current().targetSource()).isEqualTo("only");
    }

    // ---- fallback = forbid (section 2.4) ----
    private static final String THREE = HEAD + "%s" + docker("a-dk", "") + docker("b-dk", "") + community(4, "a-dk", "b-dk", "c-dk");

    private void cRefusedWithAMembersTwoAndB(String cExtra) {
        var config = THREE.formatted(docker("c-dk", cExtra));
        var location = policy(config).locations().stream().filter(l -> l.source().equals("c-dk")).findFirst().orElseThrow();
        var key = new AetherKey.CommunityPlacementAvailabilityKey("stable", "c-dk", location.zone());
        var authority = new LeaderValue(CORE, 1);

        start(config);
        member(new NodeId("a1"), "a-dk");
        member(new NodeId("a2"), "a-dk");
        member(new NodeId("b1"), "b-dk");
        seed(new KVCommand.LeaderTransaction<>(key, "refusal", authority, List.of(), List.of(
            new KVCommand.Mutation<AetherKey, AetherValue>(key, Option.none(), Option.some(
                new AetherValue.CommunityPlacementAvailabilityValue(CommunityPlacementAvailability.policyIdentity(policy(config)),
                    "binding", new NodeId("ghost"), System.currentTimeMillis(), 1))))));
    }

    @Test
    void reserve_forbidUnavailableWithRemainder_keepsItsShareAndReservesNothing() {
        cRefusedWithAMembersTwoAndB("fallback = \"forbid\"");
        reconciler.reconcile().await().unwrap();

        assertThat(operation().isEmpty()).as("a=2, b=1, c=1 stays the target: nothing is short, c waits for its probe").isTrue();
    }

    @Test
    void reserve_allowUnavailableWithRemainder_spillsItsShareToTheEligibleLocations() {
        cRefusedWithAMembersTwoAndB("fallback = \"allow\"");
        reconciler.reconcile().await().unwrap();

        assertThat(current().targetSource()).as("c's share spills by weight: a=2, b=2").isEqualTo("b-dk");
    }

    // ---- config change between snapshot and commit (section 4.3) ----
    @Test
    void reserve_configChangedBetweenSnapshotAndCommit_isRejectedAndNextPassReRanks() {
        var before = HEAD + docker("a-dk", "") + docker("b-dk", "") + community(2, "a-dk", "b-dk");
        var after = HEAD + docker("a-dk", "") + docker("b-dk", "preference = 10") + community(2, "a-dk", "b-dk");

        var hookRan = new boolean[1];

        start(before);
        beforeCommit = () -> { hookRan[0] = true; install(after, 2); };
        reconciler.reconcile().await().unwrap();

        assertThat(operation().isEmpty()).as("the commit that ranked a-dk first against the old config is rejected; operation=%s hookRan=%s", operation(), hookRan[0]).isTrue();

        reconciler.reconcile().await().unwrap();

        assertThat(current().targetSource()).as("the next pass re-ranks against the new config").isEqualTo("b-dk");
    }
}
