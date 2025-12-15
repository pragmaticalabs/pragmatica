package org.pragmatica.jbct.cli;

import org.pragmatica.jbct.upgrade.GitHubReleaseChecker;
import org.pragmatica.jbct.upgrade.JarInstaller;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * Upgrade command - update JBCT to latest version.
 */
@Command(
        name = "upgrade",
        description = "Upgrade JBCT to the latest version",
        mixinStandardHelpOptions = true
)
public class UpgradeCommand implements Callable<Integer> {

    @Option(
            names = {"--version", "-V"},
            description = "Install specific version"
    )
    String targetVersion;

    @Option(
            names = {"--check", "-c"},
            description = "Check for updates without installing"
    )
    boolean checkOnly;

    @Option(
            names = {"--install"},
            description = "First-time installation to ~/.jbct/"
    )
    boolean firstInstall;

    @Option(
            names = {"--force", "-f"},
            description = "Force upgrade even if already at latest version"
    )
    boolean force;

    @Override
    public Integer call() {
        if (firstInstall) {
            return performFirstInstall();
        }

        var checker = GitHubReleaseChecker.releaseChecker();
        var currentVersion = getCurrentVersion();

        System.out.println("Current version: " + currentVersion);
        System.out.println("Checking for updates...");

        return checker.checkLatestRelease()
                .fold(
                        cause -> {
                            System.err.println("Error: " + cause.message());
                            return 1;
                        },
                        release -> {
                            System.out.println("Latest version: " + release.version());

                            if (checkOnly) {
                                if (GitHubReleaseChecker.isNewerVersion(currentVersion, release.version())) {
                                    System.out.println("Update available: " + release.version());
                                    return 0;
                                } else {
                                    System.out.println("Already at latest version.");
                                    return 0;
                                }
                            }

                            // Check if upgrade is needed
                            if (!force && !GitHubReleaseChecker.isNewerVersion(currentVersion, release.version())) {
                                System.out.println("Already at latest version. Use --force to reinstall.");
                                return 0;
                            }

                            // Check for download URL
                            if (!release.hasDownloadUrl()) {
                                System.err.println("Error: No downloadable JAR found in release.");
                                System.err.println("Please download manually from GitHub.");
                                return 1;
                            }

                            return performUpgrade(release.downloadUrl().unwrap(), release.version());
                        }
                );
    }

    private int performUpgrade(String downloadUrl, String version) {
        System.out.println("Downloading version " + version + "...");

        var installer = JarInstaller.jarInstaller();
        var targetPath = JarInstaller.detectCurrentJar();

        System.out.println("Installing to: " + targetPath);

        return installer.install(downloadUrl, targetPath)
                .fold(
                        cause -> {
                            System.err.println("Error: " + cause.message());
                            return 1;
                        },
                        path -> {
                            System.out.println("Successfully upgraded to version " + version);
                            return 0;
                        }
                );
    }

    private int performFirstInstall() {
        System.out.println("Performing first-time installation...");

        return JarInstaller.createInstallDir()
                .flatMap(installDir -> {
                    System.out.println("Created installation directory: " + installDir);

                    // Install wrapper scripts
                    return JarInstaller.installWrapperScripts(installDir)
                            .map(_ -> installDir);
                })
                .flatMap(installDir -> {
                    System.out.println("Installed wrapper scripts.");

                    // Now download and install the JAR
                    var checker = GitHubReleaseChecker.releaseChecker();
                    return checker.checkLatestRelease()
                            .flatMap(release -> {
                                if (!release.hasDownloadUrl()) {
                                    return org.pragmatica.lang.Result.failure(
                                            org.pragmatica.lang.utils.Causes.cause("No downloadable JAR found in release")
                                    );
                                }

                                System.out.println("Downloading version " + release.version() + "...");

                                var installer = JarInstaller.jarInstaller();
                                var targetPath = JarInstaller.defaultInstallPath();

                                return installer.install(release.downloadUrl().unwrap(), targetPath)
                                        .map(path -> release.version());
                            });
                })
                .fold(
                        cause -> {
                            System.err.println("Error: " + cause.message());
                            return 1;
                        },
                        version -> {
                            System.out.println();
                            System.out.println("Successfully installed JBCT " + version);
                            System.out.println();
                            System.out.println("Add ~/.jbct/bin to your PATH:");
                            System.out.println("  export PATH=\"$HOME/.jbct/bin:$PATH\"");
                            System.out.println();
                            System.out.println("Then run: jbct --help");
                            return 0;
                        }
                );
    }

    private String getCurrentVersion() {
        // Try to get version from package info
        var pkg = UpgradeCommand.class.getPackage();
        if (pkg != null && pkg.getImplementationVersion() != null) {
            return pkg.getImplementationVersion();
        }

        // Fall back to build-time constant
        return "0.3.0";
    }
}
