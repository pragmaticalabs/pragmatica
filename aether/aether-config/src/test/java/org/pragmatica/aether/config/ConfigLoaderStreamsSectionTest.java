// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.config;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.testlogging.LogCapture;

import static org.assertj.core.api.Assertions.assertThat;

/// v1617 on #1725 — `[storage.streams]` only carries `wal_path`; other keys there have no effect. They were dropped
/// silently, so an operator setting `disk_path` there believed it applied. The loader now WARNs, naming them.
/// Mutation that reddens it: drop the `warnIgnoredStreamsKeys` call.
class ConfigLoaderStreamsSectionTest {
    private LogCapture capture;

    @BeforeEach
    void setUp() {
        capture = LogCapture.capture(ConfigLoader.class, Level.WARN, false);
    }

    @AfterEach
    void tearDown() {
        capture.close();
    }

    @Test
    void streamsSectionWithOtherKeys_warnsNamingTheIgnoredKeys() {
        ConfigLoader.loadFromString("""
            [storage.streams]
            wal_path = "/tmp/wal"
            disk_path = "/tmp/streams"
            encrypted = true
            """);

        assertThat(capture.messages(Level.WARN)).as("the keys that have no effect are named")
                                                .anyMatch(message -> message.contains("[disk_path, encrypted]"));
    }

    /// CONTROL — wal_path alone, and other storage sections, produce no such WARN.
    @Test
    void walPathAlone_andOtherSections_doNotWarn() {
        ConfigLoader.loadFromString("""
            [storage.streams]
            wal_path = "/tmp/wal"

            [storage.vault]
            disk_path = "/tmp/vault"
            """);

        assertThat(capture.messages(Level.WARN)).noneMatch(message -> message.contains("[storage.streams]"));
    }
}
