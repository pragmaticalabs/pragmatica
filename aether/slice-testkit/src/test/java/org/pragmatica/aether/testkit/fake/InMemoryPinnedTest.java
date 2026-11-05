// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.testkit.fake;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.Unit.unit;

/// FDB-1 (#2591): the public fakes implement `pinned` (a user's fake inherits the refusing default otherwise): the body
/// runs on the fake itself, then the release does, and the result is the body's.
class InMemoryPinnedTest {
    @Test
    void pinned_inMemorySqlConnector_runsBodyThenRelease_onTheFakeItself() {
        var fake = InMemorySqlConnector.scripted();
        var order = new ArrayList<String>();

        var result = fake.pinned(Duration.ZERO, session -> step(order, "body", session, fake), session -> release(order, session, fake)).await();

        assertThat(result.isSuccess()).isTrue();
        assertThat(order).containsExactly("body", "release");
    }

    @Test
    void pinned_inMemoryPgSqlConnector_bodyFailureStillReleases_andIsReturned() {
        var fake = InMemoryPgSqlConnector.scripted();
        var order = new ArrayList<String>();
        var failure = Causes.cause("boom");

        var result = fake.<Unit> pinned(Duration.ZERO, _ -> failed(order, failure), session -> release(order, session, fake)).await();

        assertThat(order).containsExactly("body", "release");
        result.onSuccess(_ -> fail("must fail"))
              .onFailure(cause -> assertThat(cause).isSameAs(failure));
    }

    private static Promise<Unit> step(List<String> order, String name, SqlConnector session, SqlConnector expected) {
        assertThat(session).isSameAs(expected);
        order.add(name);

        return Promise.success(unit());
    }

    private static Promise<Unit> release(List<String> order, SqlConnector session, SqlConnector expected) {
        return step(order, "release", session, expected);
    }

    private static Promise<Unit> failed(List<String> order, Cause failure) {
        order.add("body");

        return failure.promise();
    }
}
