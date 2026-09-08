// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Supplier;

import org.pragmatica.aether.slice.blueprint.SecurityOverridePolicy;
import org.pragmatica.aether.slice.blueprint.SecurityOverrides;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Unit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Keeps THIS node's [`HttpRoutePublisher`] security overrides in step with the replicated blueprint
/// state (#887 E2/E3).
///
/// ## What was wrong
///
/// `SecurityOverrides` reached exactly one node. `SliceRoutes.pushSecurityOverrides` called
/// `updateSecurityOverrides` in-process on `nodeSupplier.get()`, i.e. on whichever node served
/// `POST /api/v1/blueprints` — a `taskGroup(DEPLOYMENT)` route, so one node, and one whose ownership
/// migrates. Every other node held `SecurityOverrides.EMPTY`. A slice hosted on a node that did not
/// serve that request therefore published its cluster route entry built from EMPTY overrides, so the
/// override was absent from the KV entry as well and NO node enforced it — not even the remote
/// readers #887 assumed were safe.
///
/// The data was never missing. `AppBlueprintValue` carries the whole `ExpandedBlueprint`, including
/// `securityOverrides()`, and is replicated to every node and durable across restarts. What was
/// missing was a READER. This class is that reader; it introduces no new distributed state.
///
/// ## Why derive, rather than push
///
/// A pushed value is a copy that must be kept in step. Deriving from the store on every change means
/// there is nothing to keep in step: the replicated blueprint IS the source, and every node computes
/// the same answer from it.
///
/// That is also what earns E3. `activeOverrides` is in-memory, so a node restart or a DEPLOYMENT
/// task-group migration used to silently re-publish routes with EMPTY overrides. Because `resync` is
/// driven by [`ClusterStateNotification`] `ACTIVE` as well as by blueprint changes, a node that
/// restarts re-derives the overrides from the restored store instead of coming up without them.
/// Rebuilt-from-store paths that rebuild EMPTY are a repeat failure mode here, which is why the
/// restart path is exercised rather than reasoned about.
///
/// ## Determinism
///
/// Every node runs the same derivation over consensus-ordered state, and blueprints are sorted by id
/// before their entries are concatenated, so the resulting order — which decides
/// `SecurityOverrides.findMatch`'s first-match — is identical on every node and after any restart. A
/// derivation whose result depended on the order puts happened to arrive in would reproduce the
/// node-dependent enforcement this fixes.
public interface SecurityOverrideSynchronizer {
    Logger log = LoggerFactory.getLogger(SecurityOverrideSynchronizer.class);

    @SuppressWarnings("JBCT-RET-01")
    void onAppBlueprintPut(ValuePut<AppBlueprintKey, AppBlueprintValue> valuePut);

    @SuppressWarnings("JBCT-RET-01")
    void onAppBlueprintRemove(ValueRemove<AppBlueprintKey, AppBlueprintValue> valueRemove);

    @SuppressWarnings("JBCT-RET-01")
    void onQuorumStateChange(ClusterStateNotification notification);

    /// Re-derive from the store and install on the local publisher. Idempotent.
    Unit resync();

    /// The derivation, exposed so it can be tested without a live node.
    ///
    /// A `registerOnly` blueprint is registered but NOT activated, so its overrides must not govern
    /// anything — mirroring `SliceRoutes.onBlueprintActivated`, which fires on the deploy/publish
    /// paths and never on `handleBlueprintPublish`.
    ///
    /// Policy resolution across several contributing blueprints has no defined answer, and this does
    /// not invent one silently: a single contributor's policy is used as-is (the case that exists in
    /// practice, and exactly today's semantics), while a genuine conflict is logged naming the
    /// blueprints and falls back to `STRENGTHEN_ONLY`, which is `SecurityOverrides.EMPTY`'s own
    /// default. The previous behaviour in the same situation was last-writer-wins, and differed per
    /// node.
    static SecurityOverrides deriveOverrides(KVStore<AetherKey, AetherValue> store) {
        var contributors = new ArrayList<Contribution>();

        store.forEach(AppBlueprintKey.class,
                      AppBlueprintValue.class,
                      (key, value) -> collectContribution(contributors, key, value));
        if (contributors.isEmpty()) {
            return SecurityOverrides.EMPTY;
        }

        contributors.sort(Comparator.comparing(Contribution::blueprintId));
        var entries = new ArrayList<SecurityOverrides.Entry>();

        contributors.forEach(contribution -> entries.addAll(contribution.overrides().entries()));

        return SecurityOverrides.securityOverrides(entries, resolvePolicy(contributors));
    }

    private static void collectContribution(List<Contribution> contributors,
                                            AppBlueprintKey key,
                                            AppBlueprintValue value) {
        if (value.registerOnly()) {
            return;
        }

        var overrides = value.blueprint().securityOverrides();

        if (!overrides.isEmpty()) {
            contributors.add(new Contribution(key.blueprintId().asString(),
                                              overrides));
        }
    }

    private static SecurityOverridePolicy resolvePolicy(List<Contribution> contributors) {
        var policies = new LinkedHashSet<SecurityOverridePolicy>();

        contributors.forEach(contribution -> policies.add(contribution.overrides().policy()));
        if (policies.size() == 1) {
            return policies.iterator()
                           .next();
        }

        log.warn("Conflicting security-override policies across active blueprints {}: {} — falling back to {}. "
                + "Give the blueprints one policy; this fallback is a refusal to guess, not a resolution.",
                 contributors.stream().map(Contribution::blueprintId).toList(),
                 policies,
                 SecurityOverridePolicy.STRENGTHEN_ONLY);

        return SecurityOverridePolicy.STRENGTHEN_ONLY;
    }

    record Contribution(String blueprintId, SecurityOverrides overrides) {}

    static SecurityOverrideSynchronizer securityOverrideSynchronizer(KVStore<AetherKey, AetherValue> store,
                                                                     Supplier<Option<HttpRoutePublisher>> publisherSource) {
        record securityOverrideSynchronizer(KVStore<AetherKey, AetherValue> store,
                                            Supplier<Option<HttpRoutePublisher>> publisherSource) implements SecurityOverrideSynchronizer {
            @Override
            @SuppressWarnings("JBCT-RET-01")
            public void onAppBlueprintPut(ValuePut<AppBlueprintKey, AppBlueprintValue> valuePut) {
                resync();
            }

            @Override
            @SuppressWarnings("JBCT-RET-01")
            public void onAppBlueprintRemove(ValueRemove<AppBlueprintKey, AppBlueprintValue> valueRemove) {
                resync();
            }

            /// A node that has just become ACTIVE has restored state it did not observe arriving.
            /// Without this, a restart or a DEPLOYMENT task-group migration would leave the node
            /// enforcing nothing until the next blueprint change — the E3 failure.
            @Override
            @SuppressWarnings("JBCT-RET-01")
            public void onQuorumStateChange(ClusterStateNotification notification) {
                if (notification.state() == ClusterStateNotification.State.ACTIVE) {
                    resync();
                }
            }

            @Override
            public Unit resync() {
                var overrides = deriveOverrides(store);

                return publisherSource.get()
                                      .map(publisher -> publisher.updateSecurityOverrides(overrides))
                                      .or(() -> logNoPublisher(overrides));
            }

            private static Unit logNoPublisher(SecurityOverrides overrides) {
                if (!overrides.isEmpty()) {
                    log.warn("Security overrides ({} entries) could not be installed: no HTTP route publisher on this "
                            + "node yet. They will be installed at the next blueprint change or ACTIVE transition.",
                             overrides.entries().size());
                }

                return Unit.unit();
            }
        }

        return new securityOverrideSynchronizer(store, publisherSource);
    }
}
