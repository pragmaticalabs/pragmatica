// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.terra.launcher;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.terra.TerraFactory;
import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

class TerraConfigurationTest {
    @TempDir Path directory;

    @Test void intrinsic_twoJarsWithSameResourceName_readsOnlyOwningJar() throws Exception {
        for (var name : List.of("first", "second")) {
            var jar = factoryJar(name, name.equals("first") ? "META-INF/resources.toml" : "resources.toml");
            try (var loader = new FactoryLoader(jar.toUri().toURL())) {
                var factory = (TerraFactory<?>) loader.loadClass(ConfigFactoryFixture.class.getName()).getConstructor().newInstance();
                assertThat(TerraConfiguration.intrinsic(factory).unwrap().getString("probe.value").unwrap()).isEqualTo(name);
            }
        }
    }

    @Test void slice_configurationPrecedence_preservesOwnershipAndOverrides() throws Exception {
        var jar = factoryJar("intrinsic", "resources.toml");
        Files.createDirectories(directory.resolve("slices"));
        Files.writeString(directory.resolve("resources.toml"), "[probe]\nvalue = \"global\"\nshared = \"shared\"\n");
        Files.writeString(directory.resolve("slices/test%3Aconfig%3A1.toml"), "[probe]\nvalue = \"slice\"\n");
        try (var loader = new FactoryLoader(jar.toUri().toURL())) {
            var factory = (TerraFactory<?>) loader.loadClass(ConfigFactoryFixture.class.getName()).getConstructor().newInstance();
            var deployment = TerraConfiguration.deployment(directory).unwrap();
            var config = TerraConfiguration.slice(directory, factory, deployment).unwrap();
            assertThat(config.getString("probe.value").unwrap()).isEqualTo("slice");
            assertThat(config.getString("probe.shared").unwrap()).isEqualTo("shared");
            var dynamic = new TerraConfiguration.Deployment(ConfigurationProvider.builder().withDefaults(Map.of("probe.value", "dynamic")).build(), deployment.resources());
            assertThat(TerraConfiguration.slice(directory, factory, dynamic).unwrap().getString("probe.value").unwrap()).isEqualTo("dynamic");
        }
    }

    @Test void environment_doubleUnderscore_preservesUnderscoredResourceKeys() {
        var source = TerraConfiguration.environment(Map.of("TERRA_DATABASE__JDBC_URL", "jdbc:test", "UNRELATED", "ignore")).unwrap();
        assertThat(source.asMap()).containsExactlyEntriesOf(Map.of("database.jdbc_url", "jdbc:test"));
    }

    @Test void configuration_malformedFile_doesNotFallBackToDefaults() throws Exception {
        Files.writeString(directory.resolve("resources.toml"), "[broken");
        assertThat(TerraConfiguration.deployment(directory).isFailure()).isTrue();
    }

    @Test void load_checkPlan_doesNotConstructSlices() throws Exception {
        Files.writeString(directory.resolve("blueprint.toml"), "[[slices]]\nartifact = \"test:config:1\"\n");
        var plan = TerraLaunchPlan.load(directory).await(timeSpan(5).seconds()).unwrap();
        assertThat(plan.artifacts()).containsExactly("test:config:1");
    }

    @Test void load_missingSelectedSlice_failsBeforeConstruction() throws Exception {
        Files.writeString(directory.resolve("blueprint.toml"), "[[slices]]\nartifact = \"test:missing:1\"\n");
        assertThat(TerraLaunchPlan.load(directory).await(timeSpan(5).seconds()).isFailure()).isTrue();
    }

    @Test void load_invalidMigration_failsWithoutProvisioning() throws Exception {
        Files.writeString(directory.resolve("blueprint.toml"), "id = \"test:app:1\"\n[[slices]]\nartifact = \"test:config:1\"\n");
        Files.createDirectory(directory.resolve("schema"));
        Files.writeString(directory.resolve("schema/not-a-migration.sql"), "SELECT 1;");
        assertThat(TerraLaunchPlan.load(directory).await(timeSpan(5).seconds()).isFailure()).isTrue();
    }

    private Path factoryJar(String value, String resource) throws Exception {
        var path = directory.resolve(value + ".jar");
        var type = ConfigFactoryFixture.class.getName().replace('.', '/') + ".class";
        try (var out = new JarOutputStream(Files.newOutputStream(path)); var input = getClass().getClassLoader().getResourceAsStream(type)) {
            out.putNextEntry(new JarEntry(type));
            input.transferTo(out);
            out.closeEntry();
            out.putNextEntry(new JarEntry(resource));
            out.write(("[probe]\nvalue = \"" + value + "\"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return path;
    }

    private static final class FactoryLoader extends URLClassLoader {
        FactoryLoader(URL jar) { super(new URL[]{jar}, TerraConfigurationTest.class.getClassLoader()); }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.equals(ConfigFactoryFixture.class.getName())) {
                var found = findLoadedClass(name);
                return found == null ? findClass(name) : found;
            }
            return super.loadClass(name, resolve);
        }
    }
}
