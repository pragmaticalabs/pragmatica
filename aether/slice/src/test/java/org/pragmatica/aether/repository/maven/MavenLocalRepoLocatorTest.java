package org.pragmatica.aether.repository.maven;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.repository.maven.MavenLocalRepoLocator;

class MavenLocalRepoLocatorTest {
    @Test
    void localTest() {
        System.out.println(MavenLocalRepoLocator.findLocalRepository());
    }
}