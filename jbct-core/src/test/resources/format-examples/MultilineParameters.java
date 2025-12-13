package format.examples;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

public class MultilineParameters {
    // Short parameters - fits on one line
    Result<String> shortParams(String a, String b) {
        return Result.success(a + b);
    }

    // Parameters aligned to opening paren
    Result<ValidRequest> alignedParams(Email email, Password password, Option<ReferralCode> referral) {
        return Result.success(new ValidRequest(email, password, referral));
    }

    // Many parameters
    Result<Response> manyParams(String userId,
                                String token,
                                long expiresAt,
                                boolean active,
                                Option<String> refreshToken) {
        return Result.success(new Response(userId, token, expiresAt, active, refreshToken));
    }

    // Generic parameters
    static <T1, T2, T3>Result<Tuple3<T1, T2, T3>> genericParams(Result<T1> first, Result<T2> second, Result<T3> third) {
        return Result.all(first, second, third)
                     .map(Tuple3::new);
    }

    // Parameters with annotations
    Result<String> annotatedParams(@NotNull String required, @Nullable String optional, @Valid Request request) {
        return Result.success(required);
    }

    // Constructor with many parameters
    public MultilineParameters(Config config, Repository repository, Service service, Logger logger) {}

    // Static factory with many parameters
    static UserLogin userLogin(CheckCredentials checkCredentials,
                               CheckAccountStatus checkAccountStatus,
                               GenerateToken generateToken) {
        return null;
    }

    // Interface method with many parameters
    interface ComplexOperation {
        Result<Output> execute(Input input, Config config, Option<Context> context, Logger logger);
    }

    // Lambda type with many parameters
    interface MultiParamFunction<T1, T2, T3, R> {
        R apply(T1 first, T2 second, T3 third);
    }

    // Record with many components
    record LongRecord(String field1, String field2, int field3, boolean field4, Option<String> field5) {}

    // Stub types for compilation
    interface Email {}

    interface Password {}

    interface ReferralCode {}

    interface ValidRequest {
        ValidRequest(Object... args) {}
    }

    interface Response {
        Response(Object... args) {}
    }

    interface Request {}

    interface Config {}

    interface Repository {}

    interface Service {}

    interface Logger {}

    interface UserLogin {}

    interface CheckCredentials {}

    interface CheckAccountStatus {}

    interface GenerateToken {}

    interface Input {}

    interface Output {}

    interface Context {}

    record Tuple3<T1, T2, T3>(T1 first, T2 second, T3 third) {
        Tuple3(Object t) {
            this(null, null, null);
        }
    }

    @interface NotNull {}

    @interface Nullable {}

    @interface Valid {}
}
