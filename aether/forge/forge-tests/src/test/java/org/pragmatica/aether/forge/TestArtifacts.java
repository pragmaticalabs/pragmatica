// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.forge;

/// Shared test artifact constants with version derived from the Maven project version.
///
///
/// The version is injected via the `project.version` system property set by
/// the maven-failsafe-plugin configuration. This eliminates hardcoded version
/// strings that break on every version bump.
final class TestArtifacts {
    static final String VERSION = System.getProperty("project.version", "UNKNOWN");
    static final String ECHO_SLICE = "org.pragmatica-lite.aether.test:echo-slice-echo-service:" + VERSION;
    static final String URL_SHORTENER = "org.pragmatica.aether.example:url-shortener-url-shortener:" + VERSION;
    static final String ANALYTICS = "org.pragmatica.aether.example:url-shortener-analytics:" + VERSION;

    private TestArtifacts() {}
}
