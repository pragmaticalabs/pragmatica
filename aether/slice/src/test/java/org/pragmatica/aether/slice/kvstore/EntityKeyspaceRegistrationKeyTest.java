// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey.EntityKeyspaceRegistrationKey;
import org.pragmatica.consensus.NodeId;

import static org.assertj.core.api.Assertions.assertThat;

/// The PER-NODE entity-keyspace registration key (#345 I1 narrow C). The node half is what carries the
/// keyspace's HOSTING set to the leader — a keyspace-wide key could not, and the leader then minted
/// ownership over every member, so every arc owned by a non-hosting node refused every write (02w).
///
/// [AetherKey.EntityKeyspaceRegistrationKey#fromIdentity] splits at the FIRST `/` because a keyspace
/// never contains one while nothing constrains the node-id tail; both halves of that rule are pinned
/// here, since the split is the only thing standing between a dashed or slashed node id and a key that
/// parses back to a different node than it was written for.
class EntityKeyspaceRegistrationKeyTest {
    private static final String SECTION_PREFIX = "entity-keyspace/";
    private static final NodeId NODE = new NodeId("node-3");

    @Test
    void asString_carriesTheKeyspaceThenTheNode() {
        var key = EntityKeyspaceRegistrationKey.entityKeyspaceRegistrationKey("orders", NODE);

        assertThat(key.asString()).isEqualTo("entity-keyspace/orders/node-3");
    }

    @Test
    void toString_matchesAsString() {
        var key = EntityKeyspaceRegistrationKey.entityKeyspaceRegistrationKey("orders", NODE);

        assertThat(key.toString()).isEqualTo(key.asString());
    }

    /// The node id carries dashes, which is what every real Aether node id looks like — a parse that
    /// split on the last separator, or on any dash, would reconstruct a different node.
    @Test
    void fromIdentity_roundTrips_forADashedNodeId() {
        var key = EntityKeyspaceRegistrationKey.entityKeyspaceRegistrationKey("orders", NODE);

        EntityKeyspaceRegistrationKey.fromIdentity(identityOf(key))
                                     .onFailureRun(Assertions::fail)
                                     .onSuccess(parsed -> assertThat(parsed).isEqualTo(key));
    }

    /// The FIRST-separator rule, from the tail side: a node id containing `/` still round-trips whole,
    /// because only the keyspace is constrained to be separator-free. A last-separator split would hand
    /// back keyspace `orders/node` here.
    @Test
    void fromIdentity_roundTrips_forANodeIdContainingTheSeparator() {
        var key = EntityKeyspaceRegistrationKey.entityKeyspaceRegistrationKey("orders", new NodeId("node/3"));

        EntityKeyspaceRegistrationKey.fromIdentity(identityOf(key))
                                     .onFailureRun(Assertions::fail)
                                     .onSuccess(EntityKeyspaceRegistrationKeyTest::assertSlashedTailKeptWhole);
    }

    private static void assertSlashedTailKeptWhole(EntityKeyspaceRegistrationKey parsed) {
        assertThat(parsed.keyspace()).isEqualTo("orders");
        assertThat(parsed.node()
                         .id()).isEqualTo("node/3");
    }

    @Test
    void fromIdentity_noSeparator_fails() {
        assertThat(EntityKeyspaceRegistrationKey.fromIdentity("orders")
                                                .isFailure()).isTrue();
    }

    @Test
    void fromIdentity_emptyKeyspace_fails() {
        assertThat(EntityKeyspaceRegistrationKey.fromIdentity("/node-1")
                                                .isFailure()).isTrue();
    }

    @Test
    void fromIdentity_trailingSeparator_fails() {
        assertThat(EntityKeyspaceRegistrationKey.fromIdentity("orders/")
                                                .isFailure()).isTrue();
    }

    @Test
    void fromIdentity_emptyIdentity_fails() {
        assertThat(EntityKeyspaceRegistrationKey.fromIdentity("")
                                                .isFailure()).isTrue();
    }

    /// A refused identity names the offending key WITH its section prefix, so a snapshot-restore failure
    /// points at the stored key rather than at a bare fragment.
    @Test
    void fromIdentity_noSeparator_namesTheFullKeyInTheCause() {
        EntityKeyspaceRegistrationKey.fromIdentity("orders")
                                     .onSuccess(parsed -> Assertions.fail("must not parse: " + parsed))
                                     .onFailure(cause -> assertThat(cause.message()).contains("entity-keyspace/orders"));
    }

    /// The snapshot identity is the key minus its section prefix — what `KVStoreSerializer` hands
    /// `fromIdentity`.
    private static String identityOf(EntityKeyspaceRegistrationKey key) {
        return key.asString()
                  .substring(SECTION_PREFIX.length());
    }
}
