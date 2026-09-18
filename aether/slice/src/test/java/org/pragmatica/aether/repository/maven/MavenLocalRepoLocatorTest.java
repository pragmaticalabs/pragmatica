// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.repository.maven;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.repository.maven.MavenLocalRepoLocator;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Pins the local-repository resolution order. Previously this class only printed the
/// resolved path and asserted nothing, so it passed for every possible implementation
/// — including one that ignored `maven.repo.local` entirely.
class MavenLocalRepoLocatorTest {
    private static final String PROPERTY = "maven.repo.local";

    private final String saved = System.getProperty(PROPERTY);

    @AfterEach
    void restore() {
        if (saved == null) {
            System.clearProperty(PROPERTY);
        } else {
            System.setProperty(PROPERTY, saved);
        }
    }

    @Test
    void systemPropertyTakesPrecedence() {
        var expected = new File(System.getProperty("java.io.tmpdir"), "locator-precedence-repo").getAbsolutePath();

        System.setProperty(PROPERTY, expected);

        assertEquals(expected, MavenLocalRepoLocator.findLocalRepository());
    }

    /// The property is filtered for emptiness, so a blank value must NOT win — otherwise
    /// an unset-but-exported variable would silently resolve the repository to "".
    @Test
    void emptySystemPropertyIsIgnored() {
        System.setProperty(PROPERTY, "");

        var resolved = MavenLocalRepoLocator.findLocalRepository();

        assertFalse(resolved.isEmpty());
        assertNotBlankAbsolute(resolved);
    }

    @Test
    void withoutPropertyResolvesAnAbsolutePath() {
        System.clearProperty(PROPERTY);

        var resolved = MavenLocalRepoLocator.findLocalRepository();

        assertNotBlankAbsolute(resolved);
        assertFalse(resolved.contains("${user.home}"), "unexpanded placeholder in: " + resolved);
        assertFalse(resolved.startsWith("~"), "unexpanded tilde in: " + resolved);
    }

    private static void assertNotBlankAbsolute(String resolved) {
        assertFalse(resolved.isBlank());
        assertTrue(new File(resolved).isAbsolute(), "expected an absolute path, got: " + resolved);
    }
}
