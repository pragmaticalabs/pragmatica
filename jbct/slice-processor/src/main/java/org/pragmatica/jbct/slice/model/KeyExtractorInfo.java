package org.pragmatica.jbct.slice.model;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.util.regex.Pattern;

/**
 * Information about cache key extraction from request type.
 *
 * @param keyType             Fully qualified type name of the cache key
 * @param extractorExpression Java expression for key extraction (e.g., "Request::userId" or "request -> request")
 */
public record KeyExtractorInfo(String keyType, String extractorExpression) {
    private static final Pattern JAVA_IDENTIFIER = Pattern.compile("^[a-zA-Z_$][a-zA-Z0-9_$]*$");

    /**
     * Create extractor for a single @Key-annotated field.
     *
     * @param keyType       Type of the annotated field
     * @param fieldName     Name of the annotated field (must be valid Java identifier)
     * @param paramTypeName Qualified name of the parameter type (for method reference)
     * @return Result containing key extractor using method reference, or failure if validation fails
     */
    public static Result<KeyExtractorInfo> single(String keyType, String fieldName, String paramTypeName) {
        if (keyType == null || keyType.isEmpty()) {
            return Causes.cause("Key type cannot be null or empty")
                         .result();
        }
        if (fieldName == null || !JAVA_IDENTIFIER.matcher(fieldName)
                                                 .matches()) {
            return Causes.cause("Invalid field name for @Key: " + fieldName)
                         .result();
        }
        if (paramTypeName == null || paramTypeName.isEmpty()) {
            return Causes.cause("Parameter type name cannot be null or empty")
                         .result();
        }
        return Result.success(new KeyExtractorInfo(keyType, paramTypeName + "::" + fieldName));
    }

    /**
     * Create identity extractor (use entire request as key).
     *
     * @param requestType Type of the request parameter
     * @return Result containing key extractor using identity lambda, or failure if validation fails
     */
    public static Result<KeyExtractorInfo> identity(String requestType) {
        if (requestType == null || requestType.isEmpty()) {
            return Causes.cause("Request type cannot be null or empty")
                         .result();
        }
        return Result.success(new KeyExtractorInfo(requestType, "request -> request"));
    }
}
