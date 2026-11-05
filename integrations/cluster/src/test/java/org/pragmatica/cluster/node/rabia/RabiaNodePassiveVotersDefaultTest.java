// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.node.rabia;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.rabia.ReconfigurationError;
import org.pragmatica.consensus.rabia.VoterConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;

/// #2543: `RabiaNode.installPassiveVoters`'s interface default is the answer of every node that does not delegate to a passive engine
/// (test doubles, any future wrapper). It must refuse, never report an install that did not happen. The node is a proxy that runs only
/// default methods, so nothing but the default is reached; mutation: return success from the default.
class RabiaNodePassiveVotersDefaultTest {
    @Test
    void installPassiveVoters_onANodeThatDoesNotOverrideIt_isRefusedAsNotPassive() {
        InvocationHandler defaultsOnly = (proxy, method, args) -> {
            if (!method.isDefault()) {
                throw new AssertionError("only the default may be reached, not " + method.getName());
            }

            return InvocationHandler.invokeDefault(proxy, method, args);
        };
        var node = (RabiaNode<?>) Proxy.newProxyInstance(RabiaNode.class.getClassLoader(), new Class<?>[]{RabiaNode.class}, defaultsOnly);
        var roster = VoterConfiguration.voterConfiguration(1, List.of(nodeId("core-1").unwrap(), nodeId("core-2").unwrap())).unwrap();

        node.installPassiveVoters(roster)
            .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("a node with no passive engine behind it reported an install"))
            .onFailure(cause -> assertThat(cause).isEqualTo(ReconfigurationError.NOT_PASSIVE_CLIENT));
    }
}
