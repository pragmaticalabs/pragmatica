package org.pragmatica.aether.example.shared;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.utils.Causes;

/**
 * Shipping address with validation.
 */
public record Address(String street,
                      String city,
                      String state,
                      String postalCode,
                      String country) {
    private static final Fn1<Cause, String> BLANK_FIELD = Causes.forOneValue("Address field cannot be blank: %s");

    public static Result<Address> address(String street, String city, String state, String postalCode, String country) {
        return Result.all(validateField(street, "street"),
                          validateField(city, "city"),
                          validateField(state, "state"),
                          validateField(postalCode, "postalCode"),
                          validateField(country, "country"))
                     .map(Address::new);
    }

    private static Result<String> validateField(String value, String fieldName) {
        return Verify.ensure(value,
                             Verify.Is::notBlank,
                             _ -> BLANK_FIELD.apply(fieldName))
                     .map(String::trim);
    }

    public String formatted() {
        return String.join(", ", street, city, state, postalCode, country);
    }
}
