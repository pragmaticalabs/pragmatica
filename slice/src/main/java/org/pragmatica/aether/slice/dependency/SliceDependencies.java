package org.pragmatica.aether.slice.dependency;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads slice dependencies from META-INF/dependencies/ descriptor file.
 * <p>
 * The descriptor file format:
 * - One dependency per line
 * - Format: {@code className:versionPattern[:paramName]}
 * - Comments start with #
 * - Empty lines ignored
 * <p>
 * Example META-INF/dependencies/com.example.OrderService:
 * <pre>
 * # Service dependencies
 * com.example.UserService:^1.0.0:userService
 * com.example.EmailService:>=2.0.0:emailService
 * com.example.PaymentProcessor:[1.5.0,2.0.0):paymentProcessor
 * </pre>
 */
public interface SliceDependencies {
    /**
     * Load dependencies from META-INF/dependencies/{sliceClassName} resource.
     *
     * @param sliceClassName Fully qualified class name of the slice
     * @param classLoader    ClassLoader to load resource from
     *
     * @return List of dependency descriptors, empty if file not found or no dependencies
     */
    static Result<List<DependencyDescriptor>> load(String sliceClassName, ClassLoader classLoader) {
        var resourcePath = "META-INF/dependencies/" + sliceClassName;
        var resource = classLoader.getResourceAsStream(resourcePath);
        if (resource == null) {
            // No dependencies file means no dependencies - this is valid
            return Result.success(List.of());
        }
        return Result.lift(Causes::fromThrowable,
                           () -> {
                               try (var reader = new BufferedReader(new InputStreamReader(resource))) {
                               var dependencies = new ArrayList<DependencyDescriptor>();
                               String line;
                               while ((line = reader.readLine()) != null) {
                               DependencyDescriptor.parse(line)
                                                   .onSuccess(dependencies::add);
                           }
                               return dependencies;
                           }
                           });
    }
}
