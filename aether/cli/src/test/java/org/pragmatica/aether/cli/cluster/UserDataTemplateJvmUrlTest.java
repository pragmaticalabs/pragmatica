// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.cluster.RuntimeProfile;
import org.pragmatica.aether.config.cluster.RuntimeType;
import org.pragmatica.lang.Option;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserDataTemplateJvmUrlTest {

    @Nested
    class DeriveJarTag {

        @Test
        void deriveJarTag_returnsPlainSemverTag_forPlainSemver() {
            assertEquals("v1.2.3", UserDataTemplate.deriveJarTag("1.2.3"));
        }

        @Test
        void deriveJarTag_returnsCandidateTag_forPrereleaseSemver() {
            assertEquals("v1.2.3-rc1-candidate", UserDataTemplate.deriveJarTag("1.2.3-rc1"),
                         "Pre-release versions are tracked by moving candidate tags, not immutable v<version> tags");
        }

        @Test
        void deriveJarTag_returnsCandidateTag_forAlphaPrerelease() {
            assertEquals("v1.0.0-alpha.4-candidate", UserDataTemplate.deriveJarTag("1.0.0-alpha.4"));
        }

        @Test
        void deriveJarTag_returnsCandidateTag_forRcPrerelease() {
            assertEquals("v1.0.0-rc1-candidate", UserDataTemplate.deriveJarTag("1.0.0-rc1"));
        }

        @Test
        void deriveJarTag_returnsUnknown_forNullVersion() {
            assertEquals("vunknown", UserDataTemplate.deriveJarTag(null));
        }

        @Test
        void deriveJarTag_returnsUnknown_forBlankVersion() {
            assertEquals("vunknown", UserDataTemplate.deriveJarTag("   "));
        }
    }

    @Nested
    class ResolveJarUrl {

        @Test
        void resolveJarUrl_returnsExplicitOverride_whenProfileSetsJarUrl() {
            var explicit = "https://internal.mirror/aether-node-1.0.0-rc1.jar";
            var profile = RuntimeProfile.runtimeProfile("default",
                                                        RuntimeType.JVM,
                                                        Option.empty(),
                                                        Option.empty(),
                                                        Option.some(explicit));

            assertEquals(explicit, UserDataTemplate.resolveJarUrl(Option.some(profile), "1.0.0-rc1"),
                         "Explicit jar_url override must win over auto-derivation");
        }

        @Test
        void resolveJarUrl_derivesPlainSemverUrl_whenNoOverride() {
            var profile = RuntimeProfile.runtimeProfile("default",
                                                        RuntimeType.JVM,
                                                        Option.empty(),
                                                        Option.empty());

            assertEquals("https://github.com/pragmaticalabs/pragmatica/releases/download/v1.2.3/aether-node.jar",
                         UserDataTemplate.resolveJarUrl(Option.some(profile), "1.2.3"),
                         "Plain semver should derive a v<version> tag and use repo pragmaticalabs/pragmatica");
        }

        @Test
        void resolveJarUrl_derivesCandidateUrl_whenPrereleaseAndNoOverride() {
            var profile = RuntimeProfile.runtimeProfile("default",
                                                        RuntimeType.JVM,
                                                        Option.empty(),
                                                        Option.empty());

            assertEquals("https://github.com/pragmaticalabs/pragmatica/releases/download/v1.0.0-rc1-candidate/aether-node.jar",
                         UserDataTemplate.resolveJarUrl(Option.some(profile), "1.0.0-rc1"),
                         "Pre-release version must derive the moving candidate tag");
        }

        @Test
        void resolveJarUrl_derivesUrl_whenProfileAbsent() {
            assertEquals("https://github.com/pragmaticalabs/pragmatica/releases/download/v1.2.3/aether-node.jar",
                         UserDataTemplate.resolveJarUrl(Option.empty(), "1.2.3"),
                         "Without a runtime profile, the URL is derived from the cluster version alone");
        }

        @Test
        void resolveJarUrl_usesCorrectRepoPath_neverThePragmaticalabsAetherRepo() {
            var url = UserDataTemplate.resolveJarUrl(Option.empty(), "1.0.0-rc1");

            assertTrue(url.contains("pragmaticalabs/pragmatica"),
                       "Repo path must be pragmaticalabs/pragmatica (the actual monorepo)");
            assertFalse(url.contains("pragmaticalabs/aether/"),
                        "URL must NOT reference the legacy/non-existent pragmaticalabs/aether repo");
        }
    }
}
