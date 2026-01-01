package org.pragmatica.aether.slice.repository.maven;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Verify;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;

public sealed interface MavenLocalRepoLocator {
    static String findLocalRepository() {
        var userHome = System.getProperty("user.home");
        return checkSystemProperty()
               .orElse(() -> checkUserLevelSettings(userHome))
               .orElse(() -> checkGlobalSettings(userHome))
               .or(() -> defaultRepository(userHome));
    }

    private static String defaultRepository(String userHome) {
        return fromM2Home(userHome, "repository");
    }

    private static Option<String> checkGlobalSettings(String userHome) {
        return Option.option(System.getenv("M2_HOME"))
                     .filter(Verify.Is::notEmpty)
                     .map(MavenLocalRepoLocator::fromM2ConfSettings)
                     .flatMap(MavenLocalRepoLocator::getLocalRepoFromSettings)
                     .map(globalRepo -> expandPath(globalRepo, userHome));
    }

    private static Option<String> checkUserLevelSettings(String userHome) {
        return getLocalRepoFromSettings(fromM2Home(userHome, "settings.xml"))
               .map(userRepo -> expandPath(userRepo, userHome));
    }

    private static Option<String> checkSystemProperty() {
        return Option.option(System.getProperty("maven.repo.local"))
                     .filter(Verify.Is::notEmpty);
    }

    private static Option<String> getLocalRepoFromSettings(String settingsPath) {
        var file = new File(settingsPath);
        if (!file.exists()) {
            return Option.empty();
        }
        try{
            var dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            var db = dbf.newDocumentBuilder();
            var doc = db.parse(file);
            var nodes = doc.getElementsByTagName("localRepository");
            if (nodes.getLength() > 0) {
                return Option.option(nodes.item(0)
                                          .getTextContent()
                                          .trim())
                             .filter(Verify.Is::notEmpty);
            }
        } catch (Exception ignored) {}
        return Option.empty();
    }

    private static String fromM2Home(String userHome, String child) {
        return new File(m2Home(userHome), child).getAbsolutePath();
    }

    private static String fromM2ConfSettings(String m2home) {
        return new File(new File(m2home, "conf"), "settings.xml").getAbsolutePath();
    }

    private static File m2Home(String userHome) {
        return new File(userHome, ".m2");
    }

    // Expands leading ~ and ${user.home} in the path for cross-platform compatibility
    private static String expandPath(String path, String userHome) {
        if (path.startsWith("~" + File.separator) || path.equals("~")) {
            path = userHome + path.substring(1);
        }
        if (path.contains("${user.home}")) {
            path = path.replace("${user.home}", userHome);
        }
        return path;
    }

    record unused() implements MavenLocalRepoLocator {}
}
