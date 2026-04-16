package org.pragmatica.aether.slice.dependency;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.StreamOps;

import java.util.Arrays;
import java.util.List;


/// Loads slice dependencies from META-INF/dependencies/ descriptor file.
///
/// The descriptor file format:
/// - One dependency per line
/// - Format: `className:versionPattern[:paramName]`
/// - Comments start with #
/// - Empty lines ignored
///
/// Example META-INF/dependencies/com.example.OrderService:
/// ```
/// # Service dependencies
/// com.example.UserService:^1.0.0:userService
/// com.example.EmailService:>=2.0.0:emailService
/// com.example.PaymentProcessor:[1.5.0,2.0.0):paymentProcessor
/// ```
@SuppressWarnings({"JBCT-RET-05", "JBCT-PAT-01"}) public interface SliceDependencies {
    static Result<List<DependencyDescriptor>> load(String sliceClassName, ClassLoader classLoader) {
        return StreamOps.readResource(classLoader, "META-INF/dependencies/" + sliceClassName)
                        .map(SliceDependencies::parseDependencies)
                        .orElse(Result.success(List.of()));
    }

    private static List<DependencyDescriptor> parseDependencies(String content) {
        return Arrays.stream(content.split("\n"))
                     .flatMap(line -> DependencyDescriptor.dependencyDescriptor(line).stream())
                     .toList();
    }
}
