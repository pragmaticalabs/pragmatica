package org.pragmatica.aether.slice.dependency;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import static org.pragmatica.lang.Result.success;


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
    @SuppressWarnings("JBCT-RET-03") static Result<List<DependencyDescriptor>> load(String sliceClassName,
                                                                                    ClassLoader classLoader) {
        var resourcePath = "META-INF/dependencies/" + sliceClassName;
        var resource = classLoader.getResourceAsStream(resourcePath);
        if (resource == null) {return success(List.of());}
        return Result.lift(Causes::fromThrowable, () -> readDependencies(resource));
    }

    @SuppressWarnings("JBCT-EX-01") private static List<DependencyDescriptor> readDependencies(InputStream resource) throws IOException {
        try (var reader = new BufferedReader(new InputStreamReader(resource))) {
            var dependencies = new ArrayList<DependencyDescriptor>();
            String line;
            while ((line = reader.readLine()) != null) {DependencyDescriptor.dependencyDescriptor(line)
                                                                                                 .onSuccess(dependencies::add);}
            return dependencies;
        }
    }
}
