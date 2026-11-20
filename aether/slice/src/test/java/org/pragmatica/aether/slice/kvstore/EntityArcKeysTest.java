// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterStateKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.EntityArcWaiverKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.EntitySeedOfferKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.RuntimeKey;

import static org.assertj.core.api.Assertions.assertThat;

/// The two arc keys of #3303 A3a: the seed offer `(keyspace, parent)` and the arc waiver `(keyspace, child)`.
///
/// Both are cluster state and backed up (design r8.2 l.476). Both split their identity on the LAST separator, because a
/// keyspace name is author-supplied and stored raw, so the id is always the final component.
class EntityArcKeysTest {
    @Test
    void seedOffer_asString_carriesTheKeyspaceThenTheParent() {
        assertThat(EntitySeedOfferKey.entitySeedOfferKey("orders", 7)
                                     .asString()).isEqualTo("entity-seed-offer/orders/7");
    }

    @Test
    void arcWaiver_asString_carriesTheKeyspaceThenTheChild() {
        assertThat(EntityArcWaiverKey.entityArcWaiverKey("orders", 7)
                                     .asString()).isEqualTo("entity-arc-waiver/orders/7");
    }

    @Test
    void toString_matchesAsString() {
        var offer = EntitySeedOfferKey.entitySeedOfferKey("orders", 7);
        var waiver = EntityArcWaiverKey.entityArcWaiverKey("orders", 7);

        assertThat(offer.toString()).isEqualTo(offer.asString());
        assertThat(waiver.toString()).isEqualTo(waiver.asString());
    }

    @Test
    void seedOffer_roundTrips_throughTheFullString() {
        var key = EntitySeedOfferKey.entitySeedOfferKey("orders", 7);

        EntitySeedOfferKey.entitySeedOfferKey(key.asString())
                          .onFailureRun(Assertions::fail)
                          .onSuccess(parsed -> assertThat(parsed).isEqualTo(key));
    }

    @Test
    void arcWaiver_roundTrips_throughTheFullString() {
        var key = EntityArcWaiverKey.entityArcWaiverKey("orders", 7);

        EntityArcWaiverKey.entityArcWaiverKey(key.asString())
                          .onFailureRun(Assertions::fail)
                          .onSuccess(parsed -> assertThat(parsed).isEqualTo(key));
    }

    /// The LAST-separator rule: a keyspace containing `/` round-trips whole. A first-separator split would hand back keyspace
    /// `a` here.
    @Test
    void seedOffer_roundTrips_forAKeyspaceContainingTheSeparator() {
        var key = EntitySeedOfferKey.entitySeedOfferKey("a/b/c", 3);

        EntitySeedOfferKey.fromIdentity("a/b/c/3")
                          .onFailureRun(Assertions::fail)
                          .onSuccess(parsed -> assertThat(parsed).isEqualTo(key));
    }

    @Test
    void arcWaiver_roundTrips_forAKeyspaceContainingTheSeparator() {
        var key = EntityArcWaiverKey.entityArcWaiverKey("a/b/c", 3);

        EntityArcWaiverKey.fromIdentity("a/b/c/3")
                          .onFailureRun(Assertions::fail)
                          .onSuccess(parsed -> assertThat(parsed).isEqualTo(key));
    }

    @Test
    void seedOffer_malformedIdentity_fails() {
        assertThat(EntitySeedOfferKey.fromIdentity("orders")
                                     .isFailure()).isTrue();
        assertThat(EntitySeedOfferKey.fromIdentity("/7")
                                     .isFailure()).isTrue();
        assertThat(EntitySeedOfferKey.fromIdentity("orders/")
                                     .isFailure()).isTrue();
        assertThat(EntitySeedOfferKey.fromIdentity("orders/x")
                                     .isFailure()).isTrue();
        assertThat(EntitySeedOfferKey.fromIdentity("")
                                     .isFailure()).isTrue();
    }

    @Test
    void arcWaiver_malformedIdentity_fails() {
        assertThat(EntityArcWaiverKey.fromIdentity("orders")
                                     .isFailure()).isTrue();
        assertThat(EntityArcWaiverKey.fromIdentity("/7")
                                     .isFailure()).isTrue();
        assertThat(EntityArcWaiverKey.fromIdentity("orders/")
                                     .isFailure()).isTrue();
        assertThat(EntityArcWaiverKey.fromIdentity("orders/x")
                                     .isFailure()).isTrue();
        assertThat(EntityArcWaiverKey.fromIdentity("")
                                     .isFailure()).isTrue();
    }

    @Test
    void wrongSectionPrefix_fails_andEachKeyRefusesTheOthers() {
        assertThat(EntitySeedOfferKey.entitySeedOfferKey("entity-arc-waiver/orders/7")
                                     .isFailure()).isTrue();
        assertThat(EntityArcWaiverKey.entityArcWaiverKey("entity-seed-offer/orders/7")
                                     .isFailure()).isTrue();
    }

    @Test
    void malformedFullString_namesTheFullKeyInTheCause() {
        EntitySeedOfferKey.fromIdentity("orders")
                          .onSuccess(parsed -> Assertions.fail("must not parse: " + parsed))
                          .onFailure(cause -> assertThat(cause.message()).contains("entity-seed-offer/orders"));
        EntityArcWaiverKey.fromIdentity("orders")
                          .onSuccess(parsed -> Assertions.fail("must not parse: " + parsed))
                          .onFailure(cause -> assertThat(cause.message()).contains("entity-arc-waiver/orders"));
    }

    /// The numeric-parse failure carries the same full-key text as the shape failure, prefix included (vA3b survivors
    /// s/w-err-prefix): the cause names the key class, not just the identity.
    @Test
    void nonNumericId_namesThePrefixedKeyInTheCause() {
        EntitySeedOfferKey.fromIdentity("orders/x")
                          .onSuccess(parsed -> Assertions.fail("must not parse: " + parsed))
                          .onFailure(cause -> assertThat(cause.message()).isEqualTo("Invalid entity-seed-offer key format: entity-seed-offer/orders/x"));
        EntityArcWaiverKey.fromIdentity("orders/x")
                          .onSuccess(parsed -> Assertions.fail("must not parse: " + parsed))
                          .onFailure(cause -> assertThat(cause.message()).isEqualTo("Invalid entity-arc-waiver key format: entity-arc-waiver/orders/x"));
    }

    /// Both are cluster state and backed up: losing either on a cold restart would silently drop an arc decision.
    /// Typed as [AetherKey] so a key moved to the runtime class fails here by assertion, not by a missing method.
    @Test
    void bothKeys_areClusterStateAndBackedUp() {
        AetherKey offer = EntitySeedOfferKey.entitySeedOfferKey("orders", 7);
        AetherKey waiver = EntityArcWaiverKey.entityArcWaiverKey("orders", 7);

        assertClusterStateAndBackedUp(offer);
        assertClusterStateAndBackedUp(waiver);
    }

    private static void assertClusterStateAndBackedUp(AetherKey key) {
        assertThat(key).isNotInstanceOf(RuntimeKey.class);
        assertThat(key).isInstanceOf(ClusterStateKey.class);
        assertThat(((ClusterStateKey) key).isBackedUp()).isTrue();
    }
}
