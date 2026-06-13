// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/// Utility for converting Maven `maven-metadata.xml` payloads into a JSON envelope.
///
/// The server's artifact repository emits a minimal Maven 2 metadata document
/// (groupId / artifactId / versioning.versions). For `--format json` consumers
/// (test harnesses, scripts) the raw XML is awkward to navigate — this helper
/// extracts `groupId`, `artifactId`, and the `<version>` list and renders a
/// stable JSON object `{"groupId":...,"artifactId":...,"versions":[...]}`.
///
/// Pattern: utility interface (sealed; `unused` placeholder record).
public sealed interface MavenMetadataFormatter {
    Pattern GROUP_PATTERN = Pattern.compile("<groupId>\\s*([^<]+?)\\s*</groupId>");
    Pattern ARTIFACT_PATTERN = Pattern.compile("<artifactId>\\s*([^<]+?)\\s*</artifactId>");
    Pattern VERSION_PATTERN = Pattern.compile("<version>\\s*([^<]+?)\\s*</version>");

    /// Convert a Maven `maven-metadata.xml` body into a JSON envelope.
    ///
    /// Robust to whitespace and irrelevant fields (latest / release / lastUpdated)
    /// that the server may add; only the three projected fields are emitted.
    static String toJson(String xml) {
        var groupId = firstMatch(GROUP_PATTERN, xml);
        var artifactId = firstMatch(ARTIFACT_PATTERN, xml);
        var versions = allMatches(VERSION_PATTERN, xml);

        return buildJson(groupId, artifactId, versions);
    }

    private static String firstMatch(Pattern pattern, String input) {
        var matcher = pattern.matcher(input);

        return matcher.find()
               ? unescapeXml(matcher.group(1))
               : "";
    }

    private static List<String> allMatches(Pattern pattern, String input) {
        var matcher = pattern.matcher(input);
        var results = new ArrayList<String>();

        while (matcher.find()) {
            results.add(unescapeXml(matcher.group(1)));
        }

        return results;
    }

    private static String unescapeXml(String value) {
        return value.replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&quot;", "\"")
                    .replace("&apos;", "'")
                    .replace("&amp;", "&");
    }

    private static String buildJson(String groupId, String artifactId, List<String> versions) {
        var sb = new StringBuilder();

        sb.append("{\"groupId\":\"").append(escapeJson(groupId)).append("\",\"artifactId\":\"").append(escapeJson(artifactId)).append("\",\"versions\":[");
        for (var i = 0; i < versions.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }

            sb.append('"').append(escapeJson(versions.get(i))).append('"');
        }

        sb.append("]}");

        return sb.toString();
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\")
                    .replace("\"", "\\\"");
    }

    record unused() implements MavenMetadataFormatter {}
}
