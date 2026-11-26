// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import java.time.Duration;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.pragmatica.lang.Option;

import static org.awaitility.Awaitility.await;


/// #3812: waits on the fields of `GET /cluster/communities/{id}`, one field per wait.
///
/// The route promises each field its own thing and no relation between them: `state` is the committed community row, `liveMembers` is
/// the leader's instantaneous observation, and each request re-resolves the leader. The committed state follows the evaluation by a
/// consensus round, so a response can carry (DEGRADED, 3) for a moment. What the product does guarantee is that each field reaches its
/// value eventually; so each is awaited on its own, and no predicate pairs the two. An absent count never satisfies a wait.
sealed interface CommunityAwaits {
    static void awaitState(Supplier<String> route, String state, Duration atMost) {
        await().atMost(atMost).until(() -> field(route.get(), "state").equals(Option.some(state)));
    }

    static void awaitLiveMembersBelow(Supplier<String> route, int formed, Duration atMost) {
        await().atMost(atMost).until(() -> liveMembers(route.get()).map(count -> count < formed)
                                                      .or(false));
    }

    static void awaitLiveMembersEqual(Supplier<String> route, int formed, Duration atMost) {
        await().atMost(atMost).until(() -> liveMembers(route.get()).equals(Option.some(formed)));
    }

    private static Option<Integer> liveMembers(String json) {
        return field(json, "liveMembers").filter(text -> text.chars()
                                                             .allMatch(Character::isDigit))
                    .map(Integer::parseInt);
    }

    private static Option<String> field(String json, String name) {
        var matcher = Pattern.compile("\"" + name + "\":\"?([A-Za-z0-9_-]+)\"?").matcher(json);

        return matcher.find()
               ? Option.some(matcher.group(1))
               : Option.none();
    }

    record unused() implements CommunityAwaits {}
}
