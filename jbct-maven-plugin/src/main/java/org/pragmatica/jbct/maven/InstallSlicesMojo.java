package org.pragmatica.jbct.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.installation.InstallRequest;
import org.eclipse.aether.installation.InstallationException;
import org.eclipse.aether.util.artifact.SubArtifact;
import org.pragmatica.jbct.slice.SliceManifest;

import java.io.File;
import java.nio.file.Path;

/**
 * Installs slice artifacts to local repository with distinct artifactIds.
 * Unlike standard Maven attach, this creates truly separate artifacts.
 */
@Mojo(name = "install-slices", defaultPhase = LifecyclePhase.INSTALL)
public class InstallSlicesMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${project.build.outputDirectory}", readonly = true)
    private File classesDirectory;

    @Parameter(defaultValue = "${project.build.directory}", readonly = true)
    private File outputDirectory;

    @Parameter(property = "jbct.skip", defaultValue = "false")
    private boolean skip;

    @Component
    private RepositorySystem repoSystem;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true)
    private RepositorySystemSession repoSession;

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("Skipping slice installation");
            return;
        }

        var manifestDir = new File(classesDirectory, "META-INF/slice");
        if (!manifestDir.exists() || !manifestDir.isDirectory()) {
            getLog().info("No slice manifests found - skipping installation");
            return;
        }

        var manifestFiles = manifestDir.listFiles((dir, name) -> name.endsWith(".manifest"));
        if (manifestFiles == null || manifestFiles.length == 0) {
            getLog().info("No .manifest files found");
            return;
        }

        for (var manifestFile : manifestFiles) {
            installSliceArtifacts(manifestFile.toPath());
        }
    }

    private void installSliceArtifacts(Path manifestPath) throws MojoExecutionException {
        var result = SliceManifest.load(manifestPath);
        if (result.isFailure()) {
            throw new MojoExecutionException("Failed to load manifest: " + manifestPath);
        }

        var manifest = result.unwrap();
        getLog().info("Installing slice: " + manifest.sliceName());

        // Install API artifact
        installArtifact(manifest.apiArtifactId(), manifest, true);

        // Install Impl artifact
        installArtifact(manifest.implArtifactId(), manifest, false);
    }

    private void installArtifact(String artifactId, SliceManifest manifest, boolean isApi)
            throws MojoExecutionException {
        var version = project.getVersion();
        var jarFile = new File(outputDirectory, artifactId + "-" + version + ".jar");
        var pomFile = new File(outputDirectory, artifactId + "-" + version + ".pom");

        if (!jarFile.exists()) {
            getLog().warn("JAR file not found: " + jarFile + " (run package-slices first)");
            return;
        }

        try {
            var artifact = new DefaultArtifact(
                    project.getGroupId(),
                    artifactId,
                    null,
                    "jar",
                    version
            ).setFile(jarFile);

            var request = new InstallRequest();
            request.addArtifact(artifact);

            // Add POM as sub-artifact if it exists
            if (pomFile.exists()) {
                var pomArtifact = new SubArtifact(artifact, null, "pom", pomFile);
                request.addArtifact(pomArtifact);
            }

            repoSystem.install(repoSession, request);
            getLog().info("Installed: " + project.getGroupId() + ":" + artifactId + ":" + version);
        } catch (InstallationException e) {
            throw new MojoExecutionException("Failed to install artifact: " + artifactId, e);
        }
    }
}
