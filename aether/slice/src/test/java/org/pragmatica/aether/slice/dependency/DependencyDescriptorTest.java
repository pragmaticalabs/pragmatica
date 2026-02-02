package org.pragmatica.aether.slice.dependency;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DependencyDescriptorTest {

    @Test
    void parse_simple_dependency_without_param_name() {
        DependencyDescriptor.dependencyDescriptor("com.example.UserService:1.2.3")
                            .onFailureRun(Assertions::fail)
                            .onSuccess(descriptor -> {
                                assertThat(descriptor.sliceClassName()).isEqualTo("com.example.UserService");
                                assertThat(descriptor.versionPattern().asString()).isEqualTo("1.2.3");
                                assertThat(descriptor.parameterName().isEmpty()).isTrue();
                            });
    }

    @Test
    void parse_dependency_with_param_name() {
        DependencyDescriptor.dependencyDescriptor("com.example.EmailService:^1.0.0:emailService")
                            .onFailureRun(Assertions::fail)
                            .onSuccess(descriptor -> {
                                assertThat(descriptor.sliceClassName()).isEqualTo("com.example.EmailService");
                                assertThat(descriptor.versionPattern().asString()).isEqualTo("^1.0.0");
                                descriptor.parameterName().onEmpty(Assertions::fail)
                                          .onPresent(name -> assertThat(name).isEqualTo("emailService"));
                            });
    }

    @Test
    void parse_dependency_with_range_version() {
        DependencyDescriptor.dependencyDescriptor("com.example.OrderProcessor:[1.0.0,2.0.0):orderProcessor")
                            .onFailureRun(Assertions::fail)
                            .onSuccess(descriptor -> {
                                assertThat(descriptor.sliceClassName()).isEqualTo("com.example.OrderProcessor");
                                assertThat(descriptor.versionPattern().asString()).isEqualTo("[1.0.0,2.0.0)");
                                descriptor.parameterName().onEmpty(Assertions::fail)
                                          .onPresent(name -> assertThat(name).isEqualTo("orderProcessor"));
                            });
    }

    @Test
    void parse_dependency_with_comparison_version() {
        DependencyDescriptor.dependencyDescriptor("com.example.CacheService:>=2.5.0")
                            .onFailureRun(Assertions::fail)
                            .onSuccess(descriptor -> {
                                assertThat(descriptor.sliceClassName()).isEqualTo("com.example.CacheService");
                                assertThat(descriptor.versionPattern().asString()).isEqualTo(">=2.5.0");
                            });
    }

    @Test
    void parse_empty_line_returns_failure() {
        DependencyDescriptor.dependencyDescriptor("")
                            .onSuccessRun(Assertions::fail)
                            .onFailure(cause -> assertThat(cause.message()).contains("empty"));

        DependencyDescriptor.dependencyDescriptor("   ")
                            .onSuccessRun(Assertions::fail)
                            .onFailure(cause -> assertThat(cause.message()).contains("empty"));
    }

    @Test
    void parse_comment_line_returns_failure() {
        DependencyDescriptor.dependencyDescriptor("# This is a comment")
                            .onSuccessRun(Assertions::fail)
                            .onFailure(cause -> assertThat(cause.message()).contains("comment"));
    }

    @Test
    void parse_invalid_format_returns_failure() {
        // Missing version pattern
        DependencyDescriptor.dependencyDescriptor("com.example.UserService")
                            .onSuccessRun(Assertions::fail)
                            .onFailure(cause -> assertThat(cause.message()).contains("Invalid"));

        // Too many parts
        DependencyDescriptor.dependencyDescriptor("com.example.UserService:1.0.0:param:extra")
                            .onSuccessRun(Assertions::fail)
                            .onFailure(cause -> assertThat(cause.message()).contains("Too many"));
    }

    @Test
    void parse_empty_class_name_returns_failure() {
        DependencyDescriptor.dependencyDescriptor(":1.2.3")
                            .onSuccessRun(Assertions::fail)
                            .onFailure(cause -> assertThat(cause.message()).contains("Empty class name"));
    }

    @Test
    void parse_empty_version_returns_failure() {
        DependencyDescriptor.dependencyDescriptor("com.example.UserService:")
                            .onSuccessRun(Assertions::fail)
                            .onFailure(cause -> assertThat(cause.message()).contains("Empty version pattern"));
    }

    @Test
    void asString_roundtrip_without_param_name() {
        var original = "com.example.UserService:1.2.3";
        DependencyDescriptor.dependencyDescriptor(original)
                            .map(DependencyDescriptor::asString)
                            .onFailureRun(Assertions::fail)
                            .onSuccess(result -> assertThat(result).isEqualTo(original));
    }

    @Test
    void asString_roundtrip_with_param_name() {
        var original = "com.example.EmailService:^1.0.0:emailService";
        DependencyDescriptor.dependencyDescriptor(original)
                            .map(DependencyDescriptor::asString)
                            .onFailureRun(Assertions::fail)
                            .onSuccess(result -> assertThat(result).isEqualTo(original));
    }

    @Test
    void asString_roundtrip_with_range() {
        var original = "com.example.OrderProcessor:[1.0.0,2.0.0):orderProcessor";
        DependencyDescriptor.dependencyDescriptor(original)
                            .map(DependencyDescriptor::asString)
                            .onFailureRun(Assertions::fail)
                            .onSuccess(result -> assertThat(result).isEqualTo(original));
    }

    @Test
    void parse_handles_whitespace() {
        DependencyDescriptor.dependencyDescriptor("  com.example.UserService  :  1.2.3  :  userService  ")
                            .onFailureRun(Assertions::fail)
                            .onSuccess(descriptor -> {
                                assertThat(descriptor.sliceClassName()).isEqualTo("com.example.UserService");
                                assertThat(descriptor.versionPattern().asString()).isEqualTo("1.2.3");
                                descriptor.parameterName().onPresent(name -> assertThat(name).isEqualTo("userService"));
                            });
    }
}
