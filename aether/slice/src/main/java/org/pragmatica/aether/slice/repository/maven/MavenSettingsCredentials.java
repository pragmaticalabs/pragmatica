package org.pragmatica.aether.slice.repository.maven;

import org.pragmatica.lang.Option;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.Base64;

import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.option;


/// Reads Maven server credentials from ~/.m2/settings.xml.
///
/// Matches `<server>` entries by ID for repository authentication.
/// Supports username/password pairs for Basic authentication.
@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02", "JBCT-ZONE-02", "JBCT-ZONE-03"}) public sealed interface MavenSettingsCredentials {
    Logger log = LoggerFactory.getLogger(MavenSettingsCredentials.class);

    static Option<Credentials> forServer(String serverId) {
        var userHome = System.getProperty("user.home");
        return forServer(serverId, new File(userHome, ".m2/settings.xml"));
    }

    static Option<Credentials> forServer(String serverId, File settingsFile) {
        if (!settingsFile.exists()) {
            log.debug("Settings file not found: {}", settingsFile);
            return Option.empty();
        }
        return parseSettingsFile(serverId, settingsFile);
    }

    private static Option<Credentials> parseSettingsFile(String serverId, File settingsFile) {
        try {
            var dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            var db = dbf.newDocumentBuilder();
            var doc = db.parse(settingsFile);
            var servers = doc.getElementsByTagName("server");
            return findServer(serverId, servers);
        } catch (Exception e) {
            log.debug("Failed to read Maven settings from {}: {}", settingsFile, e.getMessage());
            return Option.empty();
        }
    }

    private static Option<Credentials> findServer(String serverId, NodeList servers) {
        for (int i = 0;i <servers.getLength();i++) {
            var match = extractCredentials(serverId, servers.item(i));
            if (match.isPresent()) {return match;}
        }
        return Option.empty();
    }

    private static Option<Credentials> extractCredentials(String serverId, Node server) {
        var children = server.getChildNodes();
        String id = null;
        String username = null;
        String password = null;
        for (int j = 0;j <children.getLength();j++) {
            var child = children.item(j);
            switch (child.getNodeName()){
                case "id" -> id = child.getTextContent().trim();
                case "username" -> username = child.getTextContent().trim();
                case "password" -> password = child.getTextContent().trim();
                default -> {}
            }
        }
        if (serverId.equals(id) && username != null && password != null) {return Option.some(new Credentials(username,
                                                                                                             password));}
        return Option.empty();
    }

    record Credentials(String username, String password) {
        public String toBasicAuthHeader() {
            return "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes());
        }
    }

    record unused() implements MavenSettingsCredentials{}
}
