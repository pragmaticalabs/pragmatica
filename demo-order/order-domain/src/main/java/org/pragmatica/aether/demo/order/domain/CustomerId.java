package org.pragmatica.aether.demo.order.domain;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.utils.Causes;

import java.util.regex.Pattern;

public record CustomerId(String value) {
    private static final Pattern CUSTOMER_ID_PATTERN = Pattern.compile("^CUST-[0-9]{8}$");
    private static final Fn1<Cause, String> INVALID_CUSTOMER_ID = Causes.forValue("Invalid customer ID: {}");

    public static Result<CustomerId> customerId(String raw) {
        return Verify.ensure(raw, Verify.Is::notBlank)
                     .flatMap(Verify.ensureFn(INVALID_CUSTOMER_ID, Verify.Is::matches, CUSTOMER_ID_PATTERN))
                     .map(CustomerId::new);
    }
}
