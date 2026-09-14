// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.util.List;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.BlueprintStreamBindingsKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.BlueprintStreamBindingsValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.BlueprintStreamBindingsValue.NamedAddress;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.aether.slice.stream.BlueprintStreamAddresses;
import org.pragmatica.aether.slice.stream.StreamAddressError;
import org.pragmatica.aether.slice.stream.SystemStreams;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// #1040's invariant, pinned: FOR A DECLARED STREAM, THE KEY USED TO COMMIT ITS `StreamConfig` AND THE
/// KEY USED BY EVERY MANAGEMENT ROUTE MUST BE THE SAME STRING.
///
/// The two derivations start from different places and meet here, which is the only arrangement that can
/// observe them diverging:
///
///  - the MANAGEMENT side takes the catalog [ResourceAddress] straight off the route and reduces it with
///    [StreamManager#engineKey];
///  - the SLICE side starts from the bare `resources.toml` alias the config binder produced and resolves
///    it through [BlueprintStreamAddresses#engineKeyFor] against the deploy-time bindings.
///
/// The bindings are seeded into a real [KVStore] in exactly the shape `BlueprintService` writes them —
/// a `SliceTargetValue` naming the owning blueprint, and a `BlueprintStreamBindingsValue` mapping alias
/// to address — rather than stubbed, so the test exercises the lookup the running node performs instead
/// of a restatement of it.
///
/// THE NAMESPACE IS NON-`system` DELIBERATELY. `system:cluster-events` is the single address whose two
/// spellings coincide, so it is the one stream on which this test would pass no matter how badly the two
/// sides disagreed — and it is the control the original diagnosis used. [#bareAliasDiffersFromEngineKey]
/// is the non-vacuity guard that keeps this suite honest: it fails if the fixture ever degenerates to a
/// case where "qualified" and "bare" are the same string.
class StreamEngineKeyDivergenceTest {
    private static final String ALIAS = "repl-failover-events";
    private static final String NAMESPACE = "org.pragmatica.aether.test.test-stream-repl";
    private static final String SLICE_COORDS = "org.pragmatica.aether.test:test-stream-repl:1.0.0";

    private KVStore<AetherKey, AetherValue> kvStore;
    private Artifact artifact;
    private ResourceAddress declaredAddress;

    @BeforeEach
    void setUp() {
        kvStore = new KVStore<>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
        artifact = Artifact.artifact(SLICE_COORDS).unwrap();
        declaredAddress = ResourceAddress.resourceAddress(NAMESPACE, ALIAS, "1.0.0").unwrap();
    }

    /// Mirrors `BlueprintService.buildStreamBindingsCommand` + the `SliceTargetValue.owningBlueprint`
    /// the deploy path writes — the two hops `BlueprintStreamAddresses` walks.
    private void seedDeployment(ResourceAddress address) {
        var blueprintId = BlueprintId.blueprintId(artifact);

        applyToKvStore(new KVCommand.Put<>(SliceTargetKey.sliceTargetKey(artifact.base()),
                                           new SliceTargetValue(artifact.version(),
                                                                1,
                                                                1,
                                                                Option.some(blueprintId),
                                                                "CORE_ONLY",
                                                                0L)));
        applyToKvStore(new KVCommand.Put<>(BlueprintStreamBindingsKey.blueprintStreamBindingsKey(blueprintId),
                                           BlueprintStreamBindingsValue.blueprintStreamBindingsValue(List.of(NamedAddress.namedAddress(ALIAS,
                                                                                                                                        address)))));
    }

    /// The slice-target without the bindings entry — a blueprint deployed before stream bindings existed.
    private void seedSliceTargetOnly() {
        applyToKvStore(new KVCommand.Put<>(SliceTargetKey.sliceTargetKey(artifact.base()),
                                           new SliceTargetValue(artifact.version(),
                                                                1,
                                                                1,
                                                                Option.some(BlueprintId.blueprintId(artifact)),
                                                                "CORE_ONLY",
                                                                0L)));
    }

    private void applyToKvStore(KVCommand<AetherKey> command) {
        kvStore.process(kvStore.createBatch(List.of(command)));
    }

    private String sliceSideKey() {
        return BlueprintStreamAddresses.engineKeyFor(kvStore, artifact, ALIAS).unwrap();
    }

    @Nested
    class DeclaredStream {
        @Test
        void engineKey_agreesBetweenSliceAndManagementDerivations_forNonSystemNamespace() {
            seedDeployment(declaredAddress);

            assertThat(sliceSideKey()).isEqualTo(StreamManager.engineKey(declaredAddress));
        }

        /// Non-vacuity. Without this the test above is satisfied by both sides returning the bare alias,
        /// which is the pre-#1040 state on the slice side and would read as a pass.
        @Test
        void bareAliasDiffersFromEngineKey_forNonSystemNamespace() {
            seedDeployment(declaredAddress);

            assertThat(StreamManager.engineKey(declaredAddress)).isNotEqualTo(ALIAS)
                                                                .isEqualTo(NAMESPACE + ":" + ALIAS + ":1.0.0");
            assertThat(sliceSideKey()).isNotEqualTo(ALIAS);
        }

        /// An explicit non-default stream version is the case a namespace-only re-derivation would get
        /// wrong while every fixture in this repo still agreed — no blueprint here declares one. Reading
        /// the published binding carries the version through; deriving it could only ever pin `1.0.0`.
        @Test
        void engineKey_carriesDeclaredVersion_whenBlueprintPinsNonDefaultVersion() {
            var pinned = ResourceAddress.resourceAddress(NAMESPACE, ALIAS, "2.3.1").unwrap();

            seedDeployment(pinned);

            assertThat(sliceSideKey()).isEqualTo(StreamManager.engineKey(pinned))
                                      .isEqualTo(NAMESPACE + ":" + ALIAS + ":2.3.1");
        }

        /// An `External` declaration (`source = "other:stream:1.0.0"`) is published as a binding pointing
        /// OUT of the declaring blueprint's namespace. Deriving the namespace locally would aim a
        /// cross-namespace consumer at a ring nobody writes to.
        @Test
        void engineKey_honoursForeignNamespace_whenBindingPointsOutsideDeclaringBlueprint() {
            var foreign = ResourceAddress.resourceAddress("org.example.other-producer", ALIAS, "1.0.0").unwrap();

            seedDeployment(foreign);

            assertThat(sliceSideKey()).isEqualTo(StreamManager.engineKey(foreign))
                                      .isEqualTo("org.example.other-producer:" + ALIAS + ":1.0.0");
        }
    }

    @Nested
    class SystemNamespace {
        /// `system` streams keep the bare reduction on BOTH sides. `SystemStreams.isForbiddenEngineKey`
        /// gates management-api writes on that exact spelling, so qualifying them would carry
        /// `cluster-events` out from under a security gate rather than merely renaming it.
        @Test
        void engineKey_reducesToBareName_forSystemNamespace() {
            var systemAddress = SystemStreams.CLUSTER_EVENTS;

            assertThat(StreamManager.engineKey(systemAddress)).isEqualTo("cluster-events");
            assertThat(SystemStreams.isForbiddenEngineKey(StreamManager.engineKey(systemAddress))).isTrue();
        }

        /// The gate must keep REFUSING to recognise the qualified spelling — if it accepted both, the
        /// reduction would no longer be what the gate depends on.
        @Test
        void isForbiddenEngineKey_rejectsQualifiedSpelling_ofSystemStream() {
            assertThat(SystemStreams.isForbiddenEngineKey(SystemStreams.CLUSTER_EVENTS.asString())).isFalse();
        }
    }

    @Nested
    class UndeployedSlice {
        /// No deployment in the KV snapshot — a Forge/Ember/unit runtime, or a slice whose bindings have
        /// not replicated yet. The alias passes through unchanged; there is no catalog entry to agree
        /// with, and guessing an address would invent one.
        @Test
        void engineKeyFor_fallsBackToBareAlias_whenSliceHasNoOwningBlueprint() {
            assertThat(sliceSideKey()).isEqualTo(ALIAS);
            assertThat(BlueprintStreamAddresses.addressFor(kvStore, artifact, ALIAS).isPresent()).isFalse();
        }

        /// THE ONLY FALLBACK THAT REMAINS, stated as a boundary rather than assumed: absence of an owning
        /// blueprint. Everything past this point in the deployment is refused instead, so this test and
        /// [DeployedButUnbound] together enumerate the two sides of the line.
        @Test
        void engineKeyFor_succeeds_whenSliceHasNoOwningBlueprint() {
            BlueprintStreamAddresses.engineKeyFor(kvStore, artifact, ALIAS)
                                    .onFailure(cause -> fail(cause.message()));
        }
    }

    @Nested
    class DeployedButUnbound {
        /// The shape a `version = "latest"` consumer produces — `BlueprintService.resolveOwnedAddress`
        /// omits `Latest` specs, so the alias is absent from an otherwise-populated bindings map. Also
        /// the shape of a `StreamResourceValidator` failure, after which an EMPTY bindings entry is
        /// published by design.
        ///
        /// This must FAIL, not fall back. A bare fallback here is a consumer reading a ring no producer
        /// writes to — the same silent class #1040 removes, re-created one path over.
        @Test
        void engineKeyFor_fails_whenAliasMissingFromPublishedBindings() {
            seedDeployment(declaredAddress);

            BlueprintStreamAddresses.engineKeyFor(kvStore, artifact, "unbound-alias")
                                    .onSuccess(key -> fail("Expected refusal, got engine key " + key))
                                    .onFailure(cause -> assertThat(cause).isInstanceOf(StreamAddressError.UnboundStreamAlias.class));
        }

        /// The message has to carry the alias and the blueprint, because the operator's next action is to
        /// add `source = ...` to one specific section of one specific blueprint. A refusal that says only
        /// "unresolved" is loud without being useful.
        @Test
        void unboundAliasFailure_namesTheAliasAndTheBlueprint() {
            seedDeployment(declaredAddress);

            BlueprintStreamAddresses.engineKeyFor(kvStore, artifact, "unbound-alias")
                                    .onFailure(cause -> assertThat(cause.message()).contains("unbound-alias")
                                                                                   .contains(SLICE_COORDS)
                                                                                   .contains("source"));
        }

        /// Distinct from the alias case and distinct in diagnosis: the blueprint published nothing at all.
        @Test
        void engineKeyFor_fails_whenBlueprintPublishedNoBindings() {
            seedSliceTargetOnly();

            BlueprintStreamAddresses.engineKeyFor(kvStore, artifact, ALIAS)
                                    .onSuccess(key -> fail("Expected refusal, got engine key " + key))
                                    .onFailure(cause -> assertThat(cause).isInstanceOf(StreamAddressError.UnresolvedStreamBindings.class));
        }
    }

    private static Serializer stubSerializer() {
        return new Serializer() {
            @Override public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    private static Deserializer stubDeserializer() {
        return new Deserializer() {
            @Override public <T> T read(ByteBuf byteBuf) {
                return null;
            }
        };
    }
}
