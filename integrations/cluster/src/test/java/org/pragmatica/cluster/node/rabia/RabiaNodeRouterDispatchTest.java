/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.pragmatica.cluster.node.rabia;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.messaging.Message;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.messaging.MessageRouter.Entry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.fail;

/// `buildAndWireRouter` installs its OWN `ImmutableRouter` whose `route` bypasses
/// `MessageRouter.dispatchOne` and its try/catch. Before the fix a handler throw escaped straight to
/// the transport's dispatch thread with no log line — the same silence class as the #492 encode
/// swallow, one hop later — and every handler registered AFTER the thrower was skipped for that
/// message.
///
/// The skipped-siblings half is the one that bites in production: handlers for a single message type
/// are independent subsystems, and one of them failing must not silently disable the others.
class RabiaNodeRouterDispatchTest {
    /// Local (not wired) so the test needs no codec — routing is keyed on the concrete class either
    /// way, and this file is about dispatch, not serialization.
    record RoutedProbe(String payload) implements Message.Local {}

    /// Registered BEFORE the throwing handler, to prove ordering is preserved and the chain really is
    /// entered at the top rather than restarted.
    @Test
    void route_handlerThrows_stillInvokesTheRemainingHandlers_andNeverPropagates() {
        var observed = new ArrayList<String>();
        var router = router(Entry.route(RoutedProbe.class, message -> observed.add("before:" + message.payload())),
                            Entry.route(RoutedProbe.class, message -> recordThenThrow(observed, message)),
                            Entry.route(RoutedProbe.class, message -> observed.add("after:" + message.payload())));

        assertThatCode(() -> router.route(new RoutedProbe("x")))
            .as("a handler throw must never reach the transport's dispatch thread")
            .doesNotThrowAnyException();
        assertThat(observed)
            .as("the handler AFTER the thrower must still run — independent subsystems share a type,"
                + " and one failing must not silently disable the rest")
            .containsExactly("before:x", "entered:x", "after:x");
    }

    /// The arming counterpart. Without it, the test above would pass against a router that had simply
    /// stopped dispatching the throwing handler at all — or never registered it — so the "throw was
    /// absorbed" reading would be unearned. The throwing handler records BEFORE it throws, so its
    /// entry into the chain is directly observable rather than inferred.
    @Test
    void route_throwingHandlerIsActuallyInvoked_notSilentlySkipped() {
        var reached = new ArrayList<String>();
        var router = router(Entry.route(RoutedProbe.class, message -> recordThenThrow(reached, message)));

        router.route(new RoutedProbe("y"));

        assertThat(reached).as("the throwing handler IS on the chain and IS entered before it fails")
                           .containsExactly("entered:y");
    }

    /// The healthy baseline: with no handler throwing, every handler for the type runs. Distinguishes
    /// "the absorb works" from "the router only ever runs one handler".
    @Test
    void route_noHandlerThrows_invokesEveryHandlerForTheType() {
        var observed = new ArrayList<String>();
        var router = router(Entry.route(RoutedProbe.class, message -> observed.add("first:" + message.payload())),
                            Entry.route(RoutedProbe.class, message -> observed.add("second:" + message.payload())));

        router.route(new RoutedProbe("z"));

        assertThat(observed).containsExactly("first:z", "second:z");
    }

    // === helpers ===

    @SafeVarargs
    private static MessageRouter router(Entry<RoutedProbe>... entries) {
        var wildcarded = new ArrayList<Entry<?>>(List.of(entries));

        return RabiaNode.buildAndWireRouter(MessageRouter.DelegateRouter.delegate(), wildcarded)
                        .fold(cause -> fail("router must build: " + cause.message()), built -> built);
    }

    /// A handler that fails the way a real subsystem does — after doing part of its work, so the
    /// partial effect is observable and the throw is unambiguously reached.
    private static void recordThenThrow(List<String> reached, RoutedProbe message) {
        reached.add("entered:" + message.payload());

        throw new IllegalStateException("handler deliberately failing for " + message.payload());
    }
}
