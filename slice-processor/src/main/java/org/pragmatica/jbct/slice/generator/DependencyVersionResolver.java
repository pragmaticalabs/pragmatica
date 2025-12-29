package org.pragmatica.jbct.slice.generator;

import org.pragmatica.jbct.slice.model.DependencyModel;

import javax.annotation.processing.ProcessingEnvironment;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.util.Properties;

public class DependencyVersionResolver {
    private final ProcessingEnvironment env;
    private Properties sliceDeps;
    private boolean loaded;

    public DependencyVersionResolver(ProcessingEnvironment env) {
        this.env = env;
    }

    public DependencyModel resolve(DependencyModel dependency) {
        loadIfNeeded();
        var interfacePackage = dependency.interfacePackage();
        if (interfacePackage == null || interfacePackage.isEmpty()) {
            return dependency.withResolved("unknown:unknown", "UNRESOLVED");
        }
        var sliceArtifact = findSliceArtifact(interfacePackage);
        var version = findVersion(sliceArtifact);
        return dependency.withResolved(sliceArtifact, version);
    }

    private void loadIfNeeded() {
        if (loaded) {
            return;
        }
        loaded = true;
        sliceDeps = new Properties();
        try{
            FileObject resource = env.getFiler()
                                     .getResource(StandardLocation.CLASS_OUTPUT, "", "slice-deps.properties");
            try (var reader = resource.openReader(true)) {
                sliceDeps.load(reader);
            }
        } catch (IOException e) {
            // File might not exist yet - dependencies will remain unresolved
            env.getMessager()
               .printMessage(javax.tools.Diagnostic.Kind.NOTE,
                             "slice-deps.properties not found, dependency versions will be unresolved");
        }
    }

    private String findSliceArtifact(String interfacePackage) {
        // Try to find manifest in classpath that maps this interface to an artifact
        // For now, derive from package: org.example.inventory -> org.example:inventory
        var parts = interfacePackage.split("\\.");
        if (parts.length < 2) {
            return interfacePackage + ":unknown";
        }
        var groupId = String.join(".", java.util.Arrays.copyOf(parts, parts.length - 1));
        var artifactId = parts[parts.length - 1];
        return groupId + ":" + artifactId;
    }

    private String findVersion(String sliceArtifact) {
        // Properties keys are stored unescaped after loading
        var key = sliceArtifact + ":api";
        var version = sliceDeps.getProperty(key);
        return version != null
               ? version
               : "UNRESOLVED";
    }
}
