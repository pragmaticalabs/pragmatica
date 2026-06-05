// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.blueprint;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;


@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02", "JBCT-EX-01"}) public interface BlueprintArtifactParser {
    Cause MISSING_BLUEPRINT_TOML = Causes.cause("Blueprint artifact missing META-INF/blueprint.toml");

    Fn1<Cause, String> PARSE_ERROR = Causes.forOneValue("Failed to parse blueprint artifact: %s");

    /// Spec event-stream-namespaces §11: stream alias is the part after `streams.` in the
    /// `[streams.X]` section header — slice manifests record the full `configSection` (e.g.
    /// `streams.orders`); the validator's `roleHints` map is keyed by the alias only.
    String STREAMS_PREFIX = "streams.";

    String ROLE_PRODUCER = "producer";

    String ROLE_CONSUMER = "consumer";

    String ROLE_BOTH = "both";

    static Result<BlueprintArtifact> parse(byte[] jarBytes) {
        try (var zis = new ZipInputStream(new ByteArrayInputStream(jarBytes))) {
            return parseZipEntries(zis);
        } catch (IOException e) {
            return PARSE_ERROR.apply(e.getMessage()).result();
        }
    }

    private static Result<BlueprintArtifact> parseZipEntries(ZipInputStream zis) throws IOException {
        String blueprintToml = null;
        String resourcesToml = null;
        var schemaMigrations = new LinkedHashMap<String, List<MigrationEntry>>();
        var sliceManifestProps = new ArrayList<Properties>();
        ZipEntry entry;
        while ((entry = zis.getNextEntry()) != null) {
            var name = entry.getName();
            if ("META-INF/blueprint.toml".equals(name)) {
                blueprintToml = readEntry(zis);
            } else if ("META-INF/resources.toml".equals(name)) {
                resourcesToml = readEntry(zis);
            } else if (name.startsWith("META-INF/slice/") && name.endsWith(".manifest") && !entry.isDirectory()) {
                addSliceManifest(zis, sliceManifestProps);
            } else if (name.startsWith("schema/") && name.endsWith(".sql") && !entry.isDirectory()) {
                addMigrationEntry(zis, name, schemaMigrations);
            }
        }
        if (blueprintToml == null) {return MISSING_BLUEPRINT_TOML.result();}
        var resourcesConfig = Option.option(resourcesToml);
        var roleHints = aggregateRoleHints(sliceManifestProps);
        return BlueprintParser.parse(blueprintToml)
                                    .map(blueprint -> BlueprintArtifact.blueprintArtifact(blueprint,
                                                                                          resourcesConfig,
                                                                                          schemaMigrations,
                                                                                          roleHints));
    }

    private static String readEntry(ZipInputStream zis) throws IOException {
        return new String(zis.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void addSliceManifest(ZipInputStream zis, List<Properties> sink) throws IOException {
        var props = new Properties();
        props.load(new ByteArrayInputStream(zis.readAllBytes()));
        sink.add(props);
    }

    /// Walk every slice manifest's `stream.publisher.<i>.config` and `stream.access.<i>.config`
    /// properties and project a `Map<alias, role>` per spec §11.1.2. Cross-manifest collapse:
    /// a config section bound by both publisher and access (across the same or different slices
    /// in the same blueprint) collapses to `both`.
    static Map<String, String> aggregateRoleHints(List<Properties> sliceManifestProps) {
        var rolesByConfig = new LinkedHashMap<String, Set<String>>();
        sliceManifestProps.forEach(props -> collectRoleConfigs(props, "stream.publisher.", "stream.publishers.count",
                                                                ROLE_PRODUCER, rolesByConfig));
        sliceManifestProps.forEach(props -> collectRoleConfigs(props, "stream.access.", "stream.access.count",
                                                                ROLE_CONSUMER, rolesByConfig));
        var result = new LinkedHashMap<String, String>();
        rolesByConfig.forEach((configSection, roles) -> aliasOf(configSection)
                .onPresent(alias -> result.put(alias, collapseRole(roles))));
        return Map.copyOf(result);
    }

    private static void collectRoleConfigs(Properties props,
                                            String prefix,
                                            String countKey,
                                            String role,
                                            Map<String, Set<String>> sink) {
        var count = parseCount(props.getProperty(countKey));
        for (int i = 0; i < count; i++) {
            var configSection = props.getProperty(prefix + i + ".config");
            if (configSection != null && !configSection.isEmpty()) {
                sink.computeIfAbsent(configSection, _ -> new LinkedHashSet<>()).add(role);
            }
        }
    }

    private static int parseCount(String raw) {
        if (raw == null || raw.isEmpty()) {return 0;}
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException _) {
            return 0;
        }
    }

    private static Option<String> aliasOf(String configSection) {
        if (!configSection.startsWith(STREAMS_PREFIX)) {return Option.none();}
        var alias = configSection.substring(STREAMS_PREFIX.length());
        return alias.isEmpty() ? Option.none() : Option.some(alias);
    }

    private static String collapseRole(Set<String> roles) {
        if (roles.contains(ROLE_PRODUCER) && roles.contains(ROLE_CONSUMER)) {return ROLE_BOTH;}
        if (roles.contains(ROLE_PRODUCER)) {return ROLE_PRODUCER;}
        return ROLE_CONSUMER;
    }

    private static void addMigrationEntry(ZipInputStream zis,
                                          String entryPath,
                                          Map<String, List<MigrationEntry>> migrations) throws IOException {
        var parts = entryPath.split("/");
        String datasource;
        String filename;
        if (parts.length == 2) {
            datasource = "database";
            filename = parts[1];
        } else if (parts.length >= 3) {
            datasource = "database." + parts[1];
            filename = parts[parts.length - 1];
        } else {return;}
        var sql = readEntry(zis);
        var checksum = computeChecksum(sql);
        migrations.computeIfAbsent(datasource,
                                   _ -> new ArrayList<>())
        .add(MigrationEntry.migrationEntry(filename, sql, checksum));
    }

    private static long computeChecksum(String content) {
        var crc = new CRC32();
        crc.update(content.getBytes(StandardCharsets.UTF_8));
        return crc.getValue();
    }
}
