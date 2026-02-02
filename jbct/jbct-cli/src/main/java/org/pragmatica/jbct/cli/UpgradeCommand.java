package org.pragmatica.jbct.cli;

import org.pragmatica.jbct.upgrade.GitHubReleaseChecker;
import org.pragmatica.jbct.upgrade.JarInstaller;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Upgrade command - update JBCT to latest version.
 */
@Command(
 name = "upgrade",
 description = "Upgrade JBCT to the latest version",
 mixinStandardHelpOptions = true)
public class UpgradeCommand implements Callable<Integer> {
    @Option(names = {"--version", "-V"}, description = "Install specific version")
    String targetVersion;

    @Option(names = {"--check", "-c"}, description = "Check for updates without installing")
    boolean checkOnly;

    @Option(names = {"--install"}, description = "First-time installation to ~/.jbct/")
    boolean firstInstall;

    @Option(names = {"--force", "-f"}, description = "Force upgrade even if already at latest version")
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
                      .onFailure(cause -> System.err.println("Error: " + cause.message()))
                      .map(release -> handleRelease(release, currentVersion))
                      .or(1);
    }

    private int handleRelease(GitHubReleaseChecker.ReleaseInfo release, String currentVersion) {
        System.out.println("Latest version: " + release.version());
        if (checkOnly) {
            if (GitHubReleaseChecker.isNewerVersion(currentVersion, release.version())) {
                System.out.println("Update available: " + release.version());
            } else {
                System.out.println("Already at latest version.");
            }
            return 0;
        }
        // Check if upgrade is needed
        if (!force && !GitHubReleaseChecker.isNewerVersion(currentVersion, release.version())) {
            System.out.println("Already at latest version. Use --force to reinstall.");
            return 0;
        }
        // Check for download URL
        return release.downloadUrl()
                      .toResult(Causes.cause("No downloadable JAR found in release"))
                      .onFailure(cause -> {
                                     System.err.println("Error: " + cause.message());
                                     System.err.println("Please download manually from GitHub.");
                                 })
                      .map(url -> performUpgrade(url, release.version()))
                      .or(1);
    }

    private int performUpgrade(String downloadUrl, String version) {
        System.out.println("Downloading version " + version + "...");
        var installer = JarInstaller.jarInstaller();
        var targetPath = JarInstaller.detectCurrentJar();
        System.out.println("Installing to: " + targetPath);
        return installer.install(downloadUrl, targetPath)
                        .onFailure(cause -> System.err.println("Error: " + cause.message()))
                        .onSuccess(_ -> System.out.println("Successfully upgraded to version " + version))
                        .fold(_ -> 1, _ -> 0);
    }

    private int performFirstInstall() {
        System.out.println("Performing first-time installation...");
        return JarInstaller.createInstallDir()
                           .flatMap(this::installScriptsAndDownload)
                           .onFailure(cause -> System.err.println("Error: " + cause.message()))
                           .onSuccess(this::printInstallSuccess)
                           .fold(_ -> 1, _ -> 0);
    }

    private Result<String> installScriptsAndDownload(java.nio.file.Path installDir) {
        System.out.println("Created installation directory: " + installDir);
        return JarInstaller.installWrapperScripts(installDir)
                           .flatMap(_ -> downloadLatestRelease());
    }

    private Result<String> downloadLatestRelease() {
        System.out.println("Installed wrapper scripts.");
        var checker = GitHubReleaseChecker.releaseChecker();
        return checker.checkLatestRelease()
                      .flatMap(this::downloadRelease);
    }

    private Result<String> downloadRelease(GitHubReleaseChecker.ReleaseInfo release) {
        return release.downloadUrl()
                      .toResult(Causes.cause("No downloadable JAR found in release"))
                      .flatMap(url -> downloadAndInstall(url, release.version()));
    }

    private Result<String> downloadAndInstall(String url, String version) {
        System.out.println("Downloading version " + version + "...");
        var installer = JarInstaller.jarInstaller();
        var targetPath = JarInstaller.defaultInstallPath();
        return installer.install(url, targetPath)
                        .map(_ -> version);
    }

    private void printInstallSuccess(String version) {
        System.out.println();
        System.out.println("Successfully installed JBCT " + version);
        System.out.println();
        System.out.println("Add ~/.jbct/bin to your PATH:");
        System.out.println("  export PATH=\"$HOME/.jbct/bin:$PATH\"");
        System.out.println();
        System.out.println("Then run: jbct --help");
    }

    private String getCurrentVersion() {
        return Version.get();
    }
}
