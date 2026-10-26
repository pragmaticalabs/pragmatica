// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.node.ManageableNode;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// #2420: `TOPICS_GROUP_REBUILD` takes its group segment as the HTTP server delivered it, percent-decoded once. It used to
/// decode the segment again with `URLDecoder.decode` (#1921 (c) made a malformed escape a 400 there), which read a `%` that
/// is part of a group name as an escape. A malformed escape in the request path is now refused by the server itself (400,
/// `RequestTargetParityTest`), so it never reaches this handler.
class ManagementTopicGroupDecodeStatusTest {
    private static final List<String> ADDRESS = List.of("ns", "events", "1.0.0", "rebuild");

    @Test
    void topicsGroupRebuild_doesNotDecodeTheGroupAgain_whenItCarriesAPercent() {
        for (var group : List.of("billing%zz", "billing%", "artifact%23method")) {
            var reached = new AtomicReference<String>();

            try {
                RouteProbe.run(routes(reached).routes(), ManagementRoute.TOPICS_GROUP_REBUILD, withGroup(group), Map.of());
            } catch (UnsupportedOperationException expected) {
                // the probe node refuses every call: reaching it is the whole assertion
            }

            assertThat(reached.get()).as("group '%s' is a name, not an escape: the handler goes on to the node", group)
                                     .isNotNull();
        }
    }

    private static List<String> withGroup(String group) {
        return java.util.stream.Stream.concat(java.util.stream.Stream.of("ns", "events", "1.0.0", "rebuild"), java.util.stream.Stream.of(group))
                                      .toList();
    }

    private static TopicRoutes routes(AtomicReference<String> reached) {
        return TopicRoutes.topicRoutes(() -> (ManageableNode) Proxy.newProxyInstance(ManageableNode.class.getClassLoader(),
                                                                                    new Class[]{ManageableNode.class},
                                                                                    (_, method, _) -> {
                                                                                        reached.set(method.getName());
                                                                                        throw new UnsupportedOperationException(method.getName());
                                                                                    }));
    }
}
