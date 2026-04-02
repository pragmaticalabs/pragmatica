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
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/// Parser for blueprint artifact JARs.
/// Extracts blueprint.toml, resources.toml, and schema/*.sql from JAR bytes.
@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02", "JBCT-EX-01"})
public interface BlueprintArtifactParser {
    Cause MISSING_BLUEPRINT_TOML = Causes.cause("Blueprint artifact missing META-INF/blueprint.toml");
    Fn1<Cause, String> PARSE_ERROR = Causes.forOneValue("Failed to parse blueprint artifact: %s");

    /// Parse a blueprint artifact from JAR bytes.
    static Result<BlueprintArtifact> parse(byte[] jarBytes) {
        try (var zis = new ZipInputStream(new ByteArrayInputStream(jarBytes))) {
            return parseZipEntries(zis);
        }































        catch (IOException e) {
            return PARSE_ERROR.apply(e.getMessage()).result();
        }
    }

    private static Result<BlueprintArtifact> parseZipEntries(ZipInputStream zis) throws IOException {
        String blueprintToml = null;
        String resourcesToml = null;
        var schemaMigrations = new LinkedHashMap<String, List<MigrationEntry>>();
        ZipEntry entry;
        while ( (entry = zis.getNextEntry()) != null) {
            var name = entry.getName();
            if ( "META-INF/blueprint.toml".equals(name)) {
            blueprintToml = readEntry(zis);} else
            if ( "META-INF/resources.toml".equals(name)) {
            resourcesToml = readEntry(zis);} else if ( name.startsWith("schema/") && name.endsWith(".sql") && !entry.isDirectory()) {
            addMigrationEntry(zis, name, schemaMigrations);}
        }
        if ( blueprintToml == null) {
        return MISSING_BLUEPRINT_TOML.result();}
        var resourcesConfig = Option.option(resourcesToml);
        return BlueprintParser.parse(blueprintToml)
        .map(blueprint -> BlueprintArtifact.blueprintArtifact(blueprint, resourcesConfig, schemaMigrations));
    }

    private static String readEntry(ZipInputStream zis) throws IOException {
        return new String(zis.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void addMigrationEntry(ZipInputStream zis,
                                          String entryPath,
                                          Map<String, List<MigrationEntry>> migrations) throws IOException {
        var parts = entryPath.split("/");
        String datasource;
        String filename;
        if ( parts.length == 2) {
            // schema/V001__create.sql → default datasource
            datasource = "database";
            filename = parts[1];
        } else































        if ( parts.length >= 3) {
            // schema/analytics/V001__create.sql → named datasource
            datasource = "database." + parts[1];
            filename = parts[parts.length - 1];
        } else {
        return;}
        var sql = readEntry(zis);
        var checksum = computeChecksum(sql);
        migrations.computeIfAbsent(datasource, _ -> new ArrayList<>())
        .add(MigrationEntry.migrationEntry(filename, sql, checksum));
    }

    private static long computeChecksum(String content) {
        var crc = new CRC32();
        crc.update(content.getBytes(StandardCharsets.UTF_8));
        return crc.getValue();
    }
}
