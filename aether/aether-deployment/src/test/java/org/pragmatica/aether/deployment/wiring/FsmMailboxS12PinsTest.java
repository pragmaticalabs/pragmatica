// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.wiring;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// F1 mutation survivor pin (run s1, `wiring-FsmMailbox-09`): the consumer loop runs while the mailbox is open. With the loop's guard negated the
/// consumer exits at once and runs `onStop` before anything was offered, so a started mailbox handles nothing; in a full module run that mutant
/// hung a surefire fork for 3 h. This pin detects it inside a bound: every await is bounded and the test itself carries a preemptive timeout.
class FsmMailboxS12PinsTest {
    private final List<String> handled = new CopyOnWriteArrayList<>();
    private final AtomicInteger stops = new AtomicInteger();
    private FsmMailbox<String> mailbox;

    @AfterEach
    void close() {
        if (mailbox != null) {
            mailbox.close();
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void startedMailbox_handlesAnOfferedItem_andDoesNotStopBeforeClose() {
        mailbox = FsmMailbox.fsmMailbox("deploy-fsm-pin", handled::add, stops::incrementAndGet);
        mailbox.start();

        assertThat(mailbox.offer("first")).as("open: the offer is accepted").isTrue();
        await().atMost(Duration.ofSeconds(5)).until(() -> handled.contains("first"));

        assertThat(stops).as("the consumer has not stopped while the mailbox is open").hasValue(0);
        assertThat(mailbox.running()).as("the consumer is alive while the mailbox is open").isTrue();

        mailbox.close();

        assertThat(stops).as("close ends the loop and runs onStop once").hasValue(1);
    }
}
