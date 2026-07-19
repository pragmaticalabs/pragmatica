package org.pragmatica.jbct.lint.cst.filetype;

import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.jbct.parser.Java25Parser;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/// One fixture per [FileType] plus UNCLASSIFIED cases (#453).
///
/// Each snippet is the minimal syntax that must route to the expected role, exercising the
/// precedence order (error → utility → use case → value object → step) and the test-annotation
/// override.
class FileTypeClassifierTest {
    private final Java25Parser parser = new Java25Parser();

    @Test
    void classify_useCaseInterface_isUseCase() {
        assertType(FileType.USE_CASE, """
                package com.example.usecase.register;
                public interface RegisterUser {
                    record Request(String email) {}
                    record Response(String id) {}
                    interface CheckEmail { Result<Request> apply(Request request); }
                    static RegisterUser registerUser(CheckEmail checkEmail) {
                        return request -> checkEmail.apply(request);
                    }
                    Promise<Response> execute(Request request);
                }
                """);
    }

    @Test
    void classify_recordWithResultFactory_isValueObject() {
        assertType(FileType.VALUE_OBJECT, """
                package com.example.domain;
                public record Email(String value) {
                    public static Result<Email> email(String raw) {
                        return Result.success(new Email(raw));
                    }
                }
                """);
    }

    @Test
    void classify_interfaceExtendsCause_isErrorType() {
        assertType(FileType.ERROR_TYPE, """
                package com.example.usecase.register;
                public sealed interface RegistrationError extends Cause {
                    record EmailTaken(String email) implements RegistrationError {}
                }
                """);
    }

    @Test
    void classify_singleMethodInterface_isStepInterface() {
        assertType(FileType.STEP_INTERFACE, """
                package com.example.usecase.register;
                public interface CheckEmail {
                    Promise<ValidRequest> apply(ValidRequest request);
                }
                """);
    }

    @Test
    void classify_sealedInterfaceWithUnused_isUtilityInterface() {
        assertType(FileType.UTILITY_INTERFACE, """
                package com.example.shared;
                public sealed interface ValidationUtils {
                    static Result<String> normalize(String raw) { return Result.success(raw); }
                    record unused() implements ValidationUtils {}
                }
                """);
    }

    @Test
    void classify_classWithTestMethod_isTestClass() {
        assertType(FileType.TEST_CLASS, """
                package com.example.usecase.register;
                class RegisterUserTest {
                    @Test
                    void execute_succeeds_forValidInput() {}
                }
                """);
    }

    @Test
    void classify_plainDataClass_isUnclassified() {
        assertType(FileType.UNCLASSIFIED, """
                package com.example.adapter;
                public class Widget {
                    private int count;
                    public int count() { return count; }
                }
                """);
    }

    @Test
    void classify_recordWithoutResultFactory_isUnclassified() {
        assertType(FileType.UNCLASSIFIED, """
                package com.example.domain;
                public record PlainPoint(int x, int y) {}
                """);
    }

    @Test
    void classify_errorTypeWinsOverUseCaseShape() {
        // extends Cause takes precedence even when an execute method is present.
        assertType(FileType.ERROR_TYPE, """
                package com.example.usecase.register;
                public sealed interface RegistrationError extends Cause {
                    void execute();
                }
                """);
    }

    @Test
    void classify_utilityWinsOverStepShape() {
        // A sealed utility interface with a single static method is not read as a step interface.
        assertType(FileType.UTILITY_INTERFACE, """
                package com.example.shared;
                public sealed interface PhoneUtils {
                    static Result<String> normalize(String raw) { return Result.success(raw); }
                    record unused() implements PhoneUtils {}
                }
                """);
    }

    @Test
    void classify_recordImplementingCauseWithFactory_isErrorType() {
        // ERROR_TYPE precedence: implements Cause wins even with a Result factory present.
        assertType(FileType.ERROR_TYPE, """
                package org.example;
                public record Failure(String message) implements Cause {
                    public static Result<Failure> failure(String raw) {
                        return Result.success(new Failure(raw));
                    }
                    public String message() {
                        return message;
                    }
                }
                """);
    }

    @Test
    void classify_multipleTopLevelTypes_picksPublicPrincipal() {
        // The public type is the principal even when a non-public type is declared first.
        assertType(FileType.VALUE_OBJECT, """
                package org.example;
                class Helper {}
                public record Email(String value) {
                    public static Result<Email> email(String raw) {
                        return Result.success(new Email(raw));
                    }
                }
                """);
    }

    @Test
    void classify_optionOnlyFactoryVo_isUnclassified() {
        // Documented FN: a value object whose sole factory returns Option<T> is not recognised.
        assertType(FileType.UNCLASSIFIED, """
                package org.example;
                public record Nickname(String value) {
                    public static Option<Nickname> nickname(String raw) {
                        return Option.option(raw).map(Nickname::new);
                    }
                }
                """);
    }

    @Test
    void classify_comparableOfCause_isNotErrorType() {
        // Cause appearing only inside a generic argument must not classify as an error type.
        assertType(FileType.UNCLASSIFIED, """
                package org.example;
                public class Ordering implements Comparable<Cause> {
                    public int compareTo(Cause other) {
                        return 0;
                    }
                }
                """);
    }

    @Test
    void classify_extendsSameFileCauseInterface_isErrorType() {
        // Single same-file hop: a principal extending an in-file Cause-extending interface is an error type.
        assertType(FileType.ERROR_TYPE, """
                package org.example;
                public sealed interface OrderError extends BaseError {
                    record Rejected() implements OrderError {}
                }
                interface BaseError extends Cause {}
                """);
    }

    @Test
    void classify_sealedUtilityWithSuppressAnnotation_isUtilityInterface() {
        // A multi-value @SuppressWarnings before the type must not truncate the header and hide 'sealed'.
        assertType(FileType.UTILITY_INTERFACE, """
                package org.example;
                @SuppressWarnings({"unchecked", "rawtypes"})
                public sealed interface PhoneUtils {
                    static Result<String> normalize(String raw) { return Result.success(raw); }
                    record unused() implements PhoneUtils {}
                }
                """);
    }

    @Test
    void classify_multiMethodExecuteInterface_isUnclassified() {
        // An execute-bearing SPI with extra methods and no Request/Response or self-factory is not a use case.
        assertType(FileType.UNCLASSIFIED, """
                package org.example;
                public interface MigrationOrchestrator {
                    void execute();
                    void rollback();
                }
                """);
    }

    @Test
    void classify_executeOnlySam_isStepInterface() {
        // An execute-only single-abstract-method interface falls through to a step interface, not a use case.
        assertType(FileType.STEP_INTERFACE, """
                package org.example;
                public interface DockerCommandRunner {
                    Result<String> execute(String command);
                }
                """);
    }

    @Test
    void classify_serviceRecordImplementingExternalSpi_isUnclassified() {
        // A record implementing an out-of-file SPI is a service adapter, not a value object.
        assertType(FileType.UNCLASSIFIED, """
                package org.example;
                public record AwsComputeProvider(String region) implements ComputeProvider {
                    public static Result<AwsComputeProvider> awsComputeProvider(String region) {
                        return Result.success(new AwsComputeProvider(region));
                    }
                }
                """);
    }

    @Test
    void classify_entryPointRecordWithMain_isUnclassified() {
        // A record declaring a main method is an entry point, not a value object.
        assertType(FileType.UNCLASSIFIED, """
                package org.example;
                public record App(Config config) {
                    public static Result<App> app(Config config) {
                        return Result.success(new App(config));
                    }
                    public static void main(String[] args) {
                        app(null);
                    }
                }
                """);
    }

    private void assertType(FileType expected, String source) {
        assertEquals(expected, FileTypeClassifier.classify(parse(source)));
    }

    private Cursor parse(String source) {
        return parser.parse(source)
                     .onFailure(cause -> fail("Parse failed: " + cause.message()))
                     .or((Cursor) null);
    }
}
