// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.terra.maven;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;

import org.pragmatica.lang.Result;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;


/// Assemble a portable Terra distribution from the project's resolved runtime classpath.
@Mojo(name = "assemble", defaultPhase = LifecyclePhase.PACKAGE, requiresDependencyResolution = ResolutionScope.RUNTIME, threadSafe = true)
public final class AssembleMojo extends AbstractMojo {
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Component
    private MavenProjectHelper helper;

    @Parameter(defaultValue = "${project.basedir}/src/main/terra", property = "terra.applicationDirectory")
    private File applicationDirectory;

    @Parameter(defaultValue = "${project.build.directory}/${project.artifactId}-terra", property = "terra.outputDirectory")
    private File outputDirectory;

    @Parameter(defaultValue = "${java.home}/bin/java", property = "terra.java")
    private File javaExecutable;

    @Override
    @SuppressWarnings({"JBCT-EX-01", "JBCT-RET-01"})  // Maven SPI requires throwing the build failure at this boundary.
    public void execute() throws MojoExecutionException {
        var result = assemble();

        if (result instanceof Result.Failure<Path>(var cause)) {
            throw new MojoExecutionException(cause.message());
        }

        helper.attachArtifact(project,
                              "zip",
                              "terra",
                              result.unwrap().toFile());
        getLog().info("Terra distribution: " + outputDirectory + " (archive " + result.unwrap() + ")");
    }

    private Result<Path> assemble() {
        var build = Path.of(project.getBuild().getDirectory()).toAbsolutePath().normalize();
        var output = outputDirectory.toPath().toAbsolutePath().normalize();

        if (!output.startsWith(build) || output.equals(build)) {
            return new Distribution.InvalidDistribution("Terra output must be a child of the Maven build directory").result();
        }

        var libraries = new ArrayList<Distribution.Library>();

        if (!project.getPackaging().equals("pom")) {
            var artifact = project.getArtifact();

            if (artifact.getFile() == null || !artifact.getFile().isFile()) {
                return new Distribution.InvalidDistribution("Package the project JAR before running terra:assemble").result();
            }

            libraries.add(new Distribution.Library(artifact.getId(),
                                                   artifact.getFile().toPath()));
        }

        for (var artifact : project.getArtifacts()) {
            if (!java.util.Set.of("compile", "runtime").contains(artifact.getScope())) {
                continue;
            }

            if (!artifact.getType().equals("jar") || artifact.getFile() == null || !artifact.getFile().isFile()) {
                return new Distribution.InvalidDistribution("Runtime dependency is not a resolved JAR: " + artifact.getId()).result();
            }

            libraries.add(new Distribution.Library(artifact.getId(),
                                                   artifact.getFile().toPath()));
        }

        return Distribution.assemble(libraries,
                                     applicationDirectory.toPath(),
                                     output,
                                     javaExecutable.toPath(),
                                     build.resolve(project.getBuild().getFinalName() + "-terra.zip"));
    }
}
