// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.terra.maven;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;


/// Build-time file/process boundary. Dependency JARs are kept intact: no service or resource merging.
final class Distribution {
    private Distribution() {}

    record InvalidDistribution(String message) implements Cause {}

    record Library(String coordinate, Path file) {
        String name() {
            return coordinate.replaceAll("[^A-Za-z0-9_.-]", "_") + ".jar";
        }
    }

    private record Classes(Library library, List<String> names) {}

    static Result<Unit> classpath(List<Library> libraries) {
        return Result.allOf(libraries.stream().map(Distribution::classes).toList()).flatMap(Distribution::uniqueClasses);
    }

    private static Result<Classes> classes(Library library) {
        return Result.lift(Causes::fromThrowable,
                           () -> {
                               try (var jar = new JarFile(library.file().toFile(),
                                                          true,
                                                          ZipFile.OPEN_READ,
                                                          Runtime.version())) {
                               var names = jar.versionedStream()
                                              .map(ZipEntry::getName)
                                              .filter(name -> name.endsWith(".class")
                                                              && !name.equals("module-info.class")
                                                              && !name.startsWith("META-INF/"))
                                              .toList();

                               return new Classes(library, names);
                           }
                           });
    }

    private static Result<Unit> uniqueClasses(List<Classes> libraries) {
        var owners = new HashMap<String, String>();
        var names = new HashSet<String>();

        for (var library : libraries) {
            if (!names.add(library.library().name())) {
                return new InvalidDistribution("Duplicate library identity: " + library.library().coordinate()).result();
            }

            for (var type : library.names()) {
                var owner = owners.putIfAbsent(type,
                                               library.library().coordinate());

                if (owner != null) {
                    return new InvalidDistribution("Conflicting class " + type
                                                  + " in " + owner
                                                  + " and " + library.library()
                                                                     .coordinate()).result();
                }
            }
        }

        return Result.unitResult();
    }

    static Result<Path> assemble(List<Library> libraries, Path application, Path output, Path java, Path archive) {
        return classpath(libraries).flatMap(_ -> Result.lift(Causes::fromThrowable,
                                                             () -> staged(libraries, application, output, java, archive)))
                        .flatMap(result -> result);
    }

    @org.pragmatica.lang.Contract
    private static Result<Path> staged(List<Library> libraries,
                                       Path application,
                                       Path output,
                                       Path java,
                                       Path archive) throws Exception {
        if (!Files.isRegularFile(application.resolve("blueprint.toml"))) {
            return new InvalidDistribution("Application directory must contain blueprint.toml: " + application).result();
        }

        if (Files.exists(output) && !Files.isRegularFile(output.resolve(".terra-distribution"))) {
            return new InvalidDistribution("Refusing to replace a directory not owned by Terra: " + output).result();
        }

        Files.createDirectories(output.getParent());
        var stage = Files.createTempDirectory(output.getParent(), ".terra-stage-");

        try {
            var copy = copyApplication(application, stage.resolve("application"));

            if (copy.isFailure()) {
                return copy.map(_ -> archive);
            }

            copyLibraries(libraries, stage.resolve("lib"));
            writeLaunchers(stage);
            Files.writeString(stage.resolve(".terra-distribution"), "Terra distribution format 1\n");
            var check = check(java, stage);

            if (check.isFailure()) {
                return check.map(_ -> archive);
            }

            zip(stage, archive);
            if (Files.exists(output)) {
                remove(output);
            }

            Files.move(stage, output, StandardCopyOption.ATOMIC_MOVE);

            return Result.success(archive);
        } finally {
            if (Files.exists(stage)) {
                remove(stage);
            }
        }
    }

    @org.pragmatica.lang.Contract
    private static Result<Unit> copyApplication(Path source, Path target) throws IOException {
        try (var entries = Files.walk(source)) {
            for (var entry : entries.sorted().toList()) {
                if (Files.isSymbolicLink(entry)) {
                    return new InvalidDistribution("Application bundles cannot contain symbolic links: " + entry).result();
                }

                var destination = target.resolve(source.relativize(entry));

                if (Files.isDirectory(entry)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(entry, destination);
                }
            }
        }

        return Result.unitResult();
    }

    @org.pragmatica.lang.Contract
    private static void copyLibraries(List<Library> libraries, Path directory) throws Exception {
        Files.createDirectories(directory);
        var checksums = new ArrayList<String>();

        for (var library : libraries.stream().sorted(Comparator.comparing(Library::name)).toList()) {
            var destination = directory.resolve(library.name());

            Files.copy(library.file(), destination);
            checksums.add(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(destination)))
                         + "  lib/" + library.name());
        }

        Files.write(directory.getParent().resolve("SHA256SUMS"),
                    checksums,
                    StandardCharsets.UTF_8);
    }

    @org.pragmatica.lang.Contract
    private static void writeLaunchers(Path directory) throws IOException {
        var bin = Files.createDirectories(directory.resolve("bin"));

        for (var name : List.of("terra", "terra.cmd")) {
            try (var input = Distribution.class.getResourceAsStream("/launchers/" + name)) {
                Files.copy(java.util.Objects.requireNonNull(input, "Missing launcher template"), bin.resolve(name));
            }
        }

        bin.resolve("terra").toFile().setExecutable(true, false);
    }

    @org.pragmatica.lang.Contract
    private static Result<Unit> check(Path java, Path directory) throws Exception {
        var log = directory.getParent().resolve(directory.getFileName() + "-check.log");
        var process = new ProcessBuilder(java.toString(),
                                         "--enable-preview",
                                         "-cp",
                                         directory.resolve("lib/*").toString(),
                                         "org.pragmatica.terra.launcher.TerraMain",
                                         directory.resolve("application").toString(),
                                         "--check").redirectErrorStream(true)
                                                   .redirectOutput(log.toFile())
                                                   .start();

        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);

            return new InvalidDistribution("Terra assembly check timed out; see " + log).result();
        }

        return process.exitValue() == 0
               ? Result.unitResult()
               : new InvalidDistribution("Terra assembly check failed; see " + log).result();
    }

    @org.pragmatica.lang.Contract
    private static void zip(Path directory, Path archive) throws IOException {
        var temporary = Files.createTempFile(archive.getParent(), ".terra-archive-", ".zip");

        try {
            try (var output = new ZipOutputStream(Files.newOutputStream(temporary)); var paths = Files.walk(directory)) {
                for (var file : paths.filter(Files::isRegularFile).sorted().toList()) {
                    var entry = new ZipEntry(directory.relativize(file).toString().replace('\\', '/'));

                    entry.setTime(315532800000L);
                    output.putNextEntry(entry);
                    Files.copy(file, output);
                    output.closeEntry();
                }
            }

            Files.move(temporary, archive, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @org.pragmatica.lang.Contract
    private static void remove(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
