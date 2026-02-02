package org.pragmatica.aether.slice.dependency;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;

import static org.assertj.core.api.Assertions.assertThat;

class SliceDependenciesTest {

    @Test
    void load_parses_multiple_dependencies() {
        var content = """
                com.example.UserService:1.2.3:userService
                com.example.EmailService:^1.0.0:emailService
                com.example.PaymentProcessor:[1.5.0,2.0.0):paymentProcessor
                """;

        var classLoader = createClassLoaderWithResource("META-INF/dependencies/com.example.OrderService", content);

        SliceDependencies.load("com.example.OrderService", classLoader)
                         .onFailureRun(Assertions::fail)
                         .onSuccess(dependencies -> {
                             assertThat(dependencies).hasSize(3);

                             assertThat(dependencies.get(0).sliceClassName()).isEqualTo("com.example.UserService");
                             assertThat(dependencies.get(0).versionPattern().asString()).isEqualTo("1.2.3");

                             assertThat(dependencies.get(1).sliceClassName()).isEqualTo("com.example.EmailService");
                             assertThat(dependencies.get(1).versionPattern().asString()).isEqualTo("^1.0.0");

                             assertThat(dependencies.get(2).sliceClassName()).isEqualTo("com.example.PaymentProcessor");
                             assertThat(dependencies.get(2).versionPattern().asString()).isEqualTo("[1.5.0,2.0.0)");
                         });
    }

    @Test
    void load_ignores_comments_and_empty_lines() {
        var content = """
                # Service dependencies
                com.example.UserService:1.2.3
                
                # Email integration
                com.example.EmailService:^1.0.0
                
                """;

        var classLoader = createClassLoaderWithResource("META-INF/dependencies/com.example.OrderService", content);

        SliceDependencies.load("com.example.OrderService", classLoader)
                         .onFailureRun(Assertions::fail)
                         .onSuccess(dependencies -> {
                             assertThat(dependencies).hasSize(2);
                             assertThat(dependencies.get(0).sliceClassName()).isEqualTo("com.example.UserService");
                             assertThat(dependencies.get(1).sliceClassName()).isEqualTo("com.example.EmailService");
                         });
    }

    @Test
    void load_returns_empty_list_when_no_file() {
        var classLoader = new URLClassLoader(new URL[0]);

        SliceDependencies.load("com.example.NonExistent", classLoader)
                         .onFailureRun(Assertions::fail)
                         .onSuccess(dependencies -> assertThat(dependencies).isEmpty());
    }

    @Test
    void load_returns_empty_list_for_empty_file() {
        var content = "";

        var classLoader = createClassLoaderWithResource("META-INF/dependencies/com.example.Empty", content);

        SliceDependencies.load("com.example.Empty", classLoader)
                         .onFailureRun(Assertions::fail)
                         .onSuccess(dependencies -> assertThat(dependencies).isEmpty());
    }

    @Test
    void load_handles_file_with_only_comments() {
        var content = """
                # This is a comment
                # Another comment
                """;

        var classLoader = createClassLoaderWithResource("META-INF/dependencies/com.example.Commented", content);

        SliceDependencies.load("com.example.Commented", classLoader)
                         .onFailureRun(Assertions::fail)
                         .onSuccess(dependencies -> assertThat(dependencies).isEmpty());
    }

    @Test
    void load_handles_mixed_valid_and_invalid_lines() {
        var content = """
                com.example.UserService:1.2.3
                # Comment line
                com.example.EmailService:^1.0.0
                
                com.example.CacheService:>=2.0.0
                """;

        var classLoader = createClassLoaderWithResource("META-INF/dependencies/com.example.Mixed", content);

        SliceDependencies.load("com.example.Mixed", classLoader)
                         .onFailureRun(Assertions::fail)
                         .onSuccess(dependencies -> {
                             assertThat(dependencies).hasSize(3);
                             assertThat(dependencies.get(0).sliceClassName()).isEqualTo("com.example.UserService");
                             assertThat(dependencies.get(1).sliceClassName()).isEqualTo("com.example.EmailService");
                             assertThat(dependencies.get(2).sliceClassName()).isEqualTo("com.example.CacheService");
                         });
    }

    private ClassLoader createClassLoaderWithResource(String resourcePath, String content) {
        return new ClassLoader() {
            @Override
            public InputStream getResourceAsStream(String name) {
                if (name.equals(resourcePath)) {
                    return new ByteArrayInputStream(content.getBytes());
                }
                return null;
            }
        };
    }
}
