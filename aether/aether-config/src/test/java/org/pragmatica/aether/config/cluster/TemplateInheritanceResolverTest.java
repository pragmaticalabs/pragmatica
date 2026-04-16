// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.config.cluster;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.config.toml.TomlDocument;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateInheritanceResolverTest {

    @Nested
    class NoInheritance {
        @Test
        void resolve_noInherit_returnsUnchanged() {
            var doc = new TomlDocument(Map.of(
                "source.eu", Map.of("type", "cloud", "region", "fsn1"),
                "runtime.jvm", Map.of("type", "jvm", "heap", "4g")
            ), Map.of());

            TemplateInheritanceResolver.resolve(doc)
                .onFailureRun(Assertions::fail)
                .onSuccess(result -> {
                    assertThat(result.getString("source.eu", "type").or("")).isEqualTo("cloud");
                    assertThat(result.getString("source.eu", "region").or("")).isEqualTo("fsn1");
                    assertThat(result.getString("runtime.jvm", "type").or("")).isEqualTo("jvm");
                });
        }
    }

    @Nested
    class SimpleInheritance {
        @Test
        void resolve_simpleInherit_mergesTemplateFields() {
            var doc = new TomlDocument(Map.of(
                "template.hetzner-base", Map.of("type", "cloud", "provider", "hetzner", "image", "ubuntu-22"),
                "source.eu", Map.of("inherit", "hetzner-base", "region", "fsn1")
            ), Map.of());

            TemplateInheritanceResolver.resolve(doc)
                .onFailureRun(Assertions::fail)
                .onSuccess(result -> {
                    assertThat(result.getString("source.eu", "type").or("")).isEqualTo("cloud");
                    assertThat(result.getString("source.eu", "provider").or("")).isEqualTo("hetzner");
                    assertThat(result.getString("source.eu", "image").or("")).isEqualTo("ubuntu-22");
                    assertThat(result.getString("source.eu", "region").or("")).isEqualTo("fsn1");
                    assertThat(result.hasKey("source.eu", "inherit")).isFalse();
                });
        }

        @Test
        void resolve_inheritOverride_sectionWins() {
            var doc = new TomlDocument(Map.of(
                "template.base", Map.of("type", "cloud", "provider", "hetzner", "image", "ubuntu-20"),
                "source.eu", Map.of("inherit", "base", "image", "ubuntu-22", "region", "fsn1")
            ), Map.of());

            TemplateInheritanceResolver.resolve(doc)
                .onFailureRun(Assertions::fail)
                .onSuccess(result -> {
                    assertThat(result.getString("source.eu", "image").or("")).isEqualTo("ubuntu-22");
                    assertThat(result.getString("source.eu", "provider").or("")).isEqualTo("hetzner");
                    assertThat(result.getString("source.eu", "region").or("")).isEqualTo("fsn1");
                });
        }
    }

    @Nested
    class ChainedInheritance {
        @Test
        void resolve_chainedInherit_resolvesRecursively() {
            var doc = new TomlDocument(Map.of(
                "template.cloud-base", Map.of("type", "cloud", "os", "linux"),
                "template.hetzner-base", Map.of("inherit", "cloud-base", "provider", "hetzner", "image", "ubuntu-22"),
                "source.eu", Map.of("inherit", "hetzner-base", "region", "fsn1")
            ), Map.of());

            TemplateInheritanceResolver.resolve(doc)
                .onFailureRun(Assertions::fail)
                .onSuccess(result -> {
                    assertThat(result.getString("source.eu", "type").or("")).isEqualTo("cloud");
                    assertThat(result.getString("source.eu", "os").or("")).isEqualTo("linux");
                    assertThat(result.getString("source.eu", "provider").or("")).isEqualTo("hetzner");
                    assertThat(result.getString("source.eu", "image").or("")).isEqualTo("ubuntu-22");
                    assertThat(result.getString("source.eu", "region").or("")).isEqualTo("fsn1");
                    assertThat(result.hasKey("source.eu", "inherit")).isFalse();
                });
        }
    }

    @Nested
    class ErrorCases {
        @Test
        void resolve_cyclicInherit_returnsError() {
            var sections = new HashMap<String, Map<String, Object>>();
            sections.put("template.a", Map.of("inherit", "b", "x", "1"));
            sections.put("template.b", Map.of("inherit", "a", "y", "2"));
            sections.put("source.test", Map.of("inherit", "a", "z", "3"));

            var doc = new TomlDocument(sections, Map.of());

            TemplateInheritanceResolver.resolve(doc)
                .onSuccess(_ -> Assertions.fail("Expected failure for cyclic inheritance"))
                .onFailure(cause -> assertThat(cause.message()).contains("Cyclic template inheritance"));
        }

        @Test
        void resolve_missingTemplate_returnsError() {
            var doc = new TomlDocument(Map.of(
                "source.eu", Map.of("inherit", "nonexistent", "region", "fsn1")
            ), Map.of());

            TemplateInheritanceResolver.resolve(doc)
                .onSuccess(_ -> Assertions.fail("Expected failure for missing template"))
                .onFailure(cause -> assertThat(cause.message()).contains("not found"));
        }

        @Test
        void resolve_depthExceeded_returnsError() {
            var sections = new HashMap<String, Map<String, Object>>();

            for (int i = 0; i < 20; i++) {
                var parentName = (i < 19) ? "t" + (i + 1) : "t0";
                sections.put("template.t" + i, Map.of("inherit", parentName, "key" + i, "val" + i));
            }

            sections.put("source.deep", Map.of("inherit", "t0", "leaf", "value"));

            var doc = new TomlDocument(sections, Map.of());

            TemplateInheritanceResolver.resolve(doc)
                .onSuccess(_ -> Assertions.fail("Expected failure for depth exceeded"))
                .onFailure(cause -> assertThat(cause.message()).contains("depth exceeded"));
        }
    }

    @Nested
    class TemplateRemoval {
        @Test
        void resolve_templatesRemoved_noTemplateInOutput() {
            var doc = new TomlDocument(Map.of(
                "template.base", Map.of("type", "cloud", "provider", "hetzner"),
                "template.runtime-base", Map.of("type", "jvm", "heap", "4g"),
                "source.eu", Map.of("inherit", "base", "region", "fsn1"),
                "runtime.prod", Map.of("inherit", "runtime-base", "xmx", "8g"),
                "cluster", Map.of("name", "production")
            ), Map.of());

            TemplateInheritanceResolver.resolve(doc)
                .onFailureRun(Assertions::fail)
                .onSuccess(result -> {
                    assertThat(result.sectionNames()).doesNotContain("template.base", "template.runtime-base");
                    assertThat(result.hasSection("source.eu")).isTrue();
                    assertThat(result.hasSection("runtime.prod")).isTrue();
                    assertThat(result.hasSection("cluster")).isTrue();
                    assertThat(result.getString("source.eu", "type").or("")).isEqualTo("cloud");
                    assertThat(result.getString("runtime.prod", "type").or("")).isEqualTo("jvm");
                });
        }
    }
}
