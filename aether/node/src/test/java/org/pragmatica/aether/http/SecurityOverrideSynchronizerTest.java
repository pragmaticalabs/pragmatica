// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.http;

import io.netty.buffer.ByteBuf;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.aether.slice.blueprint.SecurityOverridePolicy;
import org.pragmatica.aether.slice.blueprint.SecurityOverrides;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// #887 E2/E3 — the derivation that lets EVERY node learn an override, not only the one that served
/// `POST /api/v1/blueprints`.
///
/// These cover the rule in isolation. The consequence an attacker or operator observes — a node that
/// never served the blueprint request refusing the request over real HTTP — is pinned by
/// `AppHttpServerOverrideEnforcementTest.OverrideArrivesOnlyViaReplicatedBlueprint`, and the
/// multi-node run is recorded in the report.
class SecurityOverrideSynchronizerTest {
    private static final String ROUTE_PATTERN = "GET /undeclared/";

    private static KVStore<AetherKey, AetherValue> store() {
        return new KVStore<>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
    }

    private static void putBlueprint(KVStore<AetherKey, AetherValue> store,
                                     String coords,
                                     SecurityOverrides overrides,
                                     boolean registerOnly) {
        var id = BlueprintId.blueprintId(Artifact.artifact(coords).unwrap());
        var blueprint = ExpandedBlueprint.expandedBlueprint(id,
                                                            List.of(),
                                                            Option.none(),
                                                            overrides);

        var command = new KVCommand.Put<AetherKey, AetherValue>(AppBlueprintKey.appBlueprintKey(id),
                                                                 AppBlueprintValue.appBlueprintValue(blueprint,
                                                                                                     registerOnly));

        store.process(store.createBatch(List.of(command)));
    }

    private static SecurityOverrides lockdown(String level) {
        return SecurityOverrides.securityOverrides(List.of(SecurityOverrides.Entry.entry(ROUTE_PATTERN, level)),
                                                    SecurityOverridePolicy.STRENGTHEN_ONLY);
    }

    @Nested
    class DerivesFromReplicatedState {
        /// The whole point of E2: this derivation runs on every node from state every node already
        /// holds, so a node that never served the blueprint request still learns the override.
        @Test
        void deriveOverrides_returnsBlueprintOverrides_whenBlueprintActive() {
            var store = store();

            putBlueprint(store, "com.example:bp:1.0.0", lockdown("role:admin"), false);

            var derived = SecurityOverrideSynchronizer.deriveOverrides(store);

            assertThat(derived.isEmpty()).isFalse();
            assertThat(derived.findMatch("GET", "/undeclared/").or("<none>")).isEqualTo("role:admin");
        }

        @Test
        void deriveOverrides_returnsEmpty_whenNoBlueprints() {
            assertThat(SecurityOverrideSynchronizer.deriveOverrides(store()).isEmpty()).isTrue();
        }

        /// A register-only blueprint is registered, never activated. Its overrides must not govern
        /// anything — otherwise `aether blueprint publish` would silently change enforcement.
        @Test
        void deriveOverrides_ignoresBlueprint_whenRegisterOnly() {
            var store = store();

            putBlueprint(store, "com.example:bp:1.0.0", lockdown("role:admin"), true);

            assertThat(SecurityOverrideSynchronizer.deriveOverrides(store).isEmpty())
                    .as("a registered-but-not-activated blueprint must not install overrides")
                    .isTrue();
        }

        /// An override withdrawn from the blueprint must disappear from the derivation, or a
        /// lockdown could never be lifted without restarting every node.
        @Test
        void deriveOverrides_dropsOverride_whenBlueprintReplacedWithoutIt() {
            var store = store();

            putBlueprint(store, "com.example:bp:1.0.0", lockdown("role:admin"), false);
            assertThat(SecurityOverrideSynchronizer.deriveOverrides(store).isEmpty()).isFalse();

            putBlueprint(store, "com.example:bp:1.0.0", SecurityOverrides.EMPTY, false);

            assertThat(SecurityOverrideSynchronizer.deriveOverrides(store).isEmpty())
                    .as("withdrawing the override from the blueprint must withdraw it from the derivation")
                    .isTrue();
        }
    }

    @Nested
    class DeterministicAcrossNodes {
        /// Every node derives from consensus-ordered state, so the ORDER of the merged entries — which
        /// decides `findMatch`'s first match — must not depend on iteration or arrival order. A
        /// derivation that varied here would reproduce the node-dependent enforcement #887 is about,
        /// one level up.
        @Test
        void deriveOverrides_ordersEntriesByBlueprintId_regardlessOfInsertionOrder() {
            var forward = store();

            putBlueprint(forward, "com.example:aaa:1.0.0", lockdown("role:first"), false);
            putBlueprint(forward, "com.example:zzz:1.0.0", lockdown("role:second"), false);

            var reverse = store();

            putBlueprint(reverse, "com.example:zzz:1.0.0", lockdown("role:second"), false);
            putBlueprint(reverse, "com.example:aaa:1.0.0", lockdown("role:first"), false);

            var fromForward = SecurityOverrideSynchronizer.deriveOverrides(forward);
            var fromReverse = SecurityOverrideSynchronizer.deriveOverrides(reverse);

            assertThat(fromForward.entries())
                    .as("two nodes seeing the same blueprints in different orders must derive the same overrides")
                    .isEqualTo(fromReverse.entries());
            assertThat(fromForward.findMatch("GET", "/undeclared/").or("<none>"))
                    .as("the lowest blueprint id must win the first match, on every node")
                    .isEqualTo("role:first");
        }

        /// Conflicting policies have no defined answer. The fallback is a refusal to guess, and it is
        /// asserted so nobody later reads the silence as agreement.
        @Test
        void deriveOverrides_fallsBackToStrengthenOnly_whenPoliciesConflict() {
            var store = store();
            var full = SecurityOverrides.securityOverrides(List.of(SecurityOverrides.Entry.entry(ROUTE_PATTERN,
                                                                                                 "role:admin")),
                                                            SecurityOverridePolicy.FULL);

            putBlueprint(store, "com.example:aaa:1.0.0", full, false);
            putBlueprint(store, "com.example:zzz:1.0.0", lockdown("role:other"), false);

            assertThat(SecurityOverrideSynchronizer.deriveOverrides(store).policy())
                    .isEqualTo(SecurityOverridePolicy.STRENGTHEN_ONLY);
        }

        @Test
        void deriveOverrides_keepsSinglePolicy_whenOnlyOneBlueprintContributes() {
            var store = store();
            var full = SecurityOverrides.securityOverrides(List.of(SecurityOverrides.Entry.entry(ROUTE_PATTERN,
                                                                                                 "role:admin")),
                                                            SecurityOverridePolicy.FULL);

            putBlueprint(store, "com.example:aaa:1.0.0", full, false);

            assertThat(SecurityOverrideSynchronizer.deriveOverrides(store).policy())
                    .as("one contributor's policy is used as-is; the fallback is only for real conflicts")
                    .isEqualTo(SecurityOverridePolicy.FULL);
        }
    }

    private static Serializer stubSerializer() {
        return new Serializer() {
            @Override
            public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    private static Deserializer stubDeserializer() {
        return new Deserializer() {
            @Override
            public <T> T read(ByteBuf byteBuf) {
                return null;
            }
        };
    }
}
