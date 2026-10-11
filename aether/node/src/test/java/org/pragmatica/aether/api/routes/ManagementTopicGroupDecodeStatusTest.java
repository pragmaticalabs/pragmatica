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
import org.pragmatica.http.HttpStatus;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// #1921 (c): `TOPICS_GROUP_REBUILD` percent-decodes its group segment with `URLDecoder.decode`, which THROWS on a malformed
/// escape. The throw happened inside the handler's flatMap, so the caller's typo (`...%zz`) never became a response status.
class ManagementTopicGroupDecodeStatusTest {
    private static final List<String> ADDRESS = List.of("ns", "events", "1.0.0", "rebuild");

    @Test
    void topicsGroupRebuild_answers400_whenTheGroupHasAMalformedPercentEscape() {
        var failure = RouteProbe.failureOf(routes(new AtomicReference<>()).routes(),
                                           ManagementRoute.TOPICS_GROUP_REBUILD,
                                           withGroup("billing%zz"),
                                           Map.of());

        assertThat(RouteProbe.problemStatus(failure)).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(failure.message()).contains("billing%zz");
    }

    @Test
    void topicsGroupRebuild_answers400_whenTheGroupEndsInALoneEscapeMarker() {
        var failure = RouteProbe.failureOf(routes(new AtomicReference<>()).routes(),
                                           ManagementRoute.TOPICS_GROUP_REBUILD,
                                           withGroup("billing%"),
                                           Map.of());

        assertThat(RouteProbe.problemStatus(failure)).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /// Control: a well-formed escape still decodes and reaches the node, so the 400 is the decode refusal and not a blanket
    /// refusal of group names that contain `%`.
    @Test
    void topicsGroupRebuild_reachesTheNodeWithTheDecodedGroup_whenTheEscapeIsWellFormed() {
        var reached = new AtomicReference<String>();

        try {
            RouteProbe.run(routes(reached).routes(), ManagementRoute.TOPICS_GROUP_REBUILD, withGroup("artifact%23method"), Map.of());
        } catch (UnsupportedOperationException expected) {
            // the probe node refuses every call: reaching it is the whole assertion
        }

        assertThat(reached.get()).isNotNull();
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
