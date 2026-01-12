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
import org.eclipse.aether.deployment.DeployRequest;
import org.eclipse.aether.deployment.DeploymentException;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.util.artifact.SubArtifact;
import org.pragmatica.jbct.slice.SliceManifest;

import java.io.File;
import java.nio.file.Path;

/**
 * Deploys slice artifacts to remote repository with distinct artifactIds.
 * Unlike standard Maven attach, this creates truly separate artifacts.
 */
@Mojo(name = "deploy-slices", defaultPhase = LifecyclePhase.DEPLOY)
public class DeploySlicesMojo extends AbstractMojo {

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
            getLog().info("Skipping slice deployment");
            return;
        }

        var manifestDir = new File(classesDirectory, "META-INF/slice");
        if (!manifestDir.exists() || !manifestDir.isDirectory()) {
            getLog().info("No slice manifests found - skipping deployment");
            return;
        }

        var manifestFiles = manifestDir.listFiles((dir, name) -> name.endsWith(".manifest"));
        if (manifestFiles == null || manifestFiles.length == 0) {
            getLog().info("No .manifest files found");
            return;
        }

        var deployRepo = getDeploymentRepository();
        if (deployRepo == null) {
            throw new MojoExecutionException("No deployment repository configured");
        }

        for (var manifestFile : manifestFiles) {
            deploySliceArtifacts(manifestFile.toPath(), deployRepo);
        }
    }

    private RemoteRepository getDeploymentRepository() {
        var distMgmt = project.getDistributionManagement();
        if (distMgmt == null) {
            return null;
        }

        var repo = distMgmt.getRepository();
        if (repo == null) {
            return null;
        }

        return new RemoteRepository.Builder(repo.getId(), "default", repo.getUrl()).build();
    }

    private void deploySliceArtifacts(Path manifestPath, RemoteRepository deployRepo) throws MojoExecutionException {
        var result = SliceManifest.load(manifestPath);
        if (result.isFailure()) {
            throw new MojoExecutionException("Failed to load manifest: " + manifestPath);
        }

        var manifest = result.unwrap();
        getLog().info("Deploying slice: " + manifest.sliceName());

        // Deploy API artifact
        deployArtifact(manifest.apiArtifactId(), manifest, true, deployRepo);

        // Deploy Impl artifact
        deployArtifact(manifest.implArtifactId(), manifest, false, deployRepo);
    }

    private void deployArtifact(String artifactId, SliceManifest manifest, boolean isApi, RemoteRepository deployRepo)
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

            var request = new DeployRequest();
            request.addArtifact(artifact);
            request.setRepository(deployRepo);

            // Add POM as sub-artifact if it exists
            if (pomFile.exists()) {
                var pomArtifact = new SubArtifact(artifact, null, "pom", pomFile);
                request.addArtifact(pomArtifact);
            }

            repoSystem.deploy(repoSession, request);
            getLog().info("Deployed: " + project.getGroupId() + ":" + artifactId + ":" + version);
        } catch (DeploymentException e) {
            throw new MojoExecutionException("Failed to deploy artifact: " + artifactId, e);
        }
    }
}
