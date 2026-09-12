// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamCompression;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// #1040: the slice/engine path materializes a declared stream under its ENGINE KEY, not under the bare
/// `resources.toml` section name the config binder hands it.
///
/// Before this, `StreamManager.engineKey` was applied on the management side only — 16 call sites in
/// `aether/node`, none in `aether/aether-stream` — so one blueprint declaration produced two live rings:
/// the bare `repl-failover-events` the slice created honouring `resources.toml`, and a
/// `ns:repl-failover-events:1.0.0` ring the catalog minted from management defaults because
/// `streamConfigKey(qualified)` could not find the config committed under the bare name. A declared
/// `min-sync-replicas = 2` was therefore silently void for every write through the catalog path.
///
/// EVERY TEST HERE USES A NON-`system` NAMESPACE, and that is load-bearing rather than incidental.
/// `system:cluster-events` is the one address whose two spellings coincide — `engineKey` reduces the
/// `system` namespace to the bare name — which is why it is useless as a control for this defect and
/// why it was the control every diagnostic reached for. [SystemBareReduction] pins that reduction
/// separately, as behaviour to preserve rather than as evidence about the qualified path.
class StreamEngineKeyQualificationTest {
    private static final RetentionPolicy RETENTION = RetentionPolicy.retentionPolicy(10_000, 4 * 1024 * 1024L, 60_000L);
    private static final String ALIAS = "repl-failover-events";
    private static final String SLICE_ID = "org.pragmatica.aether.test:test-stream-repl:1.0.0";
    private static final String QUALIFIED = "org.pragmatica.aether.test.test-stream-repl:repl-failover-events:1.0.0";

    /// EVENTUAL (DROP_OLDEST) so the manager does not require AHSE, and a budget large enough to admit
    /// the partition floor — the create must SUCCEED here, unlike its exhaustion-propagation sibling.
    private static StreamConfig declaredConfig() {
        return StreamConfig.streamConfig(ALIAS, 1, RETENTION, "earliest", 1024 * 1024L, ConsistencyMode.EVENTUAL, 0);
    }

    private static StreamPartitionManager manager() {
        return StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE);
    }

    /// A resolver standing in for the node-supplied one, which reads the deploy-time
    /// `BlueprintStreamBindings`. It answers for exactly one (sliceId, alias) pair and returns the alias
    /// unchanged otherwise, so a test that reaches it with the wrong arguments fails rather than
    /// silently taking the fallback.
    private static StreamAddressResolver bindingResolver() {
        return (sliceId, alias) -> SLICE_ID.equals(sliceId) && ALIAS.equals(alias)
                                   ? QUALIFIED
                                   : alias;
    }

    private static ProvisioningContext publisherContext(StreamPartitionManager manager) {
        return ProvisioningContext.provisioningContext()
                                  .withExtension(StreamPartitionManager.class, manager)
                                  .withExtension(Serializer.class, identitySerializer());
    }

    private static ProvisioningContext accessContext(StreamPartitionManager manager) {
        return publisherContext(manager).withExtension(Deserializer.class, identityDeserializer());
    }

    private static ProvisioningContext deployed(ProvisioningContext context) {
        return context.withExtension(StreamAddressResolver.class, bindingResolver())
                      .withExtension(String.class, SLICE_ID);
    }

    /// The assertion both arms of the defect need: the stream exists under the qualified key AND does
    /// not exist under the bare one. Asserting only the first would pass while two rings were live,
    /// which is precisely the state #1040 reported.
    private static void assertMaterializedOnlyAs(StreamPartitionManager manager, String expected, String absent) {
        assertThat(manager.streamInfo(expected).isPresent()).as("stream must exist under engine key %s", expected)
                                                            .isTrue();
        assertThat(manager.streamInfo(absent).isPresent()).as("stream must NOT also exist under %s — two rings is the defect", absent)
                                                          .isFalse();
    }

    @Nested
    class DeployedSlice {
        @Test
        void streamPublisherFactory_provision_materializesUnderQualifiedKey_forNonSystemNamespace() {
            var manager = manager();

            try {
                new StreamPublisherFactory().provision(declaredConfig(), deployed(publisherContext(manager)))
                                            .await()
                                            .onFailure(cause -> fail("Provisioning failed: " + cause.message()));

                assertMaterializedOnlyAs(manager, QUALIFIED, ALIAS);
            } finally {
                manager.close();
            }
        }

        @Test
        void streamAccessFactory_provision_materializesUnderQualifiedKey_forNonSystemNamespace() {
            var manager = manager();

            try {
                new StreamAccessFactory().provision(declaredConfig(), deployed(accessContext(manager)))
                                         .await()
                                         .onFailure(cause -> fail("Provisioning failed: " + cause.message()));

                assertMaterializedOnlyAs(manager, QUALIFIED, ALIAS);
            } finally {
                manager.close();
            }
        }

        /// The publish and read sides must land on ONE ring. Qualifying only the writer would leave
        /// readers on the bare name — splitting the world differently rather than healing it — and
        /// that state passes both single-factory tests above.
        @Test
        void bothFactories_provision_shareOneEngineKey_forNonSystemNamespace() {
            var manager = manager();

            try {
                new StreamPublisherFactory().provision(declaredConfig(), deployed(publisherContext(manager)))
                                            .await()
                                            .onFailure(cause -> fail("Publisher provisioning failed: " + cause.message()));
                new StreamAccessFactory().provision(declaredConfig(), deployed(accessContext(manager)))
                                         .await()
                                         .onFailure(cause -> fail("Access provisioning failed: " + cause.message()));

                assertMaterializedOnlyAs(manager, QUALIFIED, ALIAS);
            } finally {
                manager.close();
            }
        }
    }

    @Nested
    class UndeployedRuntime {
        /// Unit tests, Forge/Ember and programmatic streams carry no resolver, because there is no
        /// deployment behind them and so no catalog spelling to agree with. The bare name is the only
        /// identity in play and must survive untouched.
        @Test
        void streamPublisherFactory_provision_materializesUnderBareName_whenContextCarriesNoResolver() {
            var manager = manager();

            try {
                new StreamPublisherFactory().provision(declaredConfig(), publisherContext(manager))
                                            .await()
                                            .onFailure(cause -> fail("Provisioning failed: " + cause.message()));

                assertMaterializedOnlyAs(manager, ALIAS, QUALIFIED);
            } finally {
                manager.close();
            }
        }

        /// A resolver with no slice identity alongside it cannot name a blueprint, so it must not guess.
        @Test
        void qualify_leavesConfigUnchanged_whenSliceIdAbsent() {
            var context = ProvisioningContext.provisioningContext()
                                             .withExtension(StreamAddressResolver.class, bindingResolver());

            assertThat(StreamAddressResolver.qualify(declaredConfig(), context).name()).isEqualTo(ALIAS);
        }
    }

    @Nested
    class SystemBareReduction {
        /// `system` streams keep their bare key — `StreamManager.engineKey` reduces the `system`
        /// namespace deliberately and `SystemStreams.isForbiddenEngineKey` depends on that spelling for
        /// the management-api write gate. A resolver that qualified them would move `cluster-events`
        /// out from under the gate, which is a security boundary, not a naming preference.
        @Test
        void qualify_keepsBareName_whenResolverReducesSystemNamespace() {
            var systemConfig = StreamConfig.streamConfig("cluster-events",
                                                         1,
                                                         RETENTION,
                                                         "earliest",
                                                         1024 * 1024L,
                                                         ConsistencyMode.EVENTUAL,
                                                         0);
            StreamAddressResolver systemResolver = (_, alias) -> alias;
            var context = ProvisioningContext.provisioningContext()
                                             .withExtension(StreamAddressResolver.class, systemResolver)
                                             .withExtension(String.class, SLICE_ID);

            assertThat(StreamAddressResolver.qualify(systemConfig, context).name()).isEqualTo("cluster-events");
        }
    }

    @Nested
    class ConfigRename {
        /// `withName` substitutes the name and nothing else. A rename that dropped `minSyncReplicas` or
        /// `replicas` would restore #1040's observable harm — a void durability contract — by a
        /// different route, and would do it while every key-equality assertion above still passed.
        @Test
        void withName_replacesNameAndPreservesEveryOtherField() {
            var original = StreamConfig.streamConfig(ALIAS,
                                                     3,
                                                     RETENTION,
                                                     "earliest",
                                                     2048L,
                                                     ConsistencyMode.STRONG,
                                                     2,
                                                     2,
                                                     StreamCompression.LZ4,
                                                     Option.some("key-1"));
            var renamed = original.withName(QUALIFIED);

            assertThat(renamed.name()).isEqualTo(QUALIFIED);
            assertThat(renamed).isEqualTo(original.withName(QUALIFIED));
            assertThat(renamed.withName(ALIAS)).isEqualTo(original);
        }
    }

    private static Serializer identitySerializer() {
        return new Serializer() {
            @SuppressWarnings("unchecked") @Override public <T> byte[] encode(T object) {return (byte[]) object;}

            @Override public <T> void write(ByteBuf byteBuf, T object) {byteBuf.writeBytes((byte[]) object);}
        };
    }

    private static Deserializer identityDeserializer() {
        return new Deserializer() {
            @SuppressWarnings("unchecked") @Override public <T> T decode(byte[] bytes) {return (T) bytes;}

            @SuppressWarnings("unchecked") @Override public <T> T read(ByteBuf byteBuf) {
                var bytes = new byte[byteBuf.readableBytes()];
                byteBuf.readBytes(bytes);
                return (T) bytes;
            }
        };
    }
}
