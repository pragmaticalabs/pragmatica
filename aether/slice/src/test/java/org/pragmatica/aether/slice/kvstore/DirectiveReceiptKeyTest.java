// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey.DirectiveReceiptKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeDepartureKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.RuntimeKey;
import org.pragmatica.consensus.NodeId;

import static org.assertj.core.api.Assertions.assertThat;

/// #3828 lane 1: the directive receipt's key, modelled on `NodeDepartureKey`. The key's string form round-trips, a foreign prefix or a
/// blank node id is refused, and the key is a cluster-FSM-only key: the worker replica excludes it while it keeps the departure record.
class DirectiveReceiptKeyTest {
    private static final NodeId NODE = NodeId.nodeId("worker-9").unwrap();

    @Test
    void asString_isPrefixedNodeId() {
        assertThat(DirectiveReceiptKey.directiveReceiptKey(NODE).asString()).isEqualTo("directive-receipt/worker-9");
        assertThat(DirectiveReceiptKey.directiveReceiptKey(NODE)).hasToString("directive-receipt/worker-9");
    }

    @Test
    void parse_roundTripsAsString() {
        var key = DirectiveReceiptKey.directiveReceiptKey(NODE);

        DirectiveReceiptKey.directiveReceiptKey(key.asString())
                           .onFailure(cause -> Assertions.fail(cause.message()))
                           .onSuccess(parsed -> assertThat(parsed).isEqualTo(key));
    }

    @Test
    void parse_foreignPrefix_fails() {
        assertThat(DirectiveReceiptKey.directiveReceiptKey("node-departure/worker-9").isFailure()).isTrue();
        assertThat(DirectiveReceiptKey.directiveReceiptKey("worker-9").isFailure()).isTrue();
    }

    @Test
    void parse_blankNodeId_fails() {
        assertThat(DirectiveReceiptKey.directiveReceiptKey("directive-receipt/").isFailure()).isTrue();
    }

    @Test
    void receiptKey_isARuntimeKeyDistinctFromTheDepartureKeyOfTheSameNode() {
        var receipt = DirectiveReceiptKey.directiveReceiptKey(NODE);

        assertThat(receipt).isInstanceOf(RuntimeKey.class);
        assertThat(receipt.asString()).isNotEqualTo(NodeDepartureKey.nodeDepartureKey(NODE).asString());
    }

    @Test
    void workerReplica_excludesTheReceipt_andKeepsTheDepartureRecord() {
        var filter = ReplicaFilter.replicaFilter();

        assertThat(filter.excludes(NodeDepartureKey.nodeDepartureKey(NODE))).as("control: the departure record is INCLUDED").isFalse();
        assertThat(filter.excludes(DirectiveReceiptKey.directiveReceiptKey(NODE))).as("the receipt is cluster FSM only").isTrue();
    }
}
