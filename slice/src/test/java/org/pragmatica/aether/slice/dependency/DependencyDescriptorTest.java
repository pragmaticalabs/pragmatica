package org.pragmatica.aether.slice.dependency;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DependencyDescriptorTest {

    @Test
    void parse_simple_dependency_without_param_name() {
        DependencyDescriptor.parse("com.example.UserService:1.2.3")
                            .onFailureRun(Assertions::fail)
                            .onSuccess(descriptor -> {
                                assertThat(descriptor.sliceClassName()).isEqualTo("com.example.UserService");
                                assertThat(descriptor.versionPattern().asString()).isEqualTo("1.2.3");
                                assertThat(descriptor.parameterName().isEmpty()).isTrue();
                            });
    }

    @Test
    void parse_dependency_with_param_name() {
        DependencyDescriptor.parse("com.example.EmailService:^1.0.0:emailService")
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
        DependencyDescriptor.parse("com.example.OrderProcessor:[1.0.0,2.0.0):orderProcessor")
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
        DependencyDescriptor.parse("com.example.CacheService:>=2.5.0")
                            .onFailureRun(Assertions::fail)
                            .onSuccess(descriptor -> {
                                assertThat(descriptor.sliceClassName()).isEqualTo("com.example.CacheService");
                                assertThat(descriptor.versionPattern().asString()).isEqualTo(">=2.5.0");
                            });
    }

    @Test
    void parse_empty_line_returns_failure() {
        DependencyDescriptor.parse("")
                            .onSuccessRun(() -> Assertions.fail("Should fail for empty line"))
                            .onFailure(cause -> assertThat(cause.message()).contains("empty"));

        DependencyDescriptor.parse("   ")
                            .onSuccessRun(() -> Assertions.fail("Should fail for blank line"))
                            .onFailure(cause -> assertThat(cause.message()).contains("empty"));
    }

    @Test
    void parse_comment_line_returns_failure() {
        DependencyDescriptor.parse("# This is a comment")
                            .onSuccessRun(() -> Assertions.fail("Should fail for comment line"))
                            .onFailure(cause -> assertThat(cause.message()).contains("comment"));
    }

    @Test
    void parse_invalid_format_returns_failure() {
        // Missing version pattern
        DependencyDescriptor.parse("com.example.UserService")
                            .onSuccessRun(() -> Assertions.fail("Should fail for missing version"))
                            .onFailure(cause -> assertThat(cause.message()).contains("Invalid"));

        // Too many parts
        DependencyDescriptor.parse("com.example.UserService:1.0.0:param:extra")
                            .onSuccessRun(() -> Assertions.fail("Should fail for too many parts"))
                            .onFailure(cause -> assertThat(cause.message()).contains("Too many"));
    }

    @Test
    void parse_empty_class_name_returns_failure() {
        DependencyDescriptor.parse(":1.2.3")
                            .onSuccessRun(() -> Assertions.fail("Should fail for empty class name"))
                            .onFailure(cause -> assertThat(cause.message()).contains("Empty class name"));
    }

    @Test
    void parse_empty_version_returns_failure() {
        DependencyDescriptor.parse("com.example.UserService:")
                            .onSuccessRun(() -> Assertions.fail("Should fail for empty version"))
                            .onFailure(cause -> assertThat(cause.message()).contains("Empty version pattern"));
    }

    @Test
    void asString_roundtrip_without_param_name() {
        var original = "com.example.UserService:1.2.3";
        DependencyDescriptor.parse(original)
                            .map(DependencyDescriptor::asString)
                            .onFailureRun(Assertions::fail)
                            .onSuccess(result -> assertThat(result).isEqualTo(original));
    }

    @Test
    void asString_roundtrip_with_param_name() {
        var original = "com.example.EmailService:^1.0.0:emailService";
        DependencyDescriptor.parse(original)
                            .map(DependencyDescriptor::asString)
                            .onFailureRun(Assertions::fail)
                            .onSuccess(result -> assertThat(result).isEqualTo(original));
    }

    @Test
    void asString_roundtrip_with_range() {
        var original = "com.example.OrderProcessor:[1.0.0,2.0.0):orderProcessor";
        DependencyDescriptor.parse(original)
                            .map(DependencyDescriptor::asString)
                            .onFailureRun(Assertions::fail)
                            .onSuccess(result -> assertThat(result).isEqualTo(original));
    }

    @Test
    void parse_handles_whitespace() {
        DependencyDescriptor.parse("  com.example.UserService  :  1.2.3  :  userService  ")
                            .onFailureRun(Assertions::fail)
                            .onSuccess(descriptor -> {
                                assertThat(descriptor.sliceClassName()).isEqualTo("com.example.UserService");
                                assertThat(descriptor.versionPattern().asString()).isEqualTo("1.2.3");
                                descriptor.parameterName().onPresent(name -> assertThat(name).isEqualTo("userService"));
                            });
    }
}
